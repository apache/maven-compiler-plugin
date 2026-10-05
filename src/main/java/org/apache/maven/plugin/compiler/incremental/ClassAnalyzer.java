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

import java.util.HashSet;
import java.util.Set;

/**
 * Abstract base for analyzing compiled {@code .class} files.
 *
 * <p>The single built-in implementation is:
 * <ul>
 *   <li>{@code ClassfileClassAnalyzer} — uses the standard
 *       {@code java.lang.classfile} API introduced in JDK 24; loaded
 *       reflectively at runtime. Requires JDK 24 or later.</li>
 * </ul>
 *
 * <p>Use {@link BytecodeAnalyzer#analyze(byte[])} which selects the best
 * available implementation automatically.
 *
 * @see BytecodeAnalyzer
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
     * Analyzes the given class file bytes.
     *
     * @param classBytes raw {@code .class} file content
     * @return analysis result containing class name, ABI fingerprint, canonical
     *         form, and referenced type names
     */
    public abstract BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes);

    /**
     * Extracts all class-level dependency references from the given class file bytes.
     *
     * <p>Returns the set of all types referenced in the classfile's constant pool,
     * without distinguishing public API from method-body references. Array types
     * and JDK built-in types are excluded.
     *
     * <p>The default implementation delegates to {@link #analyze(byte[])} and
     * computes the union of {@code signatureTypes} and {@code implementationTypes}.
     * Subclasses may override for a more efficient constant-pool-only scan.
     *
     * @param classBytes raw {@code .class} file content
     * @return set of fully qualified type names referenced in the classfile
     */
    public Set<String> analyzeGraph(byte[] classBytes) {
        var analysis = analyze(classBytes);
        var deps = new HashSet<>(analysis.signatureTypes());
        deps.addAll(analysis.implementationTypes());
        return Set.copyOf(deps);
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
