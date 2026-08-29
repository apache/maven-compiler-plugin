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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.incremental.IncrementalBuildHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DependencyStateTest {
    private static final Instant BUILD_START = Instant.ofEpochMilli(2_000);

    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsDependencyNewerThanBuildStartWithoutPreviousState() throws Exception {
        Path dependency = dependency("dependency.jar", 3_000, 1);

        assertTrue(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), BUILD_START, 0));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("dependencies.lst")));
    }

    @Test
    void createsBaselineWithoutRebuildingForOlderDependency() throws Exception {
        Path dependency = dependency("dependency.jar", 1_000, 1);

        assertFalse(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), BUILD_START, 0));
        assertFalse(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), BUILD_START, 0));
    }

    @Test
    void detectsSamePathReplacementBeforeNextBuildStarts() throws Exception {
        Path dependency = dependency("dependency.jar", 1_000, 1);
        assertFalse(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), BUILD_START, 0));

        Files.write(dependency, new byte[] {1, 2});
        Files.setLastModifiedTime(dependency, FileTime.fromMillis(3_000));

        assertTrue(hasChanged(
                Collections.singletonList(dependency), Collections.emptyList(), Instant.ofEpochMilli(4_000), 0));
    }

    @Test
    void detectsPersistedChangeWithoutBuildStartTime() throws Exception {
        Path dependency = dependency("dependency.jar", 1_000, 1);
        assertFalse(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), null, 0));

        Files.write(dependency, new byte[] {1, 2});

        assertTrue(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), null, 0));
    }

    @Test
    void detectsChangedFileInExplodedDependencyWithoutPreviousState() throws Exception {
        Path dependency = Files.createDirectories(temporaryDirectory.resolve("classes"));
        Path classFile = dependency.resolve("example/Dependency.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[] {1});
        Files.setLastModifiedTime(classFile, FileTime.fromMillis(3_000));

        assertTrue(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), BUILD_START, 0));
    }

    @Test
    void appliesStaleMillisToCurrentBuildDetection() throws Exception {
        Path dependency = dependency("dependency.jar", 3_000, 1);

        assertFalse(hasChanged(Collections.singletonList(dependency), Collections.emptyList(), BUILD_START, 1_000));
    }

    @Test
    void preservesRepeatedDependencyEntriesAndPathKinds() throws Exception {
        Path dependency = dependency("dependency.jar", 1_000, 1);

        assertFalse(hasChanged(
                Arrays.asList(dependency, dependency), Collections.singletonList(dependency), BUILD_START, 0));

        List<String> state = Files.readAllLines(temporaryDirectory.resolve("dependencies.lst"));
        assertEquals(3, state.size());
        assertTrue(state.get(0).startsWith("classpath:"));
        assertTrue(state.get(1).startsWith("classpath:"));
        assertTrue(state.get(2).startsWith("modulepath:"));
    }

    @Test
    void retainsCurrentBuildDetectionWhenStatusDirectoryIsUnavailable() throws Exception {
        Path dependency = dependency("dependency.jar", 3_000, 1);
        IncrementalBuildHelper helper = mock(IncrementalBuildHelper.class);
        when(helper.getMojoStatusDirectory()).thenThrow(new MojoExecutionException("unavailable"));

        assertTrue(DependencyState.hasChanged(
                helper,
                temporaryDirectory.resolve("output").toFile(),
                Collections.singletonList(dependency.toString()),
                Collections.emptyList(),
                new DependencyState.Configuration(Collections.emptySet(), BUILD_START, 0, mock(Log.class), false)));
    }

    private Path dependency(String name, long modifiedTime, int size) throws Exception {
        Path dependency = temporaryDirectory.resolve(name);
        Files.write(dependency, new byte[size]);
        Files.setLastModifiedTime(dependency, FileTime.fromMillis(modifiedTime));
        return dependency;
    }

    private boolean hasChanged(List<Path> classpath, List<Path> modulepath, Instant buildStartTime, int staleMillis)
            throws Exception {
        IncrementalBuildHelper helper = mock(IncrementalBuildHelper.class);
        when(helper.getMojoStatusDirectory()).thenReturn(temporaryDirectory.toFile());
        return DependencyState.hasChanged(
                helper,
                temporaryDirectory.resolve("output").toFile(),
                paths(classpath),
                paths(modulepath),
                new DependencyState.Configuration(
                        Collections.emptySet(), buildStartTime, staleMillis, mock(Log.class), false));
    }

    private static List<String> paths(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(java.util.stream.Collectors.toList());
    }
}
