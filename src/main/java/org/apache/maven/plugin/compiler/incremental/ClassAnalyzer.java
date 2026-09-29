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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Abstract base for analyzing compiled {@code .class} files.
 *
 * <p>Implementations collect type references (API-specific) and extract member
 * metadata, then delegate canonical-form construction and fingerprinting to the
 * shared logic in this class.
 *
 * <p>Two built-in implementations are provided:
 * <ul>
 *   <li>{@link AsmClassAnalyzer} — uses the bundled ASM library; works on any
 *       supported JDK version (Java 17+).</li>
 *   <li>{@code ClassfileClassAnalyzer} — uses the standard
 *       {@code java.lang.classfile} API introduced in JDK 24; loaded
 *       reflectively at runtime when the JVM is JDK 24 or later.</li>
 * </ul>
 *
 * <p>Use {@link BytecodeAnalyzer#analyze(byte[])} which selects the best
 * available implementation automatically.
 *
 * @see BytecodeAnalyzer
 * @see AsmClassAnalyzer
 */
public abstract class ClassAnalyzer {

    // JVM spec access flag constants — shared by both implementations
    protected static final int ACC_PUBLIC = 0x0001;
    protected static final int ACC_PRIVATE = 0x0002;
    protected static final int ACC_PROTECTED = 0x0004;
    protected static final int ACC_STATIC = 0x0008;
    protected static final int ACC_FINAL = 0x0010;
    protected static final int ACC_INTERFACE = 0x0200;
    protected static final int ACC_ABSTRACT = 0x0400;
    protected static final int ACC_SYNTHETIC = 0x1000;
    protected static final int ACC_ENUM = 0x4000;

    protected static final Set<String> EXCLUDED_SUPERTYPES =
            Set.of("java.lang.Object", "java.lang.Enum", "java.lang.Record");

    /**
     * A non-private, non-synthetic field extracted from a class file.
     *
     * @param access        JVM access flags bitmask
     * @param name          field name
     * @param descriptor    JVM type descriptor
     * @param constantValue compile-time constant value, or {@code null}
     * @param signature     generic signature from the Signature attribute, or {@code null}
     */
    protected record FieldInfo(int access, String name, String descriptor, Object constantValue, String signature)
            implements Comparable<FieldInfo> {
        @Override
        public int compareTo(FieldInfo o) {
            return name.compareTo(o.name);
        }
    }

    /**
     * A non-private, non-synthetic method extracted from a class file.
     *
     * @param access     JVM access flags bitmask
     * @param name       method name
     * @param descriptor JVM method descriptor
     * @param signature  generic signature from the Signature attribute, or {@code null}
     */
    protected record MethodInfo(int access, String name, String descriptor, String signature)
            implements Comparable<MethodInfo> {
        @Override
        public int compareTo(MethodInfo o) {
            int c = name.compareTo(o.name);
            return c != 0 ? c : descriptor.compareTo(o.descriptor);
        }
    }

    /**
     * Analyzes the given class file bytes.
     *
     * @param classBytes raw {@code .class} file content
     * @return analysis result containing class name, ABI fingerprint, canonical
     *         form, and referenced type names
     */
    public abstract BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes);

    /**
     * Analyzes the class file at the given path.
     *
     * @param classFile path to the {@code .class} file
     * @return analysis result
     * @throws IOException if reading the file fails
     */
    public BytecodeAnalyzer.ClassAnalysis analyze(Path classFile) throws IOException {
        return analyze(Files.readAllBytes(classFile));
    }

    /**
     * Builds the ABI canonical form from extracted class metadata.
     * The format is deterministic and identical across both analyzer
     * implementations, ensuring consistent fingerprints.
     *
     * @param classAccess    JVM access flags for the class
     * @param className      fully-qualified class name (dot-separated)
     * @param classSignature generic signature of the class, or {@code null}
     * @param superName      fully-qualified superclass name, or {@code null}
     * @param interfaces     fully-qualified interface names (dot-separated)
     * @param fields         non-private, non-synthetic fields (will be sorted)
     * @param methods        non-private, non-synthetic methods (will be sorted)
     * @return the canonical ABI string
     */
    protected static String buildCanonicalForm(
            int classAccess,
            String className,
            String classSignature,
            String superName,
            List<String> interfaces,
            List<FieldInfo> fields,
            List<MethodInfo> methods) {

        Collections.sort(fields);
        Collections.sort(methods);

        var sb = new StringBuilder();

        appendAccessFlags(sb, classAccess);
        if ((classAccess & ACC_INTERFACE) != 0) {
            sb.append("interface ");
        } else if ((classAccess & ACC_ENUM) != 0) {
            sb.append("enum ");
        } else {
            sb.append("class ");
        }
        sb.append(className);
        if (classSignature != null) {
            sb.append(" <sig: ").append(classSignature).append('>');
        }
        sb.append('\n');

        if (superName != null && !EXCLUDED_SUPERTYPES.contains(superName)) {
            sb.append("  extends ").append(superName).append('\n');
        }

        for (String iface : interfaces) {
            sb.append("  implements ").append(iface).append('\n');
        }

        for (var f : fields) {
            sb.append("  ");
            appendAccessFlags(sb, f.access);
            sb.append(BytecodeAnalyzer.descriptorToReadable(f.descriptor)).append(' ');
            sb.append(f.name);
            if (f.constantValue != null) {
                sb.append(" = ").append(f.constantValue);
            }
            if (f.signature != null) {
                sb.append(" <sig: ").append(f.signature).append('>');
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
            if (m.signature != null) {
                sb.append(" <sig: ").append(m.signature).append('>');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Appends the human-readable access flag names (public, protected, abstract,
     * static, final) to the builder, in the canonical order.
     */
    protected static void appendAccessFlags(StringBuilder sb, int access) {
        if ((access & ACC_PUBLIC) != 0) {
            sb.append("public ");
        }
        if ((access & ACC_PROTECTED) != 0) {
            sb.append("protected ");
        }
        if ((access & ACC_ABSTRACT) != 0) {
            sb.append("abstract ");
        }
        if ((access & ACC_STATIC) != 0) {
            sb.append("static ");
        }
        if ((access & ACC_FINAL) != 0) {
            sb.append("final ");
        }
    }

    /**
     * Resolves a JVM internal name (possibly an array descriptor) to a
     * fully-qualified Java name. Returns {@code null} for primitives and
     * empty/null inputs.
     *
     * @param internalName slash-separated internal name, possibly with array
     *                     prefix ({@code [}) or object wrapper ({@code L...;})
     * @return dot-separated Java name, or {@code null} if primitive/invalid
     */
    protected static String resolveInternalName(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            return null;
        }
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.isEmpty() || name.length() == 1) {
            return null;
        }
        return BytecodeAnalyzer.toJavaName(name);
    }

    /**
     * Returns {@code true} if the given access flags indicate a private or
     * synthetic member that should be excluded from the ABI canonical form.
     */
    protected static boolean isPrivateOrSynthetic(int access) {
        return (access & ACC_PRIVATE) != 0 || (access & ACC_SYNTHETIC) != 0;
    }
}
