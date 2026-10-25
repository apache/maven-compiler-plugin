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
import java.util.Set;

/**
 * Multi-release override of {@link BytecodeAnalyzer} for JDK 24+.
 *
 * <p>This class lives in {@code META-INF/versions/24/} and is loaded automatically
 * by the JVM on JDK 24 or later, overriding the root stub. It delegates directly
 * to {@link ClassfileClassAnalyzer} using the standard {@code java.lang.classfile}
 * API — no reflection required.
 *
 * <p>On JDK &lt; 24, the root {@code BytecodeAnalyzer} is loaded instead and
 * throws {@link UnsupportedOperationException}, triggering a fallback to the
 * timestamp incremental strategy.
 */
public final class BytecodeAnalyzer {

    /**
     * Prefix used to distinguish module-info qualified names from regular class names.
     * A type name starting with this prefix is a module name, not a class name.
     */
    public static final String MODULE_PREFIX = "module:";

    /**
     * Analysis result for a single {@code .class} file.
     *
     * @param className           fully-qualified class name (dot-separated)
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
            Set<String> signatureTypes,
            Set<String> implementationTypes,
            Set<String> annotationTypes,
            String moduleName,
            boolean isModuleInfo,
            String sourceFileName) {}

    private static final ClassfileClassAnalyzer ANALYZER = new ClassfileClassAnalyzer();

    private BytecodeAnalyzer() {}

    /**
     * Analyzes the class file at {@code classFile}.
     *
     * @param classFile path to the {@code .class} file
     * @return analysis result
     * @throws IOException if reading the file fails
     */
    public static ClassAnalysis analyze(Path classFile) throws IOException {
        return ANALYZER.analyze(Files.readAllBytes(classFile));
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
            case 'L' -> {
                int semi = desc.indexOf(';');
                yield semi > 0 ? toJavaName(desc.substring(1, semi)) : desc;
            }
            case '[' -> descriptorToReadable(desc.substring(1)) + "[]";
            default -> desc;
        };
    }

    static String toJavaName(String internalName) {
        return internalName.replace('/', '.');
    }
}
