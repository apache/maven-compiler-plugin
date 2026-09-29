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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link ClassAnalyzer} implementation using the standard {@code java.lang.classfile}
 * API, available since JDK 24.
 *
 * <p>This implementation is loaded reflectively by {@link BytecodeAnalyzer} when
 * the running JVM version is 24 or later. It is compiled separately with
 * {@code --release 24} to avoid a compile-time dependency on the classfile API
 * in the main sources.
 *
 * <p>The analysis is functionally equivalent to {@link AsmClassAnalyzer}: it
 * collects the same type references (class hierarchy, field/method descriptors,
 * exception types, annotations, and bytecode instructions) and produces the
 * same ABI canonical form and fingerprint.
 *
 * @see BytecodeAnalyzer
 * @see AsmClassAnalyzer
 * @see ClassAnalyzer
 */
class ClassfileClassAnalyzer extends ClassAnalyzer {

    @Override
    public BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes) {
        ClassModel cm = ClassFile.of().parse(classBytes);

        String className = BytecodeAnalyzer.toJavaName(cm.thisClass().asInternalName());
        Set<String> referencedTypes = new TreeSet<>();

        collectReferencedTypes(cm, referencedTypes);
        referencedTypes.remove(className);

        String abiCanonical = buildCanonical(cm);
        String abiFingerprint = Sha256.hash(abiCanonical);

        return new BytecodeAnalyzer.ClassAnalysis(className, abiFingerprint, abiCanonical, referencedTypes);
    }

    // --- type reference collection ---

    private static void collectReferencedTypes(ClassModel cm, Set<String> referencedTypes) {
        // superclass
        cm.superclass().ifPresent(sup -> addRef(sup.asInternalName(), referencedTypes));

        // interfaces
        for (var iface : cm.interfaces()) {
            addRef(iface.asInternalName(), referencedTypes);
        }

        // annotations (visible and invisible)
        cm.findAttribute(Attributes.runtimeVisibleAnnotations())
                .ifPresent(a -> a.annotations()
                        .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));
        cm.findAttribute(Attributes.runtimeInvisibleAnnotations())
                .ifPresent(a -> a.annotations()
                        .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));

        // fields: descriptor types + field annotations
        for (FieldModel field : cm.fields()) {
            addDescriptor(field.fieldType().stringValue(), referencedTypes);
            field.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .ifPresent(a -> a.annotations()
                            .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));
            field.findAttribute(Attributes.runtimeInvisibleAnnotations())
                    .ifPresent(a -> a.annotations()
                            .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));
        }

        // methods: descriptor types, exception types, code instructions, method annotations
        for (MethodModel method : cm.methods()) {
            String methodDesc = method.methodType().stringValue();
            addMethodDescriptor(methodDesc, referencedTypes);

            method.findAttribute(Attributes.exceptions())
                    .ifPresent(ex -> ex.exceptions().forEach(e -> addRef(e.asInternalName(), referencedTypes)));

            method.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .ifPresent(a -> a.annotations()
                            .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));
            method.findAttribute(Attributes.runtimeInvisibleAnnotations())
                    .ifPresent(a -> a.annotations()
                            .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));

            method.code().ifPresent(code -> {
                for (var element : code) {
                    switch (element) {
                        case InvokeInstruction ii -> addRef(ii.owner().asInternalName(), referencedTypes);
                        case FieldInstruction fi -> addRef(fi.owner().asInternalName(), referencedTypes);
                        case TypeCheckInstruction tci -> addRef(tci.type().asInternalName(), referencedTypes);
                        case NewObjectInstruction noi -> addRef(noi.className().asInternalName(), referencedTypes);
                        case NewMultiArrayInstruction nma ->
                            addRef(nma.arrayType().asInternalName(), referencedTypes);
                        default -> {}
                    }
                }
            });
        }
    }

    // --- ABI canonical form ---

    private static String buildCanonical(ClassModel cm) {
        var fields = new ArrayList<FieldInfo>();
        var methods = new ArrayList<MethodInfo>();

        for (FieldModel field : cm.fields()) {
            int access = accessMask(field.flags().flags());
            if (!isPrivateOrSynthetic(access)) {
                Object constantValue = field.findAttribute(Attributes.constantValue())
                        .map(cv -> cv.constant().constantValue())
                        .orElse(null);
                fields.add(new FieldInfo(
                        access,
                        field.fieldName().stringValue(),
                        field.fieldType().stringValue(),
                        constantValue));
            }
        }

        for (MethodModel method : cm.methods()) {
            int access = accessMask(method.flags().flags());
            String name = method.methodName().stringValue();
            if (!isPrivateOrSynthetic(access) && !"<clinit>".equals(name)) {
                methods.add(new MethodInfo(access, name, method.methodType().stringValue()));
            }
        }

        int classAccess = accessMask(cm.flags().flags());
        String className = BytecodeAnalyzer.toJavaName(cm.thisClass().asInternalName());
        String superName = cm.superclass()
                .map(sup -> BytecodeAnalyzer.toJavaName(sup.asInternalName()))
                .orElse(null);
        List<String> ifaceNames = cm.interfaces().stream()
                .map(iface -> BytecodeAnalyzer.toJavaName(iface.asInternalName()))
                .toList();

        return buildCanonicalForm(classAccess, className, superName, ifaceNames, fields, methods);
    }

    // --- utilities ---

    private static int accessMask(Set<AccessFlag> flags) {
        int mask = 0;
        for (AccessFlag flag : flags) {
            mask |= flag.mask();
        }
        return mask;
    }

    private static void addRef(String internalName, Set<String> referencedTypes) {
        String resolved = resolveInternalName(internalName);
        if (resolved != null) {
            referencedTypes.add(resolved);
        }
    }

    private static void addDescriptor(String descriptor, Set<String> referencedTypes) {
        int i = 0;
        while (i < descriptor.length() && descriptor.charAt(i) == '[') {
            i++;
        }
        String core = descriptor.substring(i);
        if (core.startsWith("L") && core.endsWith(";")) {
            addRef(core.substring(1, core.length() - 1), referencedTypes);
        }
    }

    private static void addMethodDescriptor(String methodDesc, Set<String> referencedTypes) {
        int close = methodDesc.indexOf(')');
        int i = 1;
        while (i < close) {
            while (i < close && methodDesc.charAt(i) == '[') {
                i++;
            }
            if (i < close) {
                if (methodDesc.charAt(i) == 'L') {
                    int end = methodDesc.indexOf(';', i);
                    addRef(methodDesc.substring(i + 1, end), referencedTypes);
                    i = end + 1;
                } else {
                    i++;
                }
            }
        }
        addDescriptor(methodDesc.substring(close + 1), referencedTypes);
    }
}
