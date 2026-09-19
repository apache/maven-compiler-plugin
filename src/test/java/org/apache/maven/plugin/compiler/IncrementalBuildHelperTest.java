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

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalBuildHelperTest {

    @TempDir
    Path basedir;

    private Path outputDirectory;

    private IncrementalBuildHelper helper;

    @BeforeEach
    void setUp() {
        outputDirectory = basedir.resolve("target").resolve("classes");

        PluginDescriptor pluginDescriptor = new PluginDescriptor();
        pluginDescriptor.setArtifactId("maven-compiler-plugin");
        MojoDescriptor mojoDescriptor = new MojoDescriptor();
        mojoDescriptor.setGoal("compile");
        mojoDescriptor.setPluginDescriptor(pluginDescriptor);
        MojoExecution mojoExecution = new MojoExecution(mojoDescriptor, "default-compile");

        Build build = new Build();
        build.setDirectory(basedir.resolve("target").toString());
        MavenProject project = new MavenProject();
        project.setBuild(build);

        helper = new IncrementalBuildHelper(mojoExecution, project);
    }

    @Test
    void statusDirectoryFollowsThePluginGoalExecutionLayout() throws Exception {
        File statusDirectory = helper.getMojoStatusDirectory();

        assertEquals(
                basedir.resolve("target")
                        .resolve("maven-status")
                        .resolve("maven-compiler-plugin")
                        .resolve("compile")
                        .resolve("default-compile")
                        .toFile(),
                statusDirectory);
        assertTrue(statusDirectory.isDirectory());
    }

    @Test
    void recordsCreatedFilesAndDeletesThemBeforeTheNextRebuild() throws Exception {
        Files.createDirectories(outputDirectory.resolve("pkg"));
        Files.write(outputDirectory.resolve("pkg").resolve("Existing.class"), new byte[0]);
        IncrementalBuildHelperRequest request = new IncrementalBuildHelperRequest()
                .inputFiles(new HashSet<>(
                        Collections.singleton(basedir.resolve("Foo.java").toFile())))
                .outputDirectory(outputDirectory.toFile());

        assertArrayEquals(new String[0], helper.beforeRebuildExecution(request));
        Files.write(outputDirectory.resolve("pkg").resolve("Foo.class"), new byte[0]);
        Files.write(outputDirectory.resolve("pkg").resolve("Foo$1.class"), new byte[0]);
        helper.afterRebuildExecution(request);

        Path statusDirectory = helper.getMojoStatusDirectory().toPath();
        List<String> createdFiles =
                Files.readAllLines(statusDirectory.resolve(IncrementalBuildHelper.CREATED_FILES_LST_FILENAME));
        assertEquals(2, createdFiles.size());
        assertTrue(createdFiles.contains(Paths.get("pkg", "Foo.class").toString()), createdFiles.toString());
        assertTrue(createdFiles.contains(Paths.get("pkg", "Foo$1.class").toString()), createdFiles.toString());
        assertEquals(
                Collections.singletonList(basedir.resolve("Foo.java").toString()),
                Files.readAllLines(statusDirectory.resolve("inputFiles.lst")));

        // the next rebuild removes exactly what the previous one created
        String[] deleted = helper.beforeRebuildExecution(request);
        assertEquals(2, deleted.length);
        assertFalse(Files.exists(outputDirectory.resolve("pkg").resolve("Foo.class")));
        assertFalse(Files.exists(outputDirectory.resolve("pkg").resolve("Foo$1.class")));
        assertTrue(Files.exists(outputDirectory.resolve("pkg").resolve("Existing.class")));
    }

    @Test
    void readsStatusFilesWrittenByMavenSharedIncremental() throws Exception {
        // maven-shared-incremental 1.1 wrote "\n"-terminated lines in the platform charset and, on reading,
        // trimmed, dropped blank lines and dropped '#' comments. State left by an older plugin must still count.
        Files.createDirectories(outputDirectory);
        Files.write(outputDirectory.resolve("Stale.class"), new byte[0]);
        Files.write(outputDirectory.resolve("Kept.class"), new byte[0]);
        Path statusDirectory = helper.getMojoStatusDirectory().toPath();
        Files.write(
                statusDirectory.resolve(IncrementalBuildHelper.CREATED_FILES_LST_FILENAME),
                "# written by 3.16.0\n  Stale.class  \n\nMissing.class\n".getBytes(Charset.defaultCharset()));
        IncrementalBuildHelperRequest request =
                new IncrementalBuildHelperRequest().outputDirectory(outputDirectory.toFile());

        String[] deleted = helper.beforeRebuildExecution(request);

        assertEquals(Arrays.asList("Stale.class", "Missing.class"), Arrays.asList(deleted));
        assertFalse(Files.exists(outputDirectory.resolve("Stale.class")));
        assertTrue(Files.exists(outputDirectory.resolve("Kept.class")));
    }

    @Test
    void toleratesAMissingOutputDirectory() throws Exception {
        IncrementalBuildHelperRequest request =
                new IncrementalBuildHelperRequest().outputDirectory(outputDirectory.toFile());

        assertArrayEquals(new String[0], helper.beforeRebuildExecution(request));
        Files.createDirectories(outputDirectory);
        Files.write(outputDirectory.resolve("New.class"), new byte[0]);
        helper.afterRebuildExecution(request);

        assertEquals(
                Collections.singletonList("New.class"),
                Files.readAllLines(helper.getMojoStatusDirectory()
                        .toPath()
                        .resolve(IncrementalBuildHelper.CREATED_FILES_LST_FILENAME)));
    }
}
