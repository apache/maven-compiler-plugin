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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.sun.source.util.JavacTask;

/**
 * ABI-fingerprint-driven incremental build engine, designed for embedding
 * in maven-compiler-plugin alongside the existing timestamp-based
 * {@code IncrementalBuild}.
 *
 * <p>The plugin drives compilation; this class determines <em>what</em> to
 * compile and collects analysis data during compilation. Typical usage:
 *
 * <pre>{@code
 * var abi = new AbiIncrementalBuild(outputDir);
 * abi.setClasspathEntries(classpath);
 * abi.setReactorModulePaths(reactorModules);
 *
 * Set<Path> toCompile = abi.initialize(allSourceFiles);
 *
 * while (!toCompile.isEmpty()) {
 *     JavacTask task = (JavacTask) compiler.getTask(..., toCompile, ...);
 *     abi.attachTo(task);
 *     if (!task.call()) break;
 *     toCompile = abi.processRound();
 * }
 *
 * abi.finish();
 * }</pre>
 *
 * <p>The engine persists its state as {@code .incremental-state} and writes
 * an {@link AbiManifest} ({@code .abi-fingerprints}) in the output directory
 * for downstream reactor modules.
 *
 * @see CompilationAnalyzer
 * @see IncrementalState
 */
public class AbiIncrementalBuild {

    private final Path outputDir;
    private final Path buildDir;
    private final Path stateFile;
    private List<Path> classpathEntries;
    private Set<Path> reactorModulePaths;
    private List<Path> processorPath;
    private ProcessorClassification processorClassification;

    private IncrementalState previousState;
    private IncrementalState state;
    private Map<String, String> sourceHashes;
    private List<Path> allSourceFiles;
    private Set<String> allCompiled;
    private CompilationAnalyzer currentAnalyzer;
    private boolean fullBuild;
    private int totalSources;

    public AbiIncrementalBuild(Path outputDir) {
        this.outputDir = outputDir;
        this.buildDir = outputDir.getParent() != null ? outputDir.getParent() : outputDir;
        this.stateFile = buildDir.resolve(".incremental-state");
    }

    /**
     * Sets classpath entries for cross-module ABI tracking. Directory entries
     * are checked for {@link AbiManifest} files; JAR entries use bytecode
     * analysis as fallback.
     */
    public void setClasspathEntries(List<Path> entries) {
        this.classpathEntries = entries;
    }

    /**
     * Marks specific classpath entries as reactor modules. These are always
     * checked for ABI changes (via manifest or bytecode).
     */
    public void setReactorModulePaths(Set<Path> paths) {
        this.reactorModulePaths = paths;
    }

    /**
     * Sets the annotation processor classpath for processor classification.
     * Entries are scanned for {@code META-INF/javaci/incremental.annotation.processors}
     * and {@code META-INF/gradle/incremental.annotation.processors} to determine
     * whether each processor is {@link ProcessorType#ISOLATING},
     * {@link ProcessorType#AGGREGATING}, or {@link ProcessorType#UNKNOWN}.
     */
    public void setProcessorPath(List<Path> processorPath) {
        this.processorPath = processorPath;
        this.processorClassification = new ProcessorClassification(processorPath);
    }

    /**
     * Initializes the incremental build by scanning source files and comparing
     * against the previous build's state.
     *
     * @param allSourceFiles all source files in this module
     * @return the set of files that need compilation (may be all files for a
     *         full build, a subset for incremental, or empty if up-to-date)
     */
    public Set<Path> initialize(List<Path> allSourceFiles) throws IOException {
        Files.createDirectories(outputDir);

        this.allSourceFiles = allSourceFiles;
        totalSources = allSourceFiles.size();
        allCompiled = new TreeSet<>();
        sourceHashes = hashSourceFiles(allSourceFiles);
        previousState = IncrementalState.load(stateFile);

        if (previousState == null) {
            return initFullBuild(allSourceFiles);
        } else {
            return initIncrementalBuild(allSourceFiles);
        }
    }

    /**
     * Attaches the ABI analyzer to a javac task. Must be called before
     * {@code task.call()} on each compilation round.
     */
    public void attachTo(JavacTask task) {
        currentAnalyzer = new CompilationAnalyzer(task);
        task.addTaskListener(currentAnalyzer);
    }

    /**
     * Processes the results of the last compilation round. Compares new ABI
     * fingerprints against previous values and determines whether a cascade
     * round is needed.
     *
     * @return the next set of files to compile (cascade consumers), or empty
     *         if the fixpoint has been reached
     */
    public Set<Path> processRound() {
        if (currentAnalyzer == null) {
            return Set.of();
        }

        var results = currentAnalyzer.getResults();

        // Detect ABI changes
        var abiChanged = new TreeSet<String>();
        for (var result : results.values()) {
            String prevAbi = previousState != null ? previousState.getAbiFingerprint(result.qualifiedName()) : null;
            if (prevAbi == null || !prevAbi.equals(result.abiFingerprint())) {
                abiChanged.add(result.qualifiedName());
            }
        }

        // Update state with this round's results
        for (var entry : sourceHashes.entrySet()) {
            if (allCompiled.contains(entry.getKey())) {
                state.setSourceHash(entry.getKey(), entry.getValue());
            }
        }
        for (var result : results.values()) {
            state.setType(
                    result.qualifiedName(),
                    new IncrementalState.TypeInfo(
                            result.sourceFile(),
                            result.abiFingerprint(),
                            result.signatureDeps(),
                            result.implementationDeps(),
                            result.annotationTypes()));
        }

        if (fullBuild || abiChanged.isEmpty()) {
            return Set.of();
        }

        // Cascade: find consumers of ABI-changed types
        var abiCascade = new TreeSet<>(abiChanged);
        for (String type : abiChanged) {
            expandSignatureCascade(type, state, abiCascade);
        }

        var additionalFiles = new TreeSet<Path>();
        for (String cascadedType : abiCascade) {
            for (String consumer : state.getAllConsumers(cascadedType)) {
                String sf = state.sourceFileFor(consumer);
                if (sf != null && !allCompiled.contains(sf)) {
                    additionalFiles.add(Path.of(sf));
                    allCompiled.add(sf);
                }
            }
        }

        // Annotation processor cascade
        additionalFiles.addAll(computeProcessorCascade());

        return additionalFiles;
    }

    /**
     * Finalizes the incremental build: saves state and writes the ABI manifest
     * for downstream reactor modules.
     */
    public void finish() throws IOException {
        if (state == null) {
            return;
        }

        // Resolve and store external fingerprints
        Set<String> externalDeps = state.getExternalDependencies();
        if (!externalDeps.isEmpty()) {
            var resolver = createResolver();
            state.setExternalFingerprints(resolver.resolve(externalDeps));
            state.setClasspathIdentities(resolver.computeCurrentJarIdentities());
        }

        state.save(stateFile);
        AbiManifest.write(buildDir.resolve(AbiManifest.FILENAME), state.getAllAbiFingerprints());
    }

    /**
     * Returns whether this was a full build (no previous state).
     */
    public boolean isFullBuild() {
        return fullBuild;
    }

    /**
     * Returns the total number of files compiled across all rounds.
     */
    public int compiledCount() {
        return allCompiled.size();
    }

    /**
     * Returns the total number of files that were unchanged.
     */
    public int unchangedCount() {
        return totalSources - allCompiled.size();
    }

    // --- Initialization ---

    private Set<Path> initFullBuild(List<Path> allSourceFiles) {
        fullBuild = true;
        state = IncrementalState.from(sourceHashes, Map.of());

        var files = new TreeSet<Path>();
        for (Path f : allSourceFiles) {
            files.add(f);
            allCompiled.add(f.toString());
        }
        return files;
    }

    private Set<Path> initIncrementalBuild(List<Path> allSourceFiles) {
        fullBuild = false;
        state = previousState.copy();

        // Detect source changes
        var changedFiles = new TreeSet<String>();
        var newFiles = new TreeSet<String>();
        var deletedFiles = new TreeSet<>(previousState.getSourceHashes().keySet());

        for (var entry : sourceHashes.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            deletedFiles.remove(path);

            String previousHash = previousState.getSourceHash(path);
            if (previousHash == null) {
                newFiles.add(path);
            } else if (!hash.equals(previousHash)) {
                changedFiles.add(path);
            }
        }

        // Check external ABI changes
        Set<String> externallyInvalidated = checkExternalAbiChanges();

        if (changedFiles.isEmpty() && newFiles.isEmpty() && deletedFiles.isEmpty() && externallyInvalidated.isEmpty()) {
            return Set.of();
        }

        // Build initial recompilation set
        var toRecompile = new TreeSet<String>();
        toRecompile.addAll(changedFiles);
        toRecompile.addAll(newFiles);
        toRecompile.addAll(externallyInvalidated);

        // Consumers of deleted types
        for (String deleted : deletedFiles) {
            for (String type : previousState.getTypesFromSource(deleted)) {
                for (String consumer : previousState.getAllConsumers(type)) {
                    String sf = previousState.sourceFileFor(consumer);
                    if (sf != null) {
                        toRecompile.add(sf);
                    }
                }
                deleteClassFile(type);
            }
            state.removeSource(deleted);
        }

        // Clear stale type entries for files about to be recompiled —
        // handles cases where a source file previously defined multiple types
        // but now defines fewer
        for (String sourceFile : toRecompile) {
            state.removeTypesForSource(sourceFile);
        }

        allCompiled.addAll(toRecompile);
        var result = new TreeSet<Path>();
        for (String s : toRecompile) {
            result.add(Path.of(s));
        }
        return result;
    }

    // --- Annotation processor handling ---

    /**
     * Determines additional files to compile based on annotation processor classification.
     * Called during incremental builds when annotated sources are in the compile set.
     *
     * <ul>
     *   <li>ISOLATING: no extra files needed (default, current behavior works)</li>
     *   <li>AGGREGATING: all sources carrying the processor's trigger annotations</li>
     *   <li>UNKNOWN: all sources (conservative full rebuild)</li>
     * </ul>
     */
    private Set<Path> computeProcessorCascade() {
        if (processorClassification == null) {
            return Set.of();
        }

        // Collect annotation types from types we just compiled
        var compiledAnnotations = new TreeSet<String>();
        for (var entry : state.getTypes().entrySet()) {
            if (allCompiled.contains(state.sourceFileFor(entry.getKey()))) {
                compiledAnnotations.addAll(entry.getValue().annotationTypes());
            }
        }

        if (compiledAnnotations.isEmpty()) {
            return Set.of();
        }

        // Check if any compiled annotation triggers an AGGREGATING or UNKNOWN processor
        boolean hasUnknown = false;
        boolean hasAggregating = false;

        // Use worst-case classification from the processor path
        var allAnnotations = state.getAllAnnotationTypes();
        if (allAnnotations.isEmpty()) {
            return Set.of();
        }

        // Check if any processors on the path are UNKNOWN or AGGREGATING
        var classificationMap = processorClassification.getClassifications();
        for (var entry : classificationMap.entrySet()) {
            if (entry.getValue() == ProcessorType.UNKNOWN) {
                hasUnknown = true;
            } else if (entry.getValue() == ProcessorType.AGGREGATING) {
                hasAggregating = true;
            }
        }

        // If no processors are classified at all but processor path is set,
        // we can't know what annotations they handle — conservative approach
        if (classificationMap.isEmpty() && processorPath != null && !processorPath.isEmpty()) {
            hasUnknown = true;
        }

        var additionalFiles = new TreeSet<Path>();

        if (hasUnknown) {
            // UNKNOWN: recompile all source files
            for (Path sf : allSourceFiles) {
                if (!allCompiled.contains(sf.toString())) {
                    additionalFiles.add(sf);
                    allCompiled.add(sf.toString());
                }
            }
        } else if (hasAggregating) {
            // AGGREGATING: recompile all annotated sources
            Set<String> annotatedFiles = state.getSourceFilesWithAnnotations(allAnnotations);
            for (String sf : annotatedFiles) {
                if (!allCompiled.contains(sf)) {
                    additionalFiles.add(Path.of(sf));
                    allCompiled.add(sf);
                }
            }
        }
        // ISOLATING: no extra files needed

        return additionalFiles;
    }

    // --- External ABI tracking ---

    private Set<String> checkExternalAbiChanges() {
        var invalidated = new TreeSet<String>();
        Set<String> externalDeps = state.getExternalDependencies();
        if (externalDeps.isEmpty()) {
            return invalidated;
        }

        var resolver = createResolver();
        Map<String, String> currentFingerprints = resolver.resolve(externalDeps);
        Map<String, String> storedFingerprints = state.getExternalFingerprints();

        var changedExternalTypes = new TreeSet<String>();
        for (var entry : currentFingerprints.entrySet()) {
            String stored = storedFingerprints.get(entry.getKey());
            if (stored == null || !stored.equals(entry.getValue())) {
                changedExternalTypes.add(entry.getKey());
            }
        }

        if (!changedExternalTypes.isEmpty()) {
            for (String changedType : changedExternalTypes) {
                for (String consumer : state.getAllConsumers(changedType)) {
                    String sf = state.sourceFileFor(consumer);
                    if (sf != null) {
                        invalidated.add(sf);
                    }
                }
            }
        }

        state.setExternalFingerprints(currentFingerprints);
        state.setClasspathIdentities(resolver.computeCurrentJarIdentities());
        return invalidated;
    }

    private ExternalAbiResolver createResolver() {
        var resolver =
                new ExternalAbiResolver(classpathEntries != null ? classpathEntries : List.of(), reactorModulePaths);
        if (previousState != null) {
            resolver.setCachedState(previousState.getExternalFingerprints(), previousState.getClasspathIdentities());
        }
        return resolver;
    }

    // --- Utility ---

    private void expandSignatureCascade(String type, IncrementalState state, Set<String> result) {
        for (String consumer : state.getSignatureConsumers(type)) {
            if (result.add(consumer)) {
                expandSignatureCascade(consumer, state, result);
            }
        }
    }

    // TODO: inner classes use $ in file names (Foo$Bar.class) but qualified names use dots.
    // javac's ANALYZE fires for top-level types, so inner class names should not appear here,
    // but this needs verification for nested/local class edge cases.
    private void deleteClassFile(String qualifiedName) {
        Path classFile = outputDir.resolve(qualifiedName.replace('.', '/') + ".class");
        try {
            Files.deleteIfExists(classFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stale class file: " + classFile, e);
        }
    }

    private static Map<String, String> hashSourceFiles(List<Path> files) throws IOException {
        var hashes = new LinkedHashMap<String, String>();
        for (Path file : files) {
            hashes.put(file.toString(), sha256(Files.readAllBytes(file)));
        }
        return hashes;
    }

    private static String sha256(byte[] content) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content);
            var hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
