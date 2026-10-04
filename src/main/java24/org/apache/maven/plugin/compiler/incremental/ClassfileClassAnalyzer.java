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
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleProvideInfo;
import java.lang.classfile.attribute.ModuleRequireInfo;
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
 * <p>Type references are classified into two sets:
 * <ul>
 *   <li><b>signatureTypes</b> — types from the public API surface: supertype, interfaces,
 *       field/method descriptors of non-private members, exception types, annotations.
 *       These are what downstream consumers structurally depend on.</li>
 *   <li><b>implementationTypes</b> — types referenced only in method body bytecode
 *       instructions (INVOKE*, field access, NEW, CHECKCAST, etc.), and descriptor
 *       types of private members. Changes to these do not cascade to the class's
 *       signature consumers.</li>
 * </ul>
 *
 * <p>{@code module-info.class} is handled specially: its ABI fingerprint is derived
 * from the {@code Module} attribute directives (requires, exports, opens, uses,
 * provides), and no implementation types are collected.
 *
 * @see BytecodeAnalyzer
 * @see ClassAnalyzer
 */
class ClassfileClassAnalyzer extends ClassAnalyzer {

    @Override
    public BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes) {
        ClassModel cm = ClassFile.of().parse(classBytes);

        // module-info.class has the MODULE access flag
        if (cm.flags().has(AccessFlag.MODULE)) {
            return analyzeModuleInfo(cm);
        }

        String className = BytecodeAnalyzer.toJavaName(cm.thisClass().asInternalName());
        Set<String> signatureTypes = new TreeSet<>();
        Set<String> implementationTypes = new TreeSet<>();
        Set<String> annotationTypes = new TreeSet<>();

        collectTypes(cm, signatureTypes, implementationTypes, annotationTypes);

        // Remove self-references and JDK types
        signatureTypes.remove(className);
        implementationTypes.remove(className);
        implementationTypes.removeAll(signatureTypes); // sig takes precedence
        signatureTypes.removeIf(ClassfileClassAnalyzer::isJdkType);
        implementationTypes.removeIf(ClassfileClassAnalyzer::isJdkType);
        annotationTypes.removeIf(ClassfileClassAnalyzer::isJdkType);

        String abiCanonical = buildCanonical(cm);
        String abiFingerprint = Sha256.hash(abiCanonical);

        // Read the SourceFile attribute for accurate source-file attribution
        String sourceFileName = cm.findAttribute(Attributes.sourceFile())
                .map(sf -> sf.sourceFile().stringValue())
                .orElse("");

        return new BytecodeAnalyzer.ClassAnalysis(
                className,
                abiFingerprint,
                abiCanonical,
                signatureTypes,
                implementationTypes,
                annotationTypes,
                /* moduleName= */ "",
                /* isModuleInfo= */ false,
                sourceFileName);
    }

    // --- module-info handling ---

    private static BytecodeAnalyzer.ClassAnalysis analyzeModuleInfo(ClassModel cm) {
        var moduleAttrOpt = cm.findAttribute(Attributes.module());
        if (moduleAttrOpt.isEmpty()) {
            return new BytecodeAnalyzer.ClassAnalysis(
                    "module-info", "", "", Set.of(), Set.of(), Set.of(), "", true, "module-info.java");
        }

        ModuleAttribute mod = moduleAttrOpt.get();
        String moduleName = mod.moduleName().name().stringValue();
        boolean isOpen = cm.flags().has(AccessFlag.OPEN);

        var requires = new TreeSet<String>();
        var exports = new TreeSet<String>();
        var opens = new TreeSet<String>();
        var uses = new TreeSet<String>();
        var provides = new TreeSet<String>();

        for (ModuleRequireInfo req : mod.requires()) {
            var sb = new StringBuilder("requires ");
            if (req.requiresFlags().contains(AccessFlag.TRANSITIVE)) sb.append("transitive ");
            if (req.requiresFlags().contains(AccessFlag.STATIC_PHASE)) sb.append("static ");
            sb.append(req.requires().name().stringValue());
            requires.add(sb.toString());
        }
        for (var exp : mod.exports()) {
            var sb = new StringBuilder("exports ")
                    .append(exp.exportedPackage().name().stringValue().replace('/', '.'));
            var tos = exp.exportsTo();
            if (!tos.isEmpty()) {
                sb.append(" to ")
                        .append(tos.stream()
                                .map(e -> e.name().stringValue())
                                .sorted()
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""));
            }
            exports.add(sb.toString());
        }
        for (var op : mod.opens()) {
            var sb = new StringBuilder("opens ")
                    .append(op.openedPackage().name().stringValue().replace('/', '.'));
            var tos = op.opensTo();
            if (!tos.isEmpty()) {
                sb.append(" to ")
                        .append(tos.stream()
                                .map(e -> e.name().stringValue())
                                .sorted()
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""));
            }
            opens.add(sb.toString());
        }
        for (var u : mod.uses()) {
            uses.add("uses " + BytecodeAnalyzer.toJavaName(u.asInternalName()));
        }
        for (ModuleProvideInfo p : mod.provides()) {
            var sb = new StringBuilder("provides ")
                    .append(BytecodeAnalyzer.toJavaName(p.provides().asInternalName()));
            var impls = p.providesWith();
            if (!impls.isEmpty()) {
                sb.append(" with ")
                        .append(impls.stream()
                                .map(i -> BytecodeAnalyzer.toJavaName(i.asInternalName()))
                                .sorted()
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""));
            }
            provides.add(sb.toString());
        }

        var canonical = new StringBuilder();
        if (isOpen) canonical.append("open ");
        canonical.append("module ").append(moduleName).append('\n');
        for (String r : requires) canonical.append("  ").append(r).append('\n');
        for (String e : exports) canonical.append("  ").append(e).append('\n');
        for (String o : opens) canonical.append("  ").append(o).append('\n');
        for (String u : uses) canonical.append("  ").append(u).append('\n');
        for (String p : provides) canonical.append("  ").append(p).append('\n');

        String abiCanonical = canonical.toString();
        String abiFingerprint = Sha256.hash(abiCanonical);

        // Signature deps for module-info: service types from uses/provides
        var sigTypes = new TreeSet<String>();
        for (var u : mod.uses()) {
            String name = BytecodeAnalyzer.toJavaName(u.asInternalName());
            if (!isJdkType(name)) sigTypes.add(name);
        }
        for (ModuleProvideInfo p : mod.provides()) {
            String svc = BytecodeAnalyzer.toJavaName(p.provides().asInternalName());
            if (!isJdkType(svc)) sigTypes.add(svc);
            for (var impl : p.providesWith()) {
                String implName = BytecodeAnalyzer.toJavaName(impl.asInternalName());
                if (!isJdkType(implName)) sigTypes.add(implName);
            }
        }

        // module-info qualified name uses the MODULE_PREFIX convention
        String qualifiedName = AbiIncrementalBuild.MODULE_PREFIX + moduleName;

        return new BytecodeAnalyzer.ClassAnalysis(
                qualifiedName,
                abiFingerprint,
                abiCanonical,
                sigTypes,
                Set.of(),
                Set.of(),
                moduleName,
                /* isModuleInfo= */ true,
                /* sourceFileName= */ "module-info.java");
    }

    // --- type reference collection ---

    private static void collectTypes(
            ClassModel cm, Set<String> signatureTypes, Set<String> implementationTypes, Set<String> annotationTypes) {

        // Superclass and interfaces are always signature-level
        cm.superclass().ifPresent(sup -> addRef(sup.asInternalName(), signatureTypes));
        for (var iface : cm.interfaces()) {
            addRef(iface.asInternalName(), signatureTypes);
        }

        // Class-level annotations → signature + annotation tracking
        collectAnnotations(
                cm.findAttribute(Attributes.runtimeVisibleAnnotations())
                        .map(a -> a.annotations())
                        .orElse(List.of()),
                signatureTypes,
                annotationTypes);
        collectAnnotations(
                cm.findAttribute(Attributes.runtimeInvisibleAnnotations())
                        .map(a -> a.annotations())
                        .orElse(List.of()),
                signatureTypes,
                annotationTypes);

        for (FieldModel field : cm.fields()) {
            boolean isPrivate = field.flags().has(AccessFlag.PRIVATE);
            Set<String> descTarget = isPrivate ? implementationTypes : signatureTypes;

            addDescriptor(field.fieldType().stringValue(), descTarget);

            // Field annotations go to the same target as the field descriptor
            collectAnnotations(
                    field.findAttribute(Attributes.runtimeVisibleAnnotations())
                            .map(a -> a.annotations())
                            .orElse(List.of()),
                    descTarget,
                    annotationTypes);
            collectAnnotations(
                    field.findAttribute(Attributes.runtimeInvisibleAnnotations())
                            .map(a -> a.annotations())
                            .orElse(List.of()),
                    descTarget,
                    annotationTypes);
        }

        for (MethodModel method : cm.methods()) {
            boolean isPrivate = method.flags().has(AccessFlag.PRIVATE);
            Set<String> sigTarget = isPrivate ? implementationTypes : signatureTypes;

            // Method descriptor types (params + return) and exceptions → sig if non-private
            addMethodDescriptor(method.methodType().stringValue(), sigTarget);
            method.findAttribute(Attributes.exceptions())
                    .ifPresent(ex -> ex.exceptions().forEach(e -> addRef(e.asInternalName(), sigTarget)));

            // Method annotations → same target as descriptor
            collectAnnotations(
                    method.findAttribute(Attributes.runtimeVisibleAnnotations())
                            .map(a -> a.annotations())
                            .orElse(List.of()),
                    sigTarget,
                    annotationTypes);
            collectAnnotations(
                    method.findAttribute(Attributes.runtimeInvisibleAnnotations())
                            .map(a -> a.annotations())
                            .orElse(List.of()),
                    sigTarget,
                    annotationTypes);

            // Method body instructions → always implementation-level
            method.code().ifPresent(code -> {
                for (var element : code) {
                    switch (element) {
                        case InvokeInstruction ii -> addRef(ii.owner().asInternalName(), implementationTypes);
                        case FieldInstruction fi -> addRef(fi.owner().asInternalName(), implementationTypes);
                        case TypeCheckInstruction tci -> addRef(tci.type().asInternalName(), implementationTypes);
                        case NewObjectInstruction noi -> addRef(noi.className().asInternalName(), implementationTypes);
                        case NewMultiArrayInstruction nma ->
                            addRef(nma.arrayType().asInternalName(), implementationTypes);
                        default -> {}
                    }
                }
            });
        }

        // Scan the constant pool for class references that are not captured by instructions.
        // This covers compile-time constants (static final primitives) whose values are inlined
        // by javac — the referencing class has no GETSTATIC instruction, but the resolved class
        // is still present in the constant pool.
        for (var entry : cm.constantPool()) {
            if (entry instanceof java.lang.classfile.constantpool.ClassEntry ce) {
                String internalName = ce.asInternalName();
                // Skip array type descriptors and the class itself
                if (!internalName.startsWith("[")
                        && !internalName.equals(cm.thisClass().asInternalName())) {
                    addRef(internalName, implementationTypes);
                }
            }
        }
    }

    private static void collectAnnotations(
            java.util.List<? extends java.lang.classfile.Annotation> annotations,
            Set<String> typeTarget,
            Set<String> annotationTarget) {
        for (var ann : annotations) {
            String descriptor = ann.className().stringValue();
            String name = descriptorToJavaName(descriptor);
            if (name != null) {
                typeTarget.add(name);
                annotationTarget.add(name);
            }
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
                String fieldSig = field.findAttribute(Attributes.signature())
                        .map(s -> s.signature().stringValue())
                        .orElse(null);
                fields.add(new FieldInfo(
                        access,
                        field.fieldName().stringValue(),
                        field.fieldType().stringValue(),
                        constantValue,
                        fieldSig));
            }
        }

        for (MethodModel method : cm.methods()) {
            int access = accessMask(method.flags().flags());
            String name = method.methodName().stringValue();
            if (!isPrivateOrSynthetic(access) && !"<clinit>".equals(name)) {
                String methodSig = method.findAttribute(Attributes.signature())
                        .map(s -> s.signature().stringValue())
                        .orElse(null);
                methods.add(new MethodInfo(access, name, method.methodType().stringValue(), methodSig));
            }
        }

        int classAccess = accessMask(cm.flags().flags());
        String className = BytecodeAnalyzer.toJavaName(cm.thisClass().asInternalName());
        String classSignature = cm.findAttribute(Attributes.signature())
                .map(s -> s.signature().stringValue())
                .orElse(null);
        String superName = cm.superclass()
                .map(sup -> BytecodeAnalyzer.toJavaName(sup.asInternalName()))
                .orElse(null);
        List<String> ifaceNames = cm.interfaces().stream()
                .map(iface -> BytecodeAnalyzer.toJavaName(iface.asInternalName()))
                .toList();

        return buildCanonicalForm(classAccess, className, classSignature, superName, ifaceNames, fields, methods);
    }

    // --- utilities ---

    private static boolean isJdkType(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.");
    }

    private static int accessMask(Set<AccessFlag> flags) {
        int mask = 0;
        for (AccessFlag flag : flags) {
            mask |= flag.mask();
        }
        return mask;
    }

    private static void addRef(String internalName, Set<String> types) {
        String resolved = resolveInternalName(internalName);
        if (resolved != null) {
            types.add(resolved);
        }
    }

    /** Converts a field type descriptor ({@code Lcom/Foo;}, {@code [Lcom/Foo;}) to a Java name, or null for primitives. */
    private static String descriptorToJavaName(String descriptor) {
        int i = 0;
        while (i < descriptor.length() && descriptor.charAt(i) == '[') {
            i++;
        }
        String core = descriptor.substring(i);
        if (core.startsWith("L") && core.endsWith(";")) {
            return BytecodeAnalyzer.toJavaName(core.substring(1, core.length() - 1));
        }
        return null; // primitive or array-of-primitive
    }

    private static void addDescriptor(String descriptor, Set<String> types) {
        String name = descriptorToJavaName(descriptor);
        if (name != null) {
            types.add(name);
        }
    }

    private static void addMethodDescriptor(String methodDesc, Set<String> types) {
        int close = methodDesc.indexOf(')');
        int i = 1;
        while (i < close) {
            while (i < close && methodDesc.charAt(i) == '[') {
                i++;
            }
            if (i < close) {
                if (methodDesc.charAt(i) == 'L') {
                    int end = methodDesc.indexOf(';', i);
                    types.add(BytecodeAnalyzer.toJavaName(methodDesc.substring(i + 1, end)));
                    i = end + 1;
                } else {
                    i++;
                }
            }
        }
        addDescriptor(methodDesc.substring(close + 1), types);
    }
}
