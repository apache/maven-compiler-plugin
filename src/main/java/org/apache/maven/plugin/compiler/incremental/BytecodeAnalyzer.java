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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

/**
 * Facade for analyzing compiled {@code .class} files to extract type references
 * and compute bytecode-level ABI fingerprints.
 *
 * <p>This class is part of a multi-release JAR. The root implementation (loaded on
 * JDK &lt; 24) always returns {@code false} from {@link #isAvailable()} — ABI
 * fingerprinting is not supported on JDK 17–23. The {@code META-INF/versions/24/}
 * override (loaded automatically by the JVM on JDK 24+) provides the real
 * implementation backed by the standard {@code java.lang.classfile} API.
 *
 * <p>Callers must check {@link #isAvailable()} before calling {@link #analyze}.
 * When unavailable, the ABI incremental strategy falls back to the timestamp strategy.
 *
 * <p>The {@link ClassAnalysis} record carries the class name, ABI fingerprint,
 * human-readable canonical form, and two classified sets of type references:
 * {@link ClassAnalysis#signatureTypes()} (API surface) and
 * {@link ClassAnalysis#implementationTypes()} (method body instructions only).
 */
public final class BytecodeAnalyzer {

    /**
     * Analysis result for a single {@code .class} file.
     *
     * @param className           fully-qualified class name (dot-separated)
     * @param abiFingerprint      16-character hex SHA-256 prefix of the ABI canonical form
     * @param abiCanonical        human-readable representation of the public API surface
     * @param signatureTypes      types appearing in public API surface (method/field descriptors,
     *                            supertype, interfaces, exception types, annotation types)
     * @param implementationTypes types appearing only in method body bytecode instructions
     *                            (INVOKEVIRTUAL, NEW, CHECKCAST, field owners, etc.)
     * @param annotationTypes     fully-qualified names of annotation types present on the class
     *                            or its members, used for annotation processor cascade decisions
     * @param moduleName          Java module name, or empty string if unnamed or non-modular
     * @param isModuleInfo        {@code true} if this represents {@code module-info.class}
     * @param sourceFileName      simple source file name from the {@code SourceFile} class file
     *                            attribute (e.g. {@code "Foo.java"}); empty string if absent
     */
    public record ClassAnalysis(
            String className,
            String abiFingerprint,
            String abiCanonical,
            Set<String> signatureTypes,
            Set<String> implementationTypes,
            Set<String> annotationTypes,
            String moduleName,
            boolean isModuleInfo,
            String sourceFileName) {}

    private BytecodeAnalyzer() {}

    /**
     * Prefix used to distinguish module-info entries from regular type entries
     * in the qualified names returned by {@link ClassAnalysis#className()}.
     */
    public static final String MODULE_PREFIX = "module:";

    /**
     * Returns {@code true} if ABI fingerprinting is available on the running JVM.
     *
     * <p>This method returns {@code false} in the root JAR (JDK &lt; 24). The
     * {@code META-INF/versions/24/} override returns {@code true}.
     *
     * <p>When this returns {@code false}, callers should fall back to the timestamp
     * incremental strategy and log a warning to the user.
     *
     * @return {@code true} if {@link #analyze} can be called safely
     */
    public static boolean isAvailable() {
        return false;
    }

    /**
     * Analyzes the class file at {@code classFile}.
     *
     * <p>Only call this method after confirming {@link #isAvailable()} returns {@code true}.
     *
     * @param classFile path to the {@code .class} file
     * @return analysis result
     * @throws IOException if reading the file fails
     * @throws UnsupportedOperationException if called on JDK &lt; 24
     */
    public static ClassAnalysis analyze(Path classFile) throws IOException {
        throw new UnsupportedOperationException("ABI fingerprinting requires JDK 24 or later (running JDK "
                + Runtime.version().feature()
                + "). Check BytecodeAnalyzer.isAvailable() before calling analyze().");
    }

    /**
     * Analyzes the given class file bytes.
     *
     * <p>Only call this method after confirming {@link #isAvailable()} returns {@code true}.
     *
     * @param classBytes raw {@code .class} file content
     * @return analysis result
     * @throws UnsupportedOperationException if called on JDK &lt; 24
     */
    public static ClassAnalysis analyze(byte[] classBytes) {
        throw new UnsupportedOperationException("ABI fingerprinting requires JDK 24 or later (running JDK "
                + Runtime.version().feature()
                + "). Check BytecodeAnalyzer.isAvailable() before calling analyze().");
    }

    // --- package-private utilities shared by the analyzer implementations and tests ---

    static String descriptorToReadable(String desc) {
        if (desc.isEmpty()) {
            return desc;
        }
        return switch (desc.charAt(0)) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> toJavaName(desc.substring(1, desc.indexOf(';')));
            case '[' -> descriptorToReadable(desc.substring(1)) + "[]";
            default -> desc;
        };
    }

    static String parseParams(String methodDesc) {
        int close = methodDesc.indexOf(')');
        String params = methodDesc.substring(1, close);
        var result = new ArrayList<String>();
        int i = 0;
        while (i < params.length()) {
            int start = i;
            while (i < params.length() && params.charAt(i) == '[') {
                i++;
            }
            if (i < params.length()) {
                if (params.charAt(i) == 'L') {
                    i = params.indexOf(';', i) + 1;
                } else {
                    i++;
                }
            }
            result.add(descriptorToReadable(params.substring(start, i)));
        }
        return String.join(", ", result);
    }

    static String parseReturn(String methodDesc) {
        return descriptorToReadable(methodDesc.substring(methodDesc.indexOf(')') + 1));
    }

    static String toJavaName(String internalName) {
        return internalName.replace('/', '.');
    }
}
