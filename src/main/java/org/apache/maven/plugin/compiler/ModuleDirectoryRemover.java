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
package org.apache.maven.plugin.compiler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper class for removing the directory having a module name.
 * This hack is used when the {@code --module-source-path} compiler option is used
 * but we still want to reproduce the directory layout of Maven 3.
 * This hack is not used when the new {@code <source>} elements is used instead.
 *
 * <p>The code in this class is useful only when {@link AbstractCompilerMojo#SUPPORT_LEGACY} is true.
 * This class can be fully deleted if a future version permanently set above-cited flag to false.</p>
 */
final class ModuleDirectoryRemover implements Closeable {
    /**
     * The output directory expected by Maven 3.
     * Example: {@code target/classes/META-INF/versions/21}.
     */
    private final Path mavenTarget;

    /**
     * The output directory where {@code javac} will write the classes.
     * Example: {@code target/classes/META-INF/versions/21/org.foo.bar}.
     */
    private final Path javacTarget;

    /**
     * A temporary directory used as an intermediate step for avoiding name clash.
     * Example: {@code target/classes/META-INF/versions/org.foo.bar}.
     */
    private final Path interTarget;

    /**
     * Temporarily renames the given output directory for matching the layout of {@code javac} output.
     *
     * @param  outputDirectory  the output directory (must exist)
     * @param  moduleName       the name of the module
     * @throws IOException if an error occurred while renaming the output directory
     */
    private ModuleDirectoryRemover(Path outputDirectory, String moduleName) throws IOException {
        mavenTarget = outputDirectory;
        interTarget = Files.move(outputDirectory, outputDirectory.resolveSibling(moduleName));
        javacTarget = Files.createDirectory(outputDirectory).resolve(moduleName);
        Files.move(interTarget, javacTarget);
    }

    /**
     * Temporarily renames the given output directory for matching the layout of {@code javac} output.
     *
     * @param  outputDirectory  the output directory (must exist)
     * @param  moduleName       the name of the module, or {@code null} if none
     * @return a handler for restoring the directory to its original name, or {@code null} if there is no renaming
     * @throws IOException if an error occurred while renaming the output directory
     */
    static ModuleDirectoryRemover create(Path outputDirectory, String moduleName) throws IOException {
        return (moduleName != null) ? new ModuleDirectoryRemover(outputDirectory, moduleName) : null;
    }

    /**
     * Restores the output directory to its original name.
     * Note: contrarily to {@link Closeable} contract, this method is not idempotent:
     * it cannot be executed twice. However, this is okay for the usage in this package.
     *
     * @throws IOException if an error occurred while renaming the output directory
     */
    @Override
    public void close() throws IOException {
        Files.move(javacTarget, interTarget);
        Files.delete(mavenTarget);
        Files.move(interTarget, mavenTarget);
    }
}
