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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.api.plugin.MojoException;

/**
 * Various helper methods to support incremental builds
 */
public class IncrementalBuildHelper {
    /**
     * the root directory to store status information about Maven executions in.
     */
    private static final String MAVEN_STATUS_ROOT = "maven-status";

    public static final String CREATED_FILES_LST_FILENAME = "createdFiles.lst";
    private static final String INPUT_FILES_LST_FILENAME = "inputFiles.lst";

    /**
     * Needed for storing the status for the incremental build support.
     */
    private final String mojoStatusPath;

    private final Set<Path> sources;

    private final Path directory;

    private final Path outputDirectory;

    /**
     * Once the {@link #beforeRebuildExecution()} got
     * called, this will contain the list of files in the build directory.
     */
    private List<Path> filesBeforeAction = Collections.emptyList();

    public IncrementalBuildHelper(String mojoStatusPath, Set<Path> sources, Path directory, Path outputDirectory) {
        if (mojoStatusPath == null) {
            throw new IllegalArgumentException("MojoExecution must not be null!");
        }

        this.mojoStatusPath = mojoStatusPath;
        this.sources = sources;
        this.directory = directory;
        this.outputDirectory = outputDirectory;
    }

    /**
     * We use a specific status directory for each Mojo execution to store state
     * which is needed during the next build invocation run.
     * @return the directory for storing status information of the current Mojo execution.
     */
    public Path getMojoStatusDirectory() throws MojoException {
        // X TODO the executionId contains -cli and -mojoname
        // X we should remove those postfixes as it should not make
        // X any difference whether being run on the cli or via build
        Path mojoStatusDir = directory.resolve(mojoStatusPath);

        try {
            Files.createDirectories(mojoStatusDir);
        } catch (IOException e) {
            throw new MojoException("Unable to create directory: " + mojoStatusDir, e);
        }

        return mojoStatusDir;
    }

    /**
     * Detect whether the list of detected files has changed since the last build.
     * We simply load the list of files for the previous build from a status file
     * and compare it with the new list. Afterwards we store the new list in the status file.
     *
     * @return <code>true</code> if the set of inputFiles got changed since the last build.
     */
    public boolean inputFileTreeChanged(List<String> added, List<String> removed) {
        Path mojoConfigBase = getMojoStatusDirectory();
        Path mojoConfigFile = mojoConfigBase.resolve(INPUT_FILES_LST_FILENAME);

        List<String> oldInputFiles = Collections.emptyList();

        if (Files.exists(mojoConfigFile)) {
            try {
                oldInputFiles = Files.readAllLines(mojoConfigFile);
            } catch (IOException e) {
                throw new MojoException("Error reading old mojo status " + mojoConfigFile, e);
            }
        }

        List<String> newFiles =
                sources.stream().map(Path::toAbsolutePath).map(Path::toString).collect(Collectors.toList());

        List<String> previousFiles = oldInputFiles;
        newFiles.stream().filter(s -> !previousFiles.contains(s)).forEach(added::add);
        previousFiles.stream().filter(s -> !newFiles.contains(s)).forEach(removed::add);
        try {
            Files.write(mojoConfigFile, added);
        } catch (IOException e) {
            throw new MojoException("Error while storing the mojo status", e);
        }

        return added.size() + removed.size() > 0;
    }

    /**
     * <p>
     * This method shall get invoked before the actual Mojo task gets triggered, e.g. the actual compile in
     * maven-compiler-plugin.
     * </p>
     * <p>
     * <b>Attention:</b> This method shall only get invoked if the plugin re-creates <b>all</b> the output.
     * </p>
     * <p>
     * It first picks up the list of files created in the previous build and delete them. This step is necessary to
     * prevent left-overs. After that we take a 'directory snapshot' (list of all files which exist in the
     * outputDirectory after the clean).
     * </p>
     * <p>
     * After the actual Mojo task got executed you should invoke the method
     * {@link #afterRebuildExecution()} to collect the
     * list of files which got changed by this task.
     * </p>
     */
    public void beforeRebuildExecution() {
        Path mojoConfigBase = getMojoStatusDirectory();
        Path mojoConfigFile = mojoConfigBase.resolve(CREATED_FILES_LST_FILENAME);

        try {
            if (Files.exists(mojoConfigFile)) {
                for (String oldFileName : Files.readAllLines(mojoConfigFile)) {
                    Path oldFile = outputDirectory.resolve(oldFileName);
                    Files.deleteIfExists(oldFile);
                }
            }

            // we remember all files which currently exist in the output directory
            if (Files.exists(outputDirectory)) {
                try (Stream<Path> walk = Files.walk(outputDirectory)) {
                    filesBeforeAction = walk.filter(Files::isRegularFile).collect(Collectors.toList());
                }
            }
        } catch (IOException e) {
            throw new MojoException("Error reading old mojo status", e);
        }
    }

    /**
     * <p>This method collects and stores all information about files changed since the
     * call to {@link #beforeRebuildExecution()}.</p>
     *
     * <p><b>Attention:</b> This method shall only get invoked if the plugin re-creates <b>all</b> the output.</p>
     */
    public void afterRebuildExecution() {
        Path mojoConfigBase = getMojoStatusDirectory();
        Path mojoConfigFile = mojoConfigBase.resolve(CREATED_FILES_LST_FILENAME);

        try {
            try (Stream<Path> walk = Files.walk(outputDirectory)) {
                List<String> added = walk.filter(Files::isRegularFile)
                        .filter(p -> !filesBeforeAction.contains(p))
                        .map(Path::toString)
                        .collect(Collectors.toList());

                Files.write(mojoConfigFile, added);
            }
        } catch (IOException e) {
            throw new MojoException("Error while storing the mojo status", e);
        }

        // in case of clean compile the file is not created so next compile won't see it
        // we mus create it here
        mojoConfigFile = mojoConfigBase.resolve(INPUT_FILES_LST_FILENAME);
        if (!Files.exists(mojoConfigFile)) {
            try {
                Files.write(mojoConfigFile, sources.stream().map(Path::toString).collect(Collectors.toList()));
            } catch (IOException e) {
                throw new MojoException("Error while storing the mojo status", e);
            }
        }
    }
}
