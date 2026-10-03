/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugin.compiler.incremental;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * {@link ClassAnalyzer} implementation using the bundled ASM library.
 *
 * <p>This implementation works on any JDK version supported by the plugin
 * (Java 17+) and is used as the default when the JVM version is below 24 or
 * when the {@code java.lang.classfile} API is not available.
 *
 * <p>Type references are collected from the class hierarchy, field/method
 * descriptors, exception types, annotations, and bytecode instructions
 * ({@code new}, {@code checkcast}, {@code instanceof}, field/method owners).
 *
 * <p>The ABI canonical form is derived from non-private, non-synthetic fields
 * and methods using erased types (no generics).
 *
 * @see BytecodeAnalyzer
 * @see ClassAnalyzer
 */
class AsmClassAnalyzer extends ClassAnalyzer {

    @Override
    public BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes) {
        var reader = new ClassReader(classBytes);
        var collector = new TypeCollector();
        reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String className = BytecodeAnalyzer.toJavaName(collector.className);
        collector.referencedTypes.remove(className);

        String abiCanonical = buildCanonical(reader, collector);
        String abiFingerprint = Sha256.hash(abiCanonical);

        return new BytecodeAnalyzer.ClassAnalysis(className, abiFingerprint, abiCanonical, collector.referencedTypes);
    }

    private static class TypeCollector extends ClassVisitor {
        String className;
        String superName;
        String classSignature;
        final Set<String> referencedTypes = new TreeSet<>();
        final List<String> interfaces = new ArrayList<>();

        TypeCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
            this.superName = superName;
            this.classSignature = signature;
            if (superName != null) {
                addRef(superName);
            }
            if (interfaces != null) {
                for (String iface : interfaces) {
                    this.interfaces.add(iface);
                    addRef(iface);
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addDescriptorTypes(descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            addDescriptorTypes(descriptor);
            if (exceptions != null) {
                for (String ex : exceptions) {
                    addRef(ex);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addRef(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    addRef(owner);
                }

                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    addRef(owner);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                    addDescriptorTypes(descriptor);
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptorTypes(descriptor);
            return null;
        }

        private void addRef(String internalName) {
            String resolved = resolveInternalName(internalName);
            if (resolved != null) {
                referencedTypes.add(resolved);
            }
        }

        private void addDescriptorTypes(String descriptor) {
            for (var type : Type.getArgumentTypes(descriptor.startsWith("(") ? descriptor : "()" + descriptor)) {
                addType(type);
            }
            if (descriptor.startsWith("(")) {
                addType(Type.getReturnType(descriptor));
            } else {
                addType(Type.getType(descriptor));
            }
        }

        private void addType(Type type) {
            if (type.getSort() == Type.ARRAY) {
                addType(type.getElementType());
            } else if (type.getSort() == Type.OBJECT) {
                referencedTypes.add(type.getClassName());
            }
        }
    }

    // --- ABI canonical form ---

    private static String buildCanonical(ClassReader reader, TypeCollector collector) {
        var fields = new ArrayList<FieldInfo>();
        var methods = new ArrayList<MethodInfo>();

        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(
                            int access, String name, String descriptor, String signature, Object value) {
                        if (!isPrivateOrSynthetic(access)) {
                            fields.add(new FieldInfo(access, name, descriptor, value, signature));
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!isPrivateOrSynthetic(access) && !"<clinit>".equals(name)) {
                            methods.add(new MethodInfo(access, name, descriptor, signature));
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String className = BytecodeAnalyzer.toJavaName(collector.className);
        String superName = collector.superName != null ? BytecodeAnalyzer.toJavaName(collector.superName) : null;
        var ifaceNames =
                collector.interfaces.stream().map(BytecodeAnalyzer::toJavaName).toList();

        return buildCanonicalForm(
                reader.getAccess(), className, collector.classSignature, superName, ifaceNames, fields, methods);
    }
}
