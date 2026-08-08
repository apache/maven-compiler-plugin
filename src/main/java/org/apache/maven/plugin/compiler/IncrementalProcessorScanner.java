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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.api.plugin.Log;

/**
 * Scans annotation processor JARs for incrementality declarations.
 *
 * <p>Reads descriptors from both {@code META-INF/gradle/incremental.annotation.processors}
 * and {@code META-INF/maven/incremental.annotation.processors}. The format is identical:
 * one line per processor with {@code <fully-qualified-class-name>,<type>} where type is
 * one of {@code isolating}, {@code aggregating}, or {@code dynamic}.</p>
 *
 * <p>The Maven descriptor takes precedence over the Gradle descriptor within the same JAR.
 * If both exist, only the Maven descriptor is read. Across JARs, results are merged — each
 * JAR contributes its own processor declarations.</p>
 *
 * <p>The scanner also reads {@code META-INF/services/javax.annotation.processing.Processor}
 * to discover processor class names. Any processor discovered via ServiceLoader but missing
 * from an incrementality descriptor is treated as {@link IncrementalProcessorType#UNKNOWN},
 * triggering the conservative full-rebuild behavior.</p>
 *
 * @since 4.0.0
 * @see IncrementalProcessorType
 */
class IncrementalProcessorScanner {

    /**
     * Gradle's descriptor path — the established convention.
     */
    private static final String GRADLE_DESCRIPTOR = "META-INF/gradle/incremental.annotation.processors";

    /**
     * Maven's descriptor path — same format, Maven-specific location.
     */
    private static final String MAVEN_DESCRIPTOR = "META-INF/maven/incremental.annotation.processors";

    /**
     * Standard ServiceLoader file for annotation processors.
     */
    private static final String SERVICE_FILE = "META-INF/services/javax.annotation.processing.Processor";

    private final Log logger;

    IncrementalProcessorScanner(Log logger) {
        this.logger = logger;
    }

    /**
     * Scans the given processor JARs and returns the aggregate incrementality type.
     *
     * <p>The rules are:</p>
     * <ol>
     *   <li>If no processor JARs are provided, returns {@link IncrementalProcessorType#UNKNOWN}.</li>
     *   <li>If no JAR contains an incrementality descriptor, returns {@code UNKNOWN}.</li>
     *   <li>If any ServiceLoader-declared processor lacks an incrementality entry, returns {@code UNKNOWN}.</li>
     *   <li>If any processor declares {@code DYNAMIC} or {@code UNKNOWN}, returns {@code UNKNOWN}.</li>
     *   <li>If any processor declares {@code AGGREGATING}, returns {@code AGGREGATING}.</li>
     *   <li>Otherwise (all {@code ISOLATING}), returns {@code ISOLATING}.</li>
     * </ol>
     *
     * @param processorPaths paths to processor JARs on the annotation processor classpath
     * @return the aggregate incrementality type
     */
    IncrementalProcessorType scan(Collection<Path> processorPaths) {
        if (processorPaths == null || processorPaths.isEmpty()) {
            return IncrementalProcessorType.UNKNOWN;
        }

        Map<String, IncrementalProcessorType> declaredTypes = new HashMap<>();
        Set<String> serviceProcessors = new HashSet<>();
        boolean foundAnyDescriptor = false;

        for (Path path : processorPaths) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try (JarFile jar = new JarFile(path.toFile())) {
                readServiceFile(jar, serviceProcessors);

                // Maven descriptor takes precedence; fall back to Gradle's
                boolean found = readDescriptor(jar, MAVEN_DESCRIPTOR, declaredTypes);
                if (!found) {
                    found = readDescriptor(jar, GRADLE_DESCRIPTOR, declaredTypes);
                }
                if (found) {
                    foundAnyDescriptor = true;
                }
            } catch (IOException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Cannot read processor JAR " + path + ": " + e.getMessage());
                }
                return IncrementalProcessorType.UNKNOWN;
            }
        }

        if (!foundAnyDescriptor) {
            return IncrementalProcessorType.UNKNOWN;
        }

        // Check that every ServiceLoader-declared processor has an incrementality entry
        for (String processor : serviceProcessors) {
            if (!declaredTypes.containsKey(processor)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Annotation processor " + processor
                            + " has no incrementality declaration — falling back to full rebuild on change.");
                }
                return IncrementalProcessorType.UNKNOWN;
            }
        }

        // Determine aggregate type from all declarations
        boolean hasAggregating = false;
        for (Map.Entry<String, IncrementalProcessorType> entry : declaredTypes.entrySet()) {
            IncrementalProcessorType type = entry.getValue();
            switch (type) {
                case DYNAMIC:
                case UNKNOWN:
                    if (logger.isDebugEnabled()) {
                        logger.debug("Annotation processor " + entry.getKey() + " declares "
                                + type.name().toLowerCase(Locale.US)
                                + " incrementality — falling back to full rebuild on change.");
                    }
                    return IncrementalProcessorType.UNKNOWN;
                case AGGREGATING:
                    hasAggregating = true;
                    break;
                case ISOLATING:
                    break;
                default:
                    break;
            }
        }

        IncrementalProcessorType result =
                hasAggregating ? IncrementalProcessorType.AGGREGATING : IncrementalProcessorType.ISOLATING;

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "All annotation processors declare " + result.name().toLowerCase(Locale.US) + " incrementality.");
        }
        return result;
    }

    /**
     * Reads processor class names from {@code META-INF/services/javax.annotation.processing.Processor}.
     * Follows the {@link java.util.ServiceLoader} format: one class name per line, {@code #} comments,
     * leading/trailing whitespace stripped.
     */
    private void readServiceFile(JarFile jar, Set<String> processors) throws IOException {
        JarEntry entry = jar.getJarEntry(SERVICE_FILE);
        if (entry == null) {
            return;
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                if (comment >= 0) {
                    line = line.substring(0, comment);
                }
                line = line.trim();
                if (!line.isEmpty()) {
                    processors.add(line);
                }
            }
        }
    }

    /**
     * Reads incrementality declarations from a descriptor file inside a JAR.
     * Format: {@code <fully-qualified-class-name>,<type>} — one per line, blank lines and
     * {@code #} comments are ignored.
     *
     * @param jar the JAR to read from
     * @param descriptorPath the path inside the JAR to the descriptor file
     * @param types the map to populate with processor types (processor class name → type)
     * @return {@code true} if the descriptor existed and was read
     */
    private boolean readDescriptor(JarFile jar, String descriptorPath, Map<String, IncrementalProcessorType> types)
            throws IOException {

        JarEntry entry = jar.getJarEntry(descriptorPath);
        if (entry == null) {
            return false;
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int comma = line.indexOf(',');
                if (comma < 0) {
                    logger.warn("Malformed incrementality declaration (missing comma): " + line);
                    continue;
                }
                String className = line.substring(0, comma).trim();
                String typeName = line.substring(comma + 1).trim().toUpperCase(Locale.US);
                try {
                    types.put(className, IncrementalProcessorType.valueOf(typeName));
                } catch (IllegalArgumentException e) {
                    logger.warn("Unknown incrementality type '" + typeName + "' for processor " + className);
                    types.put(className, IncrementalProcessorType.UNKNOWN);
                }
            }
        }
        return true;
    }
}
