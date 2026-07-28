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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;

/**
 * Tracks output files processed by compiler executions for the current project and Maven session.
 *
 * <p>Multiple compiler executions may share an output directory while using different compiler options.
 * {@link org.apache.maven.shared.incremental.IncrementalBuildHelper} compares output file names before and after
 * compilation, so it cannot detect an existing output overwritten by another execution. This registry lets a later
 * execution detect that overlap without relying on file timestamps, whose precision varies between file systems.</p>
 *
 * <p>The registry is stored in Maven's plugin context, which is shared by all executions of this plugin for one
 * project and discarded after the Maven session.</p>
 */
final class CompilationOutputRegistry {
    private static final String KEY = CompilationOutputRegistry.class.getName() + ".compiledOutputs";

    /**
     * Prevents instantiation.
     */
    private CompilationOutputRegistry() {}

    /**
     * Maps sources to normalized absolute output paths.
     *
     * @param mapping mapping from source paths to output paths
     * @param outputDirectory compiler output directory
     * @param sourceRoots configured source roots
     * @param sources sources selected by the compiler execution
     * @return expected output paths
     * @throws InclusionScanException if a source cannot be mapped
     */
    static Set<Path> mapOutputs(
            SourceMapping mapping, File outputDirectory, List<String> sourceRoots, Set<File> sources)
            throws InclusionScanException {
        Set<Path> outputs = new HashSet<>();
        List<Path> roots = new ArrayList<>(sourceRoots.size());
        for (String sourceRoot : sourceRoots) {
            roots.add(Paths.get(sourceRoot).toAbsolutePath().normalize());
        }
        for (File source : sources) {
            Path path = source.toPath().toAbsolutePath().normalize();
            Path matchingRoot = null;
            for (Path root : roots) {
                if (path.startsWith(root)
                        && (matchingRoot == null || root.getNameCount() > matchingRoot.getNameCount())) {
                    matchingRoot = root;
                }
            }
            if (matchingRoot != null) {
                for (File output : mapping.getTargetFiles(
                        outputDirectory, matchingRoot.relativize(path).toString())) {
                    outputs.add(output.toPath().toAbsolutePath().normalize());
                }
            }
        }
        return outputs;
    }

    /**
     * Finds an output most recently processed by a different compiler execution.
     *
     * @param pluginContext Maven's context for this plugin and project
     * @param compilerExecution the current compiler execution, formatted as {@code goal@executionId}
     * @param outputs outputs mapped from the current execution's sources
     * @return an overlapping output, or an empty value if there is none
     */
    static Optional<Path> find(Map<?, ?> pluginContext, String compilerExecution, Set<Path> outputs) {
        Map<Path, String> compiledOutputs = get(pluginContext, false);
        return compiledOutputs != null
                ? outputs.stream()
                        .filter(output -> {
                            String previousExecution = compiledOutputs.get(output);
                            return previousExecution != null && !previousExecution.equals(compilerExecution);
                        })
                        .findFirst()
                : Optional.empty();
    }

    /**
     * Associates outputs with the compiler execution that most recently processed them.
     *
     * @param pluginContext Maven's context for this plugin and project
     * @param compilerExecution the compiler execution, formatted as {@code goal@executionId}
     * @param outputs outputs mapped from the execution's sources
     */
    static void register(Map<?, ?> pluginContext, String compilerExecution, Set<Path> outputs) {
        Map<Path, String> compiledOutputs = get(pluginContext, true);
        if (compiledOutputs != null) {
            for (Path output : outputs) {
                compiledOutputs.put(output, compilerExecution);
            }
        }
    }

    /**
     * Returns the output registry from the plugin context.
     *
     * @param pluginContext Maven's context for this plugin and project
     * @param create whether to create the registry when absent
     * @return the registry, or {@code null} if the context or registry is absent
     */
    @SuppressWarnings("unchecked")
    private static Map<Path, String> get(Map<?, ?> pluginContext, boolean create) {
        if (pluginContext == null) {
            return null;
        }
        Object value = pluginContext.get(KEY);
        if (value == null && create) {
            Map<Path, String> outputs = new ConcurrentHashMap<>();
            Map<Object, Object> context = (Map<Object, Object>) pluginContext;
            value = context.putIfAbsent(KEY, outputs);
            if (value == null) {
                return outputs;
            }
        }
        return (Map<Path, String>) value;
    }
}
