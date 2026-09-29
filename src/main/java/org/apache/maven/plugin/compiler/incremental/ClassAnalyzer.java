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

/**
 * Strategy interface for analyzing compiled {@code .class} files.
 *
 * <p>Implementations compute the class name, ABI fingerprint, ABI canonical form,
 * and the set of type names referenced by a class file.
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
public interface ClassAnalyzer {

    /**
     * Analyzes the given class file bytes.
     *
     * @param classBytes raw {@code .class} file content
     * @return analysis result containing class name, ABI fingerprint, canonical
     *         form, and referenced type names
     */
    BytecodeAnalyzer.ClassAnalysis analyze(byte[] classBytes);

    /**
     * Analyzes the class file at the given path.
     *
     * @param classFile path to the {@code .class} file
     * @return analysis result
     * @throws IOException if reading the file fails
     */
    default BytecodeAnalyzer.ClassAnalysis analyze(Path classFile) throws IOException {
        return analyze(Files.readAllBytes(classFile));
    }
}
