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
import java.util.logging.Logger;

/**
 * Facade for analyzing compiled {@code .class} files to extract type references
 * and compute bytecode-level ABI fingerprints.
 *
 * <p>When the JVM is JDK 24 or later, the standard {@code java.lang.classfile}
 * API ({@link ClassfileClassAnalyzer}) is used automatically. On earlier JVMs
 * the bundled ASM library ({@link AsmClassAnalyzer}) is used as a fallback.
 *
 * <p>The {@link ClassAnalysis} record carries the class name, ABI fingerprint
 * (a 16-character SHA-256 prefix), the human-readable canonical form that the
 * fingerprint is derived from, and the set of fully-qualified type names
 * referenced by the class.
 *
 * @see AsmClassAnalyzer
 * @see ClassfileClassAnalyzer
 * @see ClassAnalyzer
 */
public final class BytecodeAnalyzer {

    private static final Logger LOGGER = Logger.getLogger(BytecodeAnalyzer.class.getName());

    /**
     * Analysis result for a single {@code .class} file.
     *
     * @param className       fully-qualified class name (dot-separated)
     * @param abiFingerprint  16-character hex SHA-256 prefix of the ABI canonical form
     * @param abiCanonical    human-readable representation of the public API surface
     * @param referencedTypes fully-qualified names of all types referenced by this class
     */
    public record ClassAnalysis(
            String className, String abiFingerprint, String abiCanonical, Set<String> referencedTypes) {}

    /** The {@link ClassAnalyzer} implementation chosen at class-load time. */
    private static final ClassAnalyzer ANALYZER = selectAnalyzer();

    private BytecodeAnalyzer() {}

    /**
     * Analyzes the class file at {@code classFile}.
     *
     * @param classFile path to the {@code .class} file
     * @return analysis result
     * @throws IOException if reading the file fails
     */
    public static ClassAnalysis analyze(Path classFile) throws IOException {
        return ANALYZER.analyze(classFile);
    }

    /**
     * Analyzes the given class file bytes.
     *
     * @param classBytes raw {@code .class} file content
     * @return analysis result
     */
    public static ClassAnalysis analyze(byte[] classBytes) {
        return ANALYZER.analyze(classBytes);
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

    // --- implementation selection ---

    private static ClassAnalyzer selectAnalyzer() {
        if (Runtime.version().feature() >= 24) {
            try {
                Class<?> cls = Class.forName(
                        "org.apache.maven.plugin.compiler.incremental.ClassfileClassAnalyzer",
                        true,
                        BytecodeAnalyzer.class.getClassLoader());
                var ctor = cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                return (ClassAnalyzer) ctor.newInstance();
            } catch (Exception | LinkageError e) {
                LOGGER.fine(() -> "java.lang.classfile analyzer unavailable (" + e + "), falling back to ASM");
            }
        }
        return new AsmClassAnalyzer();
    }
}
