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
import java.util.Collections;
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
class AsmClassAnalyzer implements ClassAnalyzer {

    @Override
    public BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes) {
        var reader = new ClassReader(classBytes);
        var collector = new TypeCollector();
        reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String className = BytecodeAnalyzer.toJavaName(collector.className);
        collector.referencedTypes.remove(className);

        String abiCanonical = canonicalForm(reader, collector);
        String abiFingerprint = Sha256.hash(abiCanonical);

        return new BytecodeAnalyzer.ClassAnalysis(className, abiFingerprint, abiCanonical, collector.referencedTypes);
    }

    private static class TypeCollector extends ClassVisitor {
        String className;
        String superName;
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
            if (superName != null) {
                addInternalName(superName);
            }
            if (interfaces != null) {
                for (String iface : interfaces) {
                    this.interfaces.add(iface);
                    addInternalName(iface);
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
                    addInternalName(ex);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addInternalName(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    addInternalName(owner);
                }

                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    addInternalName(owner);
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

        private void addInternalName(String internalName) {
            if (internalName == null) {
                return;
            }
            // Unwrap array types
            while (internalName.startsWith("[")) {
                internalName = internalName.substring(1);
            }
            if (internalName.startsWith("L") && internalName.endsWith(";")) {
                internalName = internalName.substring(1, internalName.length() - 1);
            }
            if (internalName.isEmpty() || internalName.length() == 1) {
                return; // primitive
            }
            referencedTypes.add(BytecodeAnalyzer.toJavaName(internalName));
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

    private record FieldInfo(int access, String name, String descriptor, Object constantValue)
            implements Comparable<FieldInfo> {
        @Override
        public int compareTo(FieldInfo o) {
            return name.compareTo(o.name);
        }
    }

    private record MethodInfo(int access, String name, String descriptor, String[] exceptions)
            implements Comparable<MethodInfo> {
        @Override
        public int compareTo(MethodInfo o) {
            int c = name.compareTo(o.name);
            return c != 0 ? c : descriptor.compareTo(o.descriptor);
        }
    }

    private static String canonicalForm(ClassReader reader, TypeCollector collector) {
        var fields = new ArrayList<FieldInfo>();
        var methods = new ArrayList<MethodInfo>();

        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(
                            int access, String name, String descriptor, String signature, Object value) {
                        if ((access & Opcodes.ACC_PRIVATE) == 0 && (access & Opcodes.ACC_SYNTHETIC) == 0) {
                            fields.add(new FieldInfo(access, name, descriptor, value));
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ((access & Opcodes.ACC_PRIVATE) == 0
                                && (access & Opcodes.ACC_SYNTHETIC) == 0
                                && !"<clinit>".equals(name)) {
                            methods.add(new MethodInfo(access, name, descriptor, exceptions));
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        Collections.sort(fields);
        Collections.sort(methods);

        var sb = new StringBuilder();
        int classAccess = reader.getAccess();

        appendAccessFlags(sb, classAccess);
        if ((classAccess & Opcodes.ACC_INTERFACE) != 0) {
            sb.append("interface ");
        } else if ((classAccess & Opcodes.ACC_ENUM) != 0) {
            sb.append("enum ");
        } else {
            sb.append("class ");
        }
        sb.append(BytecodeAnalyzer.toJavaName(collector.className)).append('\n');

        if (collector.superName != null) {
            String sup = BytecodeAnalyzer.toJavaName(collector.superName);
            if (!"java.lang.Object".equals(sup) && !"java.lang.Enum".equals(sup) && !"java.lang.Record".equals(sup)) {
                sb.append("  extends ").append(sup).append('\n');
            }
        }

        for (String iface : collector.interfaces) {
            sb.append("  implements ")
                    .append(BytecodeAnalyzer.toJavaName(iface))
                    .append('\n');
        }

        for (var f : fields) {
            sb.append("  ");
            appendAccessFlags(sb, f.access);
            sb.append(BytecodeAnalyzer.descriptorToReadable(f.descriptor)).append(' ');
            sb.append(f.name);
            if (f.constantValue != null) {
                sb.append(" = ").append(f.constantValue);
            }
            sb.append('\n');
        }

        for (var m : methods) {
            sb.append("  ");
            appendAccessFlags(sb, m.access);
            sb.append(m.name);
            sb.append('(').append(BytecodeAnalyzer.parseParams(m.descriptor)).append(')');
            String ret = BytecodeAnalyzer.parseReturn(m.descriptor);
            if (!"void".equals(ret)) {
                sb.append(" -> ").append(ret);
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private static void appendAccessFlags(StringBuilder sb, int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            sb.append("public ");
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            sb.append("protected ");
        }
        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
            sb.append("abstract ");
        }
        if ((access & Opcodes.ACC_STATIC) != 0) {
            sb.append("static ");
        }
        if ((access & Opcodes.ACC_FINAL) != 0) {
            sb.append("final ");
        }
    }
}
