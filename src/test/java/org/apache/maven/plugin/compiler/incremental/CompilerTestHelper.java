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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
     * Compiles all {@code .java} files under {@code sourceDir} into {@code outputDir}.
     */
    static void compileAndAnalyze(Path sourceDir, Path outputDir) throws IOException {
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
        }
    }

    static void deleteSource(Path sourceDir, String packageName, String className) throws IOException {
        Path file = sourceDir.resolve(packageName.replace('.', '/') + "/" + className + ".java");
        Files.deleteIfExists(file);
    }
}
