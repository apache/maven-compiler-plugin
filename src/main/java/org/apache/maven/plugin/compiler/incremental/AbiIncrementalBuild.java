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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
 * abi.setProcessorPath(processorPath);
 * abi.setConfigHash(configHash);
 *
 * Set<Path> toCompile = abi.initialize(allSourceFiles);
 *
 * while (!toCompile.isEmpty()) {
 *     JavacTask task = (JavacTask) compiler.getTask(..., toCompile, ...);
 *     abi.attachTo(task);
 *     if (!task.call()) { abi.invalidate(); break; }
 *     toCompile = abi.processRound();
 * }
 *
 * abi.finish();
 * }</pre>
 *
 * <p>See {@link org.apache.maven.plugin.compiler.ToolExecutor} for the full
 * integration including module path detection and multi-release handling.
 *
 * <p>The engine persists its state as {@code .incremental-state} inside the
 * output directory and writes an {@link AbiManifest} ({@code .abi-fingerprints})
 * in the build directory for downstream reactor modules.
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
    private Map<String, Long> sourceMtimes;
    private List<Path> allSourceFiles;
    private Set<String> allCompiled;
    private CompilationAnalyzer currentAnalyzer;
    private boolean fullBuild;
    private boolean useModulePrefixedPaths;
    private String configHash = "";
    private String rebuildCause;
    private int totalSources;

    public AbiIncrementalBuild(Path outputDir) {
        this.outputDir = outputDir;
        this.buildDir = outputDir.getParent() != null ? outputDir.getParent() : outputDir;
        this.stateFile = outputDir.resolve(".incremental-state");
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
     * Indicates that class files are written under module-name subdirectories
     * of the output directory (MODULE_SOURCE hierarchy). When set, stored module
     * names are used as path prefixes when deleting class files.
     */
    public void setUseModulePrefixedPaths(boolean useModulePrefixedPaths) {
        this.useModulePrefixedPaths = useModulePrefixedPaths;
    }

    /**
     * Sets a hash of compilation context configuration (e.g. module-info-patch
     * files). If this hash differs from the previous build, a full rebuild
     * is triggered.
     */
    public void setConfigHash(String hash) {
        this.configHash = hash != null ? hash : "";
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
        previousState = IncrementalState.load(stateFile);
        sourceMtimes = new LinkedHashMap<>();
        sourceHashes = hashSourceFiles(allSourceFiles, previousState, sourceMtimes);

        if (previousState == null) {
            rebuildCause = "no previous build state";
            return initFullBuild(allSourceFiles);
        } else if (!configHash.equals(previousState.getConfigHash())) {
            rebuildCause = "compilation configuration changed (module-info-patch.maven)";
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
            String moduleName = useModulePrefixedPaths ? result.moduleName() : "";
            state.setType(
                    result.qualifiedName(),
                    new IncrementalState.TypeInfo(
                            result.sourceFile(),
                            result.abiFingerprint(),
                            result.signatureDeps(),
                            result.implementationDeps(),
                            result.annotationTypes(),
                            moduleName));
        }

        if (fullBuild || abiChanged.isEmpty()) {
            return Set.of();
        }

        // Detect module name changes — these require a full rebuild because
        // all types in the module were compiled under the old module context
        if (previousState != null && hasModuleNameChanged(state, previousState)) {
            return forceFullRebuild();
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

        // Persist source mtimes for the next build's mtime-first optimization
        for (var entry : sourceMtimes.entrySet()) {
            state.setSourceMtime(entry.getKey(), entry.getValue());
        }

        state.setConfigHash(configHash);
        state.save(stateFile);
        AbiManifest.write(buildDir.resolve(AbiManifest.FILENAME), state.getAllAbiFingerprints());
    }

    /**
     * Invalidates the incremental state after a compilation failure.
     * Deletes the state file so the next build starts fresh, avoiding
     * a broken output directory where class files were deleted but
     * not regenerated.
     */
    public void invalidate() {
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException e) {
            // Best effort — a missing state file just triggers a full rebuild
        }
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

    /**
     * Returns a human-readable description of why recompilation was triggered,
     * or {@code null} if no rebuild is needed.
     */
    public String getRebuildCause() {
        return rebuildCause;
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

        // Build rebuild cause description
        var causes = new java.util.ArrayList<String>();
        if (!changedFiles.isEmpty()) {
            causes.add(changedFiles.size() + " changed");
        }
        if (!newFiles.isEmpty()) {
            causes.add(newFiles.size() + " new");
        }
        if (!deletedFiles.isEmpty()) {
            causes.add(deletedFiles.size() + " deleted");
        }
        if (!externallyInvalidated.isEmpty()) {
            causes.add(externallyInvalidated.size() + " invalidated by dependency changes");
        }
        rebuildCause = String.join(", ", causes);

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
                deleteClassFile(type, previousState.getType(type));
            }
            state.removeSource(deleted);
        }

        // Clear stale type entries and class files for files about to be recompiled —
        // handles cases where a source file previously defined multiple types
        // (including inner/nested classes) but now defines fewer
        for (String sourceFile : toRecompile) {
            for (String type : previousState.getTypesFromSource(sourceFile)) {
                deleteClassFile(type, previousState.getType(type));
            }
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

    private static boolean hasModuleNameChanged(IncrementalState current, IncrementalState previous) {
        var currentModules = current.getTypes().keySet().stream()
                .filter(k -> k.startsWith(CompilationAnalyzer.MODULE_PREFIX))
                .collect(Collectors.toSet());
        var previousModules = previous.getTypes().keySet().stream()
                .filter(k -> k.startsWith(CompilationAnalyzer.MODULE_PREFIX))
                .collect(Collectors.toSet());
        return !currentModules.equals(previousModules);
    }

    private Set<Path> forceFullRebuild() {
        // Delete old class files for types not yet recompiled
        for (var entry : previousState.getTypes().entrySet()) {
            String sf = entry.getValue().sourceFile();
            if (sf != null && !allCompiled.contains(sf)) {
                deleteClassFile(entry.getKey(), entry.getValue());
            }
        }
        var additionalFiles = new TreeSet<Path>();
        for (Path sf : allSourceFiles) {
            if (!allCompiled.contains(sf.toString())) {
                additionalFiles.add(sf);
                allCompiled.add(sf.toString());
            }
        }
        return additionalFiles;
    }

    private void expandSignatureCascade(String type, IncrementalState state, Set<String> result) {
        for (String consumer : state.getSignatureConsumers(type)) {
            if (result.add(consumer)) {
                expandSignatureCascade(consumer, state, result);
            }
        }
    }

    private void deleteClassFile(String qualifiedName, IncrementalState.TypeInfo info) {
        String moduleName = info != null ? info.moduleName() : "";
        Path baseDir = moduleName.isEmpty() ? outputDir : outputDir.resolve(moduleName);

        if (qualifiedName.startsWith(CompilationAnalyzer.MODULE_PREFIX)) {
            Path moduleInfoClass = baseDir.resolve("module-info.class");
            try {
                Files.deleteIfExists(moduleInfoClass);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete module-info.class", e);
            }
            return;
        }
        Path classFile = baseDir.resolve(qualifiedName.replace('.', '/') + ".class");
        try {
            Files.deleteIfExists(classFile);
            // Also clean up inner/nested class files (Foo$Bar.class, Foo$Bar$Baz.class, etc.)
            // These are generated by javac alongside the top-level class file.
            Path classDir = classFile.getParent();
            String simplePrefix = classFile.getFileName().toString().replace(".class", "$");
            if (Files.isDirectory(classDir)) {
                try (var stream = Files.list(classDir)) {
                    stream.filter(p -> p.getFileName().toString().startsWith(simplePrefix)
                                    && p.getFileName().toString().endsWith(".class"))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ex) {
                                    // Best effort — stale inner class files are harmless
                                }
                            });
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stale class file: " + classFile, e);
        }
    }

    /**
     * Computes SHA-256 hashes for the given source files, using an mtime-first
     * short-circuit: if a file's last-modified time matches the value stored in
     * {@code prev}, its previously stored hash is reused without reading the
     * file's content.  This avoids redundant I/O on the common no-change case,
     * particularly beneficial for large source trees.
     *
     * @param files    the source files to process
     * @param prev     previous build's state (may be {@code null})
     * @param mtimes   output map populated with each file's observed mtime (millis)
     * @return map from file path string to SHA-256 content hash
     */
    private static Map<String, String> hashSourceFiles(
            List<Path> files, IncrementalState prev, Map<String, Long> mtimes) throws IOException {
        var hashes = new LinkedHashMap<String, String>();
        for (Path file : files) {
            String path = file.toString();
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long mtime = attrs.lastModifiedTime().toMillis();
            mtimes.put(path, mtime);

            if (prev != null) {
                var storedMtime = prev.getSourceMtime(path);
                if (storedMtime.isPresent() && storedMtime.getAsLong() == mtime) {
                    String storedHash = prev.getSourceHash(path);
                    if (storedHash != null) {
                        // mtime unchanged — reuse stored hash, skip reading file content
                        hashes.put(path, storedHash);
                        continue;
                    }
                }
            }

            hashes.put(path, Sha256.hash(Files.readAllBytes(file)));
        }
        return hashes;
    }
}
