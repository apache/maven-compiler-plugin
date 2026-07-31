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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.incremental.IncrementalBuildHelper;
import org.codehaus.plexus.util.FileUtils;

/**
 * Persists the class path and module path used by a compiler Mojo execution and detects dependencies modified during
 * the current Maven build while collecting that state.
 */
final class DependencyState {
    private static final String STATE_FILE = "dependencies.lst";
    private static final String STATE_SEPARATOR = "\t";
    private static final Set<String> DEFAULT_FILE_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("class", "jar")));

    private DependencyState() {}

    static boolean hasChanged(
            IncrementalBuildHelper incrementalBuildHelper,
            File outputDirectory,
            List<String> classpathElements,
            List<String> modulepathElements,
            Configuration configuration) {
        Path stateFile = null;
        try {
            stateFile = incrementalBuildHelper.getMojoStatusDirectory().toPath().resolve(STATE_FILE);
        } catch (MojoExecutionException e) {
            configuration.log.warn("Error reading mojo status directory.");
        }

        List<String> oldState = Collections.emptyList();
        boolean hasPreviousState = stateFile != null && Files.isRegularFile(stateFile);
        if (hasPreviousState) {
            try {
                oldState = Files.readAllLines(stateFile);
            } catch (IOException e) {
                configuration.log.warn("Error while reading old dependency status: " + stateFile);
                hasPreviousState = false;
            }
        }

        List<String> newState = new ArrayList<>();
        ScanContext context = new ScanContext(outputDirectory, configuration);
        Path changedDependency = addDependencies(newState, "classpath:", classpathElements, context, null);
        changedDependency = addDependencies(newState, "modulepath:", modulepathElements, context, changedDependency);

        if (stateFile != null) {
            try {
                Files.write(stateFile, newState);
            } catch (IOException e) {
                configuration.log.warn("Error while writing new dependency status: " + stateFile);
            }
        }

        if (hasPreviousState && !oldState.equals(newState)) {
            if (configuration.log.isDebugEnabled() || configuration.showChanges) {
                logChanges(oldState, newState, configuration.log);
            }
            return true;
        }
        if (changedDependency != null) {
            if (configuration.log.isDebugEnabled() || configuration.showChanges) {
                configuration.log.info("\tNew dependency detected: " + changedDependency.toAbsolutePath());
            }
            return true;
        }
        if (configuration.buildStartTime == null) {
            configuration.log.debug("Cannot determine build start time, skipping incremental build detection.");
        }
        return false;
    }

    private static Path addDependencies(
            List<String> state, String prefix, List<String> pathElements, ScanContext context, Path changedDependency) {
        for (String pathElement : pathElements) {
            Path dependencyPath = Paths.get(pathElement).toAbsolutePath().normalize();
            if (!dependencyPath.equals(context.outputPath)) {
                ModificationState modification = context.modifications.get(dependencyPath);
                if (modification == null) {
                    modification = modificationState(dependencyPath, context);
                    context.modifications.put(dependencyPath, modification);
                }
                state.add(prefix + dependencyPath + STATE_SEPARATOR + modification.recordedState);
                if (changedDependency == null && modification.changedDependency != null) {
                    changedDependency = modification.changedDependency;
                }
            }
        }
        return changedDependency;
    }

    /**
     * Returns {@code size:mtime} for a file, or {@code relevant-file-count:metadata-sha256} for a directory.
     * The directory digest covers each relevant file's relative path, size and modification time.
     */
    private static ModificationState modificationState(Path dependency, ScanContext context) {
        if (!Files.isDirectory(dependency)) {
            try {
                BasicFileAttributes attributes = readAttributes(dependency);
                Path changedDependency = context.fileExtensions.contains(FileUtils.extension(
                                        dependency.getFileName().toString()))
                                && changedSinceBuildStart(attributes, context)
                        ? dependency
                        : null;
                return new ModificationState(fileMetadata(attributes), changedDependency);
            } catch (IOException e) {
                context.log.warn("I/O error reading dependency state: " + dependency + ": " + e.getMessage());
                return new ModificationState("unreadable", null);
            }
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(dependency)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(file -> context.fileExtensions.contains(
                            FileUtils.extension(file.getFileName().toString())))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            context.log.warn("I/O error reading dependency state: " + dependency + ": " + e.getMessage());
            return new ModificationState("unreadable", null);
        }

        MessageDigest digest = newDigest();
        Path changedDependency = null;
        boolean unreadable = false;
        for (Path file : files) {
            try {
                BasicFileAttributes attributes = readAttributes(file);
                update(digest, dependency.relativize(file).toString());
                update(digest, fileMetadata(attributes));
                if (changedDependency == null && changedSinceBuildStart(attributes, context)) {
                    changedDependency = file;
                }
            } catch (IOException e) {
                if (!unreadable) {
                    context.log.warn("I/O error reading dependency state: " + dependency + ": " + e.getMessage());
                    unreadable = true;
                }
            }
        }
        String recordedState = unreadable ? "unreadable" : files.size() + ":" + toHexString(digest.digest());
        return new ModificationState(recordedState, changedDependency);
    }

    private static BasicFileAttributes readAttributes(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class);
    }

    private static String fileMetadata(BasicFileAttributes attributes) {
        return attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
    }

    private static boolean changedSinceBuildStart(BasicFileAttributes attributes, ScanContext context) {
        return context.buildStartTime != null
                && attributes
                        .lastModifiedTime()
                        .toInstant()
                        .minusMillis(context.staleMillis)
                        .truncatedTo(ChronoUnit.MILLIS)
                        .isAfter(context.buildStartTime);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder buffer = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            buffer.append(Character.forDigit(unsigned >>> 4, 16));
            buffer.append(Character.forDigit(unsigned & 0x0F, 16));
        }
        return buffer.toString();
    }

    private static void logChanges(List<String> oldState, List<String> newState, Log log) {
        List<String> oldPaths = paths(oldState);
        List<String> newPaths = paths(newState);
        DeltaList<String> pathChanges = new DeltaList<>(oldPaths, newPaths);
        for (String dependencyAdded : pathChanges.getAdded()) {
            log.info("\tDependency path (+): " + dependencyAdded);
        }
        for (String dependencyRemoved : pathChanges.getRemoved()) {
            log.info("\tDependency path (-): " + dependencyRemoved);
        }
        if (!pathChanges.hasChanged()) {
            if (!oldPaths.equals(newPaths)) {
                log.info("\tDependency path order changed.");
            } else {
                for (int i = 0; i < newState.size(); i++) {
                    if (!oldState.get(i).equals(newState.get(i))) {
                        log.info("\tDependency modified: " + newPaths.get(i));
                    }
                }
            }
        }
    }

    private static List<String> paths(Collection<String> state) {
        return state.stream().map(DependencyState::path).collect(Collectors.toList());
    }

    private static String path(String stateEntry) {
        int separator = stateEntry.indexOf(STATE_SEPARATOR);
        return separator >= 0 ? stateEntry.substring(0, separator) : stateEntry;
    }

    static final class Configuration {
        private final Set<String> fileExtensions;
        private final Instant buildStartTime;
        private final int staleMillis;
        private final Log log;
        private final boolean showChanges;

        Configuration(
                Set<String> fileExtensions, Instant buildStartTime, int staleMillis, Log log, boolean showChanges) {
            this.fileExtensions =
                    fileExtensions == null || fileExtensions.isEmpty() ? DEFAULT_FILE_EXTENSIONS : fileExtensions;
            this.buildStartTime = buildStartTime;
            this.staleMillis = staleMillis;
            this.log = log;
            this.showChanges = showChanges;
        }
    }

    private static final class ScanContext {
        private final Path outputPath;
        private final Set<String> fileExtensions;
        private final Instant buildStartTime;
        private final int staleMillis;
        private final Log log;
        private final Map<Path, ModificationState> modifications = new HashMap<>();

        private ScanContext(File outputDirectory, Configuration configuration) {
            outputPath = outputDirectory.toPath().toAbsolutePath().normalize();
            fileExtensions = configuration.fileExtensions;
            buildStartTime = configuration.buildStartTime;
            staleMillis = configuration.staleMillis;
            log = configuration.log;
        }
    }

    private static final class ModificationState {
        private final String recordedState;
        private final Path changedDependency;

        private ModificationState(String recordedState, Path changedDependency) {
            this.recordedState = recordedState;
            this.changedDependency = changedDependency;
        }
    }
}
