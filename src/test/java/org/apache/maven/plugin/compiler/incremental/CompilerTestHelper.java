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

import javax.tools.JavaCompiler;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Shared helper for incremental compilation tests.
 */
class CompilerTestHelper {

    static void writeSource(Path sourceDir, String packageName, String className, String source) throws IOException {
        Path packageDir = sourceDir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve(className + ".java"), source);
    }

    /**
     * Compiles all {@code .java} files under {@code sourceDir} into {@code outputDir}
     * and returns the output directory.
     */
    static Map<String, SourceFileAnalysis> compileAndAnalyze(Path sourceDir, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            sourceFiles =
                    walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var fm = compiler.getStandardFileManager(null, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            var units = fm.getJavaFileObjectsFromPaths(sourceFiles);
            var task = compiler.getTask(null, fm, null, null, null, units);

            if (!task.call()) {
                throw new RuntimeException("Compilation failed");
            }

            // Build a minimal SourceFileAnalysis map from bytecode — walk the output directory
            // once and key each class file by its className (not per-source-file to avoid O(n²)).
            Map<String, SourceFileAnalysis> results = new LinkedHashMap<>();
            try (Stream<Path> walk = Files.walk(outputDir)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(cf -> {
                    try {
                        var analysis = BytecodeAnalyzer.analyze(cf);
                        // Attribute class to source file by matching the simple class name prefix
                        String className = analysis.className();
                        String simpleName = className.contains(".")
                                ? className.substring(className.lastIndexOf('.') + 1)
                                : className;
                        // Strip inner-class suffix for source attribution
                        String outerName = simpleName.contains("$")
                                ? simpleName.substring(0, simpleName.indexOf('$'))
                                : simpleName;
                        String sourceFile = sourceFiles.stream()
                                .filter(sf -> sf.getFileName().toString().equals(outerName + ".java"))
                                .map(Path::toString)
                                .findFirst()
                                .orElse(cf.toString());
                        var sfa = new SourceFileAnalysis(
                                className,
                                sourceFile,
                                unionDeps(analysis.signatureTypes(), analysis.implementationTypes()),
                                analysis.annotationTypes(),
                                analysis.moduleName());
                        results.put(className, sfa);
                    } catch (IOException e) {
                        // best effort — corrupted class files are silently skipped
                    }
                });
            } catch (IOException e) {
                // best effort
            }
            return results;
        }
    }

    static void deleteSource(Path sourceDir, String packageName, String className) throws IOException {
        Path file = sourceDir.resolve(packageName.replace('.', '/') + "/" + className + ".java");
        Files.deleteIfExists(file);
    }

    /**
     * Compiles {@code files} into {@code outputDir}, with {@code extraClasspath} entries
     * added to the classpath (used for cross-module tests).
     */
    static void compileFiles(Path outputDir, Set<Path> files, Path... extraClasspath) throws IOException {
        Files.createDirectories(outputDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var fm = compiler.getStandardFileManager(null, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            var cp = new ArrayList<File>();
            cp.add(outputDir.toFile());
            for (Path p : extraClasspath) {
                cp.add(p.toFile());
            }
            fm.setLocation(StandardLocation.CLASS_PATH, cp);
            fm.setLocation(StandardLocation.SOURCE_PATH, List.of());
            var units = fm.getJavaFileObjectsFromPaths(files);
            var task = compiler.getTask(null, fm, null, null, null, units);
            if (!task.call()) {
                throw new RuntimeException("Compilation failed");
            }
        }
    }

    private static Set<String> unionDeps(Set<String> a, Set<String> b) {
        var result = new HashSet<String>(a);
        result.addAll(b);
        return Set.copyOf(result);
    }
}
