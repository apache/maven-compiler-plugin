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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * Bookkeeping for the rebuild-everything flavour of incremental compilation: remembers which files the previous
 * execution created so they can be deleted before the next full rebuild, and which sources it saw so that added or
 * removed sources can be detected.
 * <p>
 * The state lives in {@code target/maven-status/<plugin>/<goal>/<executionId>/} as plain line-oriented files. The
 * layout and file formats are those of {@code org.apache.maven.shared:maven-shared-incremental:1.1}, from which this
 * class was inlined, so state written by earlier plugin versions keeps being understood.
 */
class IncrementalBuildHelper {
    /**
     * the root directory to store status information about Maven executions in.
     */
    private static final String MAVEN_STATUS_ROOT = "maven-status";

    static final String CREATED_FILES_LST_FILENAME = "createdFiles.lst";
    private static final String INPUT_FILES_LST_FILENAME = "inputFiles.lst";

    private static final String[] EMPTY_ARRAY = new String[0];

    /**
     * Needed for storing the status for the incremental build support.
     */
    private final MojoExecution mojoExecution;

    /**
     * Needed for storing the status for the incremental build support.
     */
    private final MavenProject mavenProject;

    /**
     * Once {@link #beforeRebuildExecution(IncrementalBuildHelperRequest)} got called, this will contain the list of
     * files in the build directory.
     */
    private Set<String> filesBeforeAction = new LinkedHashSet<>();

    IncrementalBuildHelper(MojoExecution mojoExecution, MavenSession mavenSession) {
        this(mojoExecution, getMavenProject(mavenSession));
    }

    IncrementalBuildHelper(MojoExecution mojoExecution, MavenProject mavenProject) {
        if (mavenProject == null) {
            throw new IllegalArgumentException("MavenProject must not be null!");
        }
        if (mojoExecution == null) {
            throw new IllegalArgumentException("MojoExecution must not be null!");
        }

        this.mavenProject = mavenProject;
        this.mojoExecution = mojoExecution;
    }

    /**
     * small helper method to allow for the nullcheck in the ct invocation
     */
    private static MavenProject getMavenProject(MavenSession mavenSession) {
        if (mavenSession == null) {
            throw new IllegalArgumentException("MavenSession must not be null!");
        }

        return mavenSession.getCurrentProject();
    }

    /**
     * We use a specific status directory for each Mojo execution to store state which is needed during the next build
     * invocation run.
     *
     * @return the directory for storing status information of the current Mojo execution.
     */
    public File getMojoStatusDirectory() throws MojoExecutionException {
        if (mojoExecution == null) {
            throw new MojoExecutionException("MojoExecution could not get resolved");
        }

        File buildOutputDirectory = new File(mavenProject.getBuild().getDirectory());

        // X TODO the executionId contains -cli and -mojoname
        // X we should remove those postfixes as it should not make
        // X any difference whether being run on the cli or via build
        String mojoStatusPath = MAVEN_STATUS_ROOT
                + File.separator
                + mojoExecution.getMojoDescriptor().getPluginDescriptor().getArtifactId()
                + File.separator
                + mojoExecution.getMojoDescriptor().getGoal()
                + File.separator
                + mojoExecution.getExecutionId();

        File mojoStatusDir = new File(buildOutputDirectory, mojoStatusPath);

        if (!mojoStatusDir.exists()) {
            mojoStatusDir.mkdirs();
        }

        return mojoStatusDir;
    }

    /**
     * <p>This method shall get invoked before the actual Mojo task gets triggered, e.g. the actual compile in
     * maven-compiler-plugin.</p>
     *
     * <p><b>Attention:</b> This method shall only get invoked if the plugin re-creates <b>all</b> the output.</p>
     *
     * <p>It first picks up the list of files created in the previous build and delete them. This step is necessary to
     * prevent left-overs. After that we take a 'directory snapshot' (list of all files which exist in the
     * outputDirectory after the clean). </p>
     *
     * <p>After the actual Mojo task got executed you should invoke the method
     * {@link #afterRebuildExecution(IncrementalBuildHelperRequest)} to collect the list of files which got changed by
     * this task.</p>
     *
     * @param incrementalBuildHelperRequest
     * @return all files which got created in the previous build and have been deleted now.
     * @throws MojoExecutionException
     */
    public String[] beforeRebuildExecution(IncrementalBuildHelperRequest incrementalBuildHelperRequest)
            throws MojoExecutionException {
        File mojoConfigBase = getMojoStatusDirectory();
        File mojoConfigFile = new File(mojoConfigBase, CREATED_FILES_LST_FILENAME);

        String[] oldFiles;

        try {
            oldFiles = readLines(mojoConfigFile);
            for (String oldFileName : oldFiles) {
                File oldFile = new File(incrementalBuildHelperRequest.getOutputDirectory(), oldFileName);
                oldFile.delete();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading old mojo status", e);
        }

        // we remember all files which currently exist in the output directory
        filesBeforeAction = listFiles(incrementalBuildHelperRequest.getOutputDirectory());

        return oldFiles;
    }

    /**
     * <p>This method collects and stores all information about files changed since the call to
     * {@link #beforeRebuildExecution(IncrementalBuildHelperRequest)}.</p>
     *
     * <p><b>Attention:</b> This method shall only get invoked if the plugin re-creates <b>all</b> the output.</p>
     *
     * @param incrementalBuildHelperRequest will contains file sources to store if create files are not yet stored
     * @throws MojoExecutionException
     */
    public void afterRebuildExecution(IncrementalBuildHelperRequest incrementalBuildHelperRequest)
            throws MojoExecutionException {
        // now scan the same directory again and create a diff
        Set<String> filesAfterAction = listFiles(incrementalBuildHelperRequest.getOutputDirectory());
        filesAfterAction.removeAll(filesBeforeAction);

        File mojoConfigBase = getMojoStatusDirectory();
        File mojoConfigFile = new File(mojoConfigBase, CREATED_FILES_LST_FILENAME);

        try {
            writeLines(mojoConfigFile, filesAfterAction.toArray(EMPTY_ARRAY));
        } catch (IOException e) {
            throw new MojoExecutionException("Error while storing the mojo status", e);
        }

        // in case of clean compile the file is not created so next compile won't see it
        // we mus create it here
        mojoConfigFile = new File(mojoConfigBase, INPUT_FILES_LST_FILENAME);
        if (!mojoConfigFile.exists()) {
            try {
                writeLines(mojoConfigFile, toArrayOfPath(incrementalBuildHelperRequest.getInputFiles()));
            } catch (IOException e) {
                throw new MojoExecutionException("Error while storing the mojo status", e);
            }
        }
    }

    private static String[] toArrayOfPath(Set<File> files) {
        if (files == null || files.isEmpty()) {
            return EMPTY_ARRAY;
        }
        return files.stream().map(File::getPath).toArray(String[]::new);
    }

    /**
     * Lists every regular file below {@code directory}, as paths relative to it, in the order they are encountered.
     * Returns an empty set when the directory does not exist.
     */
    private static Set<String> listFiles(File directory) throws MojoExecutionException {
        if (directory == null || !directory.exists()) {
            return new LinkedHashSet<>();
        }
        Path base = directory.toPath();
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> base.relativize(path).toString())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            throw new MojoExecutionException("Error scanning the output directory " + directory, e);
        }
    }

    /**
     * Reads a status file the way {@code maven-shared-utils} {@code FileUtils.fileReadArray} did: an absent file
     * yields no lines, and trimmed, empty and {@code #}-prefixed lines are skipped.
     */
    private static String[] readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        if (file.exists()) {
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Charset.defaultCharset())) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    line = line.trim();
                    if (!line.startsWith("#") && !line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        }
        return lines.toArray(EMPTY_ARRAY);
    }

    /**
     * Writes a status file the way {@code maven-shared-utils} {@code FileUtils.fileWriteArray} did: one entry per
     * line, {@code \n} terminated, in the platform default charset.
     */
    private static void writeLines(File file, String[] data) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file.toPath(), Charset.defaultCharset())) {
            for (String line : data) {
                writer.write(line);
                writer.write("\n");
            }
        }
    }
}
