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
import java.util.Collections;
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
class ClassfileClassAnalyzer implements ClassAnalyzer {

    @Override
    public BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes) {
        ClassModel cm = ClassFile.of().parse(classBytes);

        String className = toJavaName(cm.thisClass().asInternalName());
        Set<String> referencedTypes = new TreeSet<>();

        // --- collect referenced types ---

        // superclass
        cm.superclass().ifPresent(sup -> addInternalName(sup.asInternalName(), referencedTypes));

        // interfaces
        for (var iface : cm.interfaces()) {
            addInternalName(iface.asInternalName(), referencedTypes);
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
                    .ifPresent(
                            ex -> ex.exceptions().forEach(e -> addInternalName(e.asInternalName(), referencedTypes)));

            method.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .ifPresent(a -> a.annotations()
                            .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));
            method.findAttribute(Attributes.runtimeInvisibleAnnotations())
                    .ifPresent(a -> a.annotations()
                            .forEach(ann -> addDescriptor(ann.className().stringValue(), referencedTypes)));

            method.code().ifPresent(code -> {
                for (var element : code) {
                    switch (element) {
                        case InvokeInstruction ii -> addInternalName(ii.owner().asInternalName(), referencedTypes);
                        case FieldInstruction fi -> addInternalName(fi.owner().asInternalName(), referencedTypes);
                        case TypeCheckInstruction tci ->
                            addInternalName(tci.type().asInternalName(), referencedTypes);
                        case NewObjectInstruction noi ->
                            addInternalName(noi.className().asInternalName(), referencedTypes);
                        case NewMultiArrayInstruction nma ->
                            addInternalName(nma.arrayType().asInternalName(), referencedTypes);
                        default -> {}
                    }
                }
            });
        }

        referencedTypes.remove(className);

        String abiCanonical = canonicalForm(cm);
        String abiFingerprint = Sha256.hash(abiCanonical);

        return new BytecodeAnalyzer.ClassAnalysis(className, abiFingerprint, abiCanonical, referencedTypes);
    }

    // --- ABI canonical form ---

    private record FieldInfo(Set<AccessFlag> flags, String name, String descriptor, Object constantValue)
            implements Comparable<FieldInfo> {
        @Override
        public int compareTo(FieldInfo o) {
            return name.compareTo(o.name);
        }
    }

    private record MethodInfo(Set<AccessFlag> flags, String name, String descriptor) implements Comparable<MethodInfo> {
        @Override
        public int compareTo(MethodInfo o) {
            int c = name.compareTo(o.name);
            return c != 0 ? c : descriptor.compareTo(o.descriptor);
        }
    }

    private static String canonicalForm(ClassModel cm) {
        var fields = new ArrayList<FieldInfo>();
        var methods = new ArrayList<MethodInfo>();

        for (FieldModel field : cm.fields()) {
            Set<AccessFlag> flags = field.flags().flags();
            if (!flags.contains(AccessFlag.PRIVATE) && !flags.contains(AccessFlag.SYNTHETIC)) {
                Object constantValue = field.findAttribute(Attributes.constantValue())
                        .map(cv -> cv.constant().constantValue())
                        .orElse(null);
                fields.add(new FieldInfo(
                        flags,
                        field.fieldName().stringValue(),
                        field.fieldType().stringValue(),
                        constantValue));
            }
        }

        for (MethodModel method : cm.methods()) {
            Set<AccessFlag> flags = method.flags().flags();
            String name = method.methodName().stringValue();
            if (!flags.contains(AccessFlag.PRIVATE)
                    && !flags.contains(AccessFlag.SYNTHETIC)
                    && !"<clinit>".equals(name)) {
                methods.add(new MethodInfo(flags, name, method.methodType().stringValue()));
            }
        }

        Collections.sort(fields);
        Collections.sort(methods);

        var sb = new StringBuilder();
        Set<AccessFlag> classFlags = cm.flags().flags();

        appendAccessFlags(sb, classFlags);
        if (classFlags.contains(AccessFlag.INTERFACE)) {
            sb.append("interface ");
        } else if (classFlags.contains(AccessFlag.ENUM)) {
            sb.append("enum ");
        } else {
            sb.append("class ");
        }
        sb.append(toJavaName(cm.thisClass().asInternalName())).append('\n');

        cm.superclass().ifPresent(sup -> {
            String superName = toJavaName(sup.asInternalName());
            if (!"java.lang.Object".equals(superName)
                    && !"java.lang.Enum".equals(superName)
                    && !"java.lang.Record".equals(superName)) {
                sb.append("  extends ").append(superName).append('\n');
            }
        });

        for (var iface : cm.interfaces()) {
            sb.append("  implements ")
                    .append(toJavaName(iface.asInternalName()))
                    .append('\n');
        }

        for (var f : fields) {
            sb.append("  ");
            appendAccessFlags(sb, f.flags);
            sb.append(BytecodeAnalyzer.descriptorToReadable(f.descriptor)).append(' ');
            sb.append(f.name);
            if (f.constantValue != null) {
                sb.append(" = ").append(f.constantValue);
            }
            sb.append('\n');
        }

        for (var m : methods) {
            sb.append("  ");
            appendAccessFlags(sb, m.flags);
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

    private static void appendAccessFlags(StringBuilder sb, Set<AccessFlag> flags) {
        if (flags.contains(AccessFlag.PUBLIC)) {
            sb.append("public ");
        }
        if (flags.contains(AccessFlag.PROTECTED)) {
            sb.append("protected ");
        }
        if (flags.contains(AccessFlag.ABSTRACT)) {
            sb.append("abstract ");
        }
        if (flags.contains(AccessFlag.STATIC)) {
            sb.append("static ");
        }
        if (flags.contains(AccessFlag.FINAL)) {
            sb.append("final ");
        }
    }

    // --- type name helpers ---

    private static String toJavaName(String internalName) {
        return internalName.replace('/', '.');
    }

    /**
     * Adds a field/class descriptor (e.g. {@code Ljava/lang/String;} or
     * {@code [Ljava/util/List;}) to the referenced types set.
     */
    private static void addDescriptor(String descriptor, Set<String> referencedTypes) {
        // Unwrap array dimensions
        int i = 0;
        while (i < descriptor.length() && descriptor.charAt(i) == '[') {
            i++;
        }
        String core = descriptor.substring(i);
        if (core.startsWith("L") && core.endsWith(";")) {
            addInternalName(core.substring(1, core.length() - 1), referencedTypes);
        }
        // primitives and void (single char, no 'L') are ignored
    }

    /**
     * Adds a method descriptor's parameter types and return type to the set.
     */
    private static void addMethodDescriptor(String methodDesc, Set<String> referencedTypes) {
        int close = methodDesc.indexOf(')');
        // parse parameters
        int i = 1;
        while (i < close) {
            int start = i;
            while (i < close && methodDesc.charAt(i) == '[') {
                i++;
            }
            if (i < close) {
                if (methodDesc.charAt(i) == 'L') {
                    int end = methodDesc.indexOf(';', i);
                    addInternalName(methodDesc.substring(i + 1, end), referencedTypes);
                    i = end + 1;
                } else {
                    i++; // primitive
                }
            }
        }
        // return type
        addDescriptor(methodDesc.substring(close + 1), referencedTypes);
    }

    /**
     * Adds an internal class name (slash-separated, possibly an array descriptor)
     * to the referenced types set.
     */
    private static void addInternalName(String internalName, Set<String> referencedTypes) {
        if (internalName == null || internalName.isEmpty()) {
            return;
        }
        // Handle array internal names like [[Ljava/lang/String;
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.isEmpty() || name.length() == 1) {
            return; // primitive
        }
        referencedTypes.add(toJavaName(name));
    }
}
