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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.apache.maven.plugin.compiler.SourceDirectory.CLASS_FILE_SUFFIX;
import static org.apache.maven.plugin.compiler.SourceDirectory.JAVA_FILE_SUFFIX;
import static org.apache.maven.plugin.compiler.SourceDirectory.MODULE_INFO;

/**
 * Helper class for the case where a {@code module-info.java} file defined in the tests
 * overwrites the file defined in the main classes. It should be a last-resort practice only,
 * when options such as {@code --add-reads} or {@code --add-exports} are not sufficient.
 *
 * <p>The code in this class is useful only when {@link AbstractCompilerMojo#SUPPORT_LEGACY} is true.
 * This class can be fully deleted if a future version permanently set above-cited flag to false.</p>
 */
final class ModuleInfoOverwrite implements Runnable {
    /**
     * Path to the original {@code module-info.java} file. It will need to be temporarily renamed
     * because otherwise the Java compiler seems to unconditionally compiles it, even if we do not
     * specify this file in the list of sources to compile.
     */
    private final Path testSourceFile;

    /**
     * Path to the {@code module-info.java.bak} file.
     */
    private final Path savedSourceFile;

    /**
     * Path to the main {@code module-info.class} file to temporarily hide.
     * This file will be temporarily renamed to {@link #moduleInfoBackup}
     * before to compile the tests.
     */
    private final Path moduleInfoToHide;

    /**
     * Path to the renamed main {@code module-info.class} file. This file
     * needs to be renamed as {@link #moduleInfoToHide} after compilation.
     */
    private final Path moduleInfoBackup;

    /**
     * The {@code module-info.class} to use as a replacement for the one which has been renamed.
     */
    private final Path moduleInfoReplacement;

    /**
     * The shutdown hook invoked if the user interrupts the compilation, for example with [Control-C].
     */
    private Thread shutdownHook;

    /**
     * Creates a new instance.
     */
    private ModuleInfoOverwrite(Path source, Path main, Path test) {
        testSourceFile = source;
        savedSourceFile = source.resolveSibling(MODULE_INFO + JAVA_FILE_SUFFIX + ".bak");
        moduleInfoToHide = main;
        moduleInfoBackup = main.resolveSibling(MODULE_INFO + CLASS_FILE_SUFFIX + ".bak");
        moduleInfoReplacement = test;
    }

    /**
     * Returns an instance for the given main output directory, or {@code null} if not needed.
     * This method should be invoked only if a {@code module-info.java} file exists and may
     * overwrite a file defined in the main classes.
     */
    static ModuleInfoOverwrite create(Path source, Path mainOutputDirectory, Path testOutputDirectory)
            throws IOException {
        Path main = mainOutputDirectory.resolve(MODULE_INFO + CLASS_FILE_SUFFIX);
        if (Files.isRegularFile(main)) {
            Path test = testOutputDirectory.resolve(MODULE_INFO + CLASS_FILE_SUFFIX);
            if (Files.isRegularFile(test)) {
                var mo = new ModuleInfoOverwrite(source, main, test);
                mo.substitute();
                return mo;
            }
        }
        return null;
    }

    /**
     * Replaces the main {@code module-info.class} file by the test one.
     * The original file is saved in the {@code module-info.class.bak} file.
     * Then the test {@code module-info.class} is moved to the main directory.
     * Note that it needs to be moved, not copied or linked, because we need
     * to temporarily remove {@code module-info.class} from the test directory
     * (otherwise {@code javac} does not seem to consider that we are patching a module).
     *
     * @throws IOException if an error occurred while renaming the file.
     */
    private void substitute() throws IOException {
        Files.move(testSourceFile, savedSourceFile);
        Files.move(moduleInfoToHide, moduleInfoBackup);
        Files.move(moduleInfoReplacement, moduleInfoToHide);
        if (shutdownHook == null) { // Paranoiac check in case this method is invoked twice (should not happen).
            shutdownHook = new Thread(this);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    /**
     * Restores the {@code module-info} file.
     *
     * @throws IOException if an error occurred while renaming the file.
     */
    void restore() throws IOException {
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        Files.move(savedSourceFile, testSourceFile); // File to restore in priority.
        Files.move(moduleInfoToHide, moduleInfoReplacement);
        Files.move(moduleInfoBackup, moduleInfoToHide);
    }

    /**
     * Invoked during JVM shutdown if user interrupted the compilation, for example with [Control-C].
     */
    @Override
    @SuppressWarnings("CallToPrintStackTrace")
    public void run() {
        shutdownHook = null;
        try {
            restore();
        } catch (IOException e) {
            // We cannot do much better because the loggers are shutting down.
            e.printStackTrace();
        }
    }
}
