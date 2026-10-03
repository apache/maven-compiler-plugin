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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers annotation processor classifications from the processor classpath.
 *
 * <p>Reads classification files from two locations in each classpath entry
 * (directory or JAR), checked in order:
 * <ol>
 *   <li>{@code META-INF/javaci/incremental.annotation.processors}</li>
 *   <li>{@code META-INF/gradle/incremental.annotation.processors}
 *       (Gradle-compatible, for ecosystem reuse)</li>
 * </ol>
 *
 * <p>File format: one processor per line, {@code fully.qualified.Name,TYPE}
 * where TYPE is {@code ISOLATING} or {@code AGGREGATING}. Empty lines and
 * lines starting with {@code #} are ignored.
 *
 * <p>Processors not found in any classification file are classified as
 * {@link ProcessorType#UNKNOWN}.
 *
 * @see ProcessorType
 */
public class ProcessorClassification {

    private static final String JAVACI_RESOURCE = "META-INF/javaci/incremental.annotation.processors";
    private static final String GRADLE_RESOURCE = "META-INF/gradle/incremental.annotation.processors";

    private final Map<String, ProcessorType> classifications = new HashMap<>();

    /**
     * Creates a classification by scanning the given classpath entries for
     * processor classification files.
     *
     * @param processorPath classpath entries (directories or JARs) to scan
     */
    public ProcessorClassification(List<Path> processorPath) {
        if (processorPath != null) {
            for (Path entry : processorPath) {
                loadFromEntry(entry);
            }
        }
    }

    /**
     * Returns the classification for the given processor class name.
     *
     * @param processorClassName fully qualified processor class name
     * @return the declared type, or {@link ProcessorType#UNKNOWN} if not declared
     */
    public ProcessorType classify(String processorClassName) {
        return classifications.getOrDefault(processorClassName, ProcessorType.UNKNOWN);
    }

    /**
     * Returns the worst (most conservative) classification across all given processors.
     * UNKNOWN &gt; AGGREGATING &gt; ISOLATING.
     */
    public ProcessorType worstCase(List<String> processorClassNames) {
        if (processorClassNames == null || processorClassNames.isEmpty()) {
            return ProcessorType.ISOLATING;
        }
        ProcessorType worst = ProcessorType.ISOLATING;
        for (String name : processorClassNames) {
            ProcessorType type = classify(name);
            if (type == ProcessorType.UNKNOWN) {
                return ProcessorType.UNKNOWN;
            }
            if (type == ProcessorType.AGGREGATING) {
                worst = ProcessorType.AGGREGATING;
            }
        }
        return worst;
    }

    /**
     * Returns the full classification map (for testing/debugging).
     */
    public Map<String, ProcessorType> getClassifications() {
        return Map.copyOf(classifications);
    }

    private void loadFromEntry(Path entry) {
        if (!Files.exists(entry)) {
            return;
        }
        if (Files.isDirectory(entry)) {
            loadFromDirectory(entry);
        } else if (isJarFile(entry)) {
            loadFromJar(entry);
        }
    }

    private void loadFromDirectory(Path dir) {
        loadFile(dir.resolve(JAVACI_RESOURCE));
        loadFile(dir.resolve(GRADLE_RESOURCE));
    }

    private void loadFromJar(Path jarPath) {
        try (FileSystem fs = FileSystems.newFileSystem(jarPath)) {
            loadFile(fs.getPath(JAVACI_RESOURCE));
            loadFile(fs.getPath(GRADLE_RESOURCE));
        } catch (IOException ignored) {
            // Skip unreadable JARs
        }
    }

    private void loadFile(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try (var reader =
                new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int comma = line.indexOf(',');
                if (comma > 0 && comma < line.length() - 1) {
                    String name = line.substring(0, comma).strip();
                    String typeStr = line.substring(comma + 1).strip().toUpperCase(java.util.Locale.ROOT);
                    try {
                        ProcessorType type = ProcessorType.valueOf(typeStr);
                        // Don't overwrite — first classification wins (javaci before gradle)
                        classifications.putIfAbsent(name, type);
                    } catch (IllegalArgumentException ignored) {
                        // Skip unrecognized types
                    }
                }
            }
        } catch (IOException ignored) {
            // Skip unreadable files
        }
    }

    private static boolean isJarFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }
}
