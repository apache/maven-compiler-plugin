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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Dependency-graph-driven incremental build engine, designed for embedding
 * in maven-compiler-plugin alongside the existing timestamp-based
 * {@code IncrementalBuild}.
 *
 * <p>The plugin drives compilation; this class determines <em>what</em> to
 * compile and performs post-compilation bytecode analysis to build the
 * class-level dependency graph. Typical usage:
 *
 * {@snippet :
 * var build = new GraphIncrementalBuild(outputDir);
 * build.setProcessorPath(processorPath);
 * build.setConfigHash(configHash);
 *
 * Set<Path> toCompile = build.initialize(allSourceFiles);
 *
 * while (!toCompile.isEmpty()) {
 *     compiler.compile(toCompile);      // any compiler, any mode
 *     toCompile = build.processCompiledClasses(toCompile);
 * }
 *
 * build.finish();
 * }
 *
 * <p>After each compilation pass, {@link #processCompiledClasses(Set)} scans
 * the freshly produced {@code .class} files, updates the dependency graph,
 * and returns any additional source files that must be compiled in the next
 * pass (cascade due to changed classes, or newly discovered dependencies).
 * The loop converges in at most 2–3 passes in practice.
 *
 * <p>The engine persists its state as {@code incremental-state} in the
 * {@code target/maven-status/maven-compiler-plugin/<outputDirName>/} directory (outside the class
 * output directory so it is not packaged into JARs).
 *
 * @see IncrementalState
 */
public class GraphIncrementalBuild {

    /** Prefix used to distinguish module-info entries from regular type entries in the state. */
    static final String MODULE_PREFIX = BytecodeAnalyzer.MODULE_PREFIX;

    private final Path outputDir;
    private final Path buildDir;
    private final Path stateFile;
    private List<Path> classpathEntries;
    private Set<Path> reactorModulePaths;
    private boolean abiTracking;
    private List<Path> processorPath;
    private ProcessorClassification processorClassification;
    private IncrementalState previousState;
    private IncrementalState state;

    /** Package-private accessor for tests. */
    IncrementalState getState() {
        return state;
    }

    private Map<String, String> sourceHashes;
    private Map<String, Long> sourceMtimes;
    private List<Path> allSourceFiles;
    private Set<String> allCompiled;
    private boolean fullBuild;
    private boolean useModulePrefixedPaths;
    private String configHash = "";
    private String rebuildCause;
    private int totalSources;
    /** Lazily populated on full builds; maps each output class file to its simple top-level class name. */
    private Map<Path, String> outputClassIndex;

    public GraphIncrementalBuild(Path outputDir) {
        this.outputDir = outputDir.toAbsolutePath();
        this.buildDir = this.outputDir.getParent() != null ? this.outputDir.getParent() : this.outputDir;
        // Store state outside the output directory so it is not included in the JAR.
        // Use the same maven-status convention as the timestamp-based strategy.
        // Include the output directory name (e.g. "classes", "test-classes") to avoid
        // collisions between compile and testCompile executions.
        String outputDirName = outputDir.getFileName().toString();
        Path mavenStatus = buildDir.resolve("maven-status").resolve("maven-compiler-plugin");
        this.stateFile = mavenStatus.resolve(outputDirName).resolve("incremental-state");
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
     * Enables ABI tracking mode ({@code abi} strategy). When {@code true}, the
     * engine computes ABI fingerprints and fine-grained {@code signatureDeps}/
     * {@code implementationDeps} for each compiled type. When {@code false}
     * (default, {@code graph} strategy), only class-level dependency references
     * are collected.
     */
    public void setAbiTracking(boolean abiTracking) {
        this.abiTracking = abiTracking;
    }

    /**
     * Sets the annotation processor classpath for processor classification.
     * Entries are scanned for {@code META-INF/maven/compiler/incremental.annotation.processors}
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
     * Sets a hash of compilation context configuration (e.g. compiler options
     * and module-info-patch files). If this hash differs from the previous
     * build, a full rebuild is triggered.
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
            rebuildCause = "compilation configuration changed (module-info-patch.maven or compiler options)";
            return initFullBuild(allSourceFiles);
        } else {
            return initIncrementalBuild(allSourceFiles);
        }
    }

    /**
     * Processes the {@code .class} files produced by the last compilation pass.
     * Scans each class file to extract the dependency graph, records changes,
     * and returns any additional source files that must be compiled in the next pass.
     *
     * <p>The cascade logic: any compiled class whose content changed (new or modified)
     * triggers recompilation of all source files that depend on it (signature or
     * implementation consumers).
     *
     * @param compiledSourceFiles the source files that were passed to the compiler in this round
     * @return additional source files to compile (may be empty when fixpoint is reached)
     * @throws IOException if reading {@code .class} files fails
     */
    public Set<Path> processCompiledClasses(Set<Path> compiledSourceFiles) throws IOException {
        // Reset the class index so it is rebuilt fresh for each compilation round
        outputClassIndex = null;

        // Scan .class files for the types produced from the compiled source files
        var results = new LinkedHashMap<String, SourceFileAnalysis>();
        for (Path sourceFile : compiledSourceFiles) {
            collectClassAnalyses(sourceFile, results);
        }

        // All compiled types cascade to their consumers (any change triggers recompilation)
        var changedTypes = new TreeSet<>(results.keySet());

        // Update incremental state with this round's results
        for (var entry : sourceHashes.entrySet()) {
            if (allCompiled.contains(entry.getKey())) {
                state.setSourceHash(entry.getKey(), entry.getValue());
            }
        }
        for (var result : results.values()) {
            String moduleName = useModulePrefixedPaths ? result.moduleName() : "";
            IncrementalState.TypeInfo typeInfo;
            if (abiTracking) {
                typeInfo = new IncrementalState.AbiTypeInfo(
                        result.sourceFile(),
                        result.classDeps(),
                        result.annotationTypes(),
                        moduleName,
                        result.signatureDeps(),
                        result.implementationDeps(),
                        result.abiFingerprint());
            } else {
                typeInfo = new IncrementalState.GraphTypeInfo(
                        result.sourceFile(), result.classDeps(), result.annotationTypes(), moduleName);
            }
            state.setType(result.qualifiedName(), typeInfo);
        }

        if (fullBuild || changedTypes.isEmpty()) {
            return Set.of();
        }

        // Detect module name changes — require a full rebuild
        if (previousState != null && hasModuleNameChanged(state, previousState)) {
            return forceFullRebuild();
        }

        // Cascade: find all consumers of changed types (both signature and implementation)
        var cascade = new TreeSet<>(changedTypes);
        for (String type : changedTypes) {
            expandSignatureCascade(type, state, cascade);
        }

        var additionalFiles = new TreeSet<Path>();
        for (String cascadedType : cascade) {
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
     * Scans the output directory for {@code .class} files produced from the given source file,
     * analyzes each one with {@link BytecodeAnalyzer}, and accumulates {@link SourceFileAnalysis}
     * records into {@code results}.
     *
     * <p>The source→class mapping is reconstructed by looking up types previously recorded for
     * this source file in the previous state, and by scanning the output directory for class
     * files whose name prefix matches the source file's simple name. This covers both primary
     * classes and inner/anonymous classes ({@code Foo$Bar.class}).
     */
    private void collectClassAnalyses(Path sourceFile, Map<String, SourceFileAnalysis> results) throws IOException {
        String sourceFilePath = sourceFile.toString();
        String simpleSourceName = sourceFile.getFileName().toString(); // e.g. "Model.java"

        // Map from class file path → pre-computed analysis (null if not yet analyzed).
        // Reuses the analysis from the package-dir walk to avoid double BytecodeAnalyzer.analyze calls.
        var classFileCache = new LinkedHashMap<Path, BytecodeAnalyzer.ClassAnalysis>();

        // Types previously tracked for this source file — their .class files may have moved
        if (previousState != null) {
            for (String type : previousState.getTypesFromSource(sourceFilePath)) {
                Path classFile = classFileFor(type, previousState.getType(type));
                if (Files.exists(classFile)) {
                    classFileCache.put(classFile, null);
                }
                // Also scan for inner classes (Foo$Bar.class etc.)
                var innerFiles = new TreeSet<Path>();
                addInnerClassFiles(classFile, innerFiles);
                for (Path inner : innerFiles) {
                    classFileCache.putIfAbsent(inner, null);
                }
            }
        }

        // Walk output directory entries for this source's simple name
        // (handles new types introduced in this compilation)
        Path packageDir = inferPackageDir(sourceFile);
        if (packageDir != null && Files.isDirectory(packageDir)) {
            try (var stream = Files.list(packageDir)) {
                stream.filter(p -> p.toString().endsWith(".class")).forEach(cf -> {
                    try {
                        // Match by SourceFile attribute — covers primary class, inner/anonymous
                        // classes (Foo$Bar.class), AND package-private secondary top-level classes
                        // (FooHelper in Foo.java), without cross-package simple-name collisions.
                        var a = BytecodeAnalyzer.analyze(cf);
                        if (simpleSourceName.equals(a.sourceFileName())) {
                            classFileCache.put(cf, a); // cache the analysis for reuse below
                        }
                    } catch (IOException e) {
                        // Log at debug level — a corrupted or inaccessible class file is silently
                        // skipped; the missing type entry will trigger a full rebuild next time.
                        System.getLogger(GraphIncrementalBuild.class.getName())
                                .log(System.Logger.Level.DEBUG, "Failed to analyze class file: {0}", cf);
                    }
                });
            }
        } else {
            // No previous state for this source (full build or new file in incremental build):
            // use the cached class index keyed by SourceFile attribute value (simple source name,
            // e.g. "Foo.java"). This correctly associates package-private secondary types and
            // avoids cross-package simple-name collisions.
            for (var entry : getFullBuildClassIndex().entrySet()) {
                if (simpleSourceName.equals(entry.getValue())) {
                    classFileCache.putIfAbsent(entry.getKey(), null);
                }
            }
        }

        // Special case: module-info.class — check both flat and module-prefixed locations
        if (sourceFile.getFileName().toString().equals("module-info.java")) {
            // Flat layout: outputDir/module-info.class
            Path flat = outputDir.resolve("module-info.class");
            if (Files.exists(flat)) {
                classFileCache.putIfAbsent(flat, null);
            }
            // MODULE_SOURCE layout: outputDir/<moduleName>/module-info.class
            if (useModulePrefixedPaths && previousState != null) {
                // Find the module name from previous state
                for (String type : previousState.getTypesFromSource(sourceFile.toString())) {
                    if (type.startsWith(MODULE_PREFIX)) {
                        String modName = type.substring(MODULE_PREFIX.length());
                        Path modInfo = outputDir.resolve(modName).resolve("module-info.class");
                        if (Files.exists(modInfo)) {
                            classFileCache.putIfAbsent(modInfo, null);
                        }
                        break;
                    }
                }
            }
        }

        // Final pass: analyze each class file (reusing cached analysis where available)
        for (var cacheEntry : classFileCache.entrySet()) {
            Path classFile = cacheEntry.getKey();
            if (!Files.exists(classFile)) {
                continue;
            }
            try {
                if (abiTracking) {
                    // Full ABI analysis: sig/impl deps + fingerprint
                    var analysis =
                            cacheEntry.getValue() != null ? cacheEntry.getValue() : BytecodeAnalyzer.analyze(classFile);
                    String sourceFilePath2 = analysis.isModuleInfo()
                            ? sourceFilePath
                            : resolveSourceFile(analysis.className(), sourceFilePath);
                    var sfa = new SourceFileAnalysis(
                            analysis.className(),
                            sourceFilePath2,
                            unionDeps(analysis.signatureTypes(), analysis.implementationTypes()),
                            analysis.signatureTypes(),
                            analysis.implementationTypes(),
                            analysis.abiFingerprint(),
                            analysis.abiCanonical(),
                            analysis.annotationTypes(),
                            analysis.moduleName());
                    results.put(analysis.className(), sfa);
                } else {
                    // Graph analysis: class-level deps only, no ABI fingerprint.
                    // Full analyze() is used because we still need className, moduleName,
                    // annotationTypes metadata; sig/impl deps are merged into classDeps.
                    var analysis =
                            cacheEntry.getValue() != null ? cacheEntry.getValue() : BytecodeAnalyzer.analyze(classFile);
                    String sourceFilePath2 = analysis.isModuleInfo()
                            ? sourceFilePath
                            : resolveSourceFile(analysis.className(), sourceFilePath);
                    Set<String> classDeps = unionDeps(analysis.signatureTypes(), analysis.implementationTypes());
                    var sfa = new SourceFileAnalysis(
                            analysis.className(),
                            sourceFilePath2,
                            classDeps,
                            Set.of(),
                            Set.of(),
                            "",
                            "",
                            analysis.annotationTypes(),
                            analysis.moduleName());
                    results.put(analysis.className(), sfa);
                }
            } catch (IOException e) {
                // Skip unreadable class files — they'll be caught at compile time
            }
        }
    }

    /** Returns the union of two sets (both may be empty). */
    private static Set<String> unionDeps(Set<String> a, Set<String> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        var result = new HashSet<>(a);
        result.addAll(b);
        return Set.copyOf(result);
    }

    private Map<Path, String> getFullBuildClassIndex() throws IOException {
        if (outputClassIndex == null) {
            outputClassIndex = new LinkedHashMap<>();
            if (Files.isDirectory(outputDir)) {
                try (var walk = Files.walk(outputDir)) {
                    for (Path cf :
                            (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                        try {
                            var analysis = BytecodeAnalyzer.analyze(cf);
                            // Use the SourceFile attribute value — correctly handles package-private
                            // secondary types in the same file (e.g. FooHelper in Foo.java → "Foo.java").
                            String sfName = analysis.sourceFileName();
                            if (!sfName.isEmpty()) {
                                outputClassIndex.put(cf, sfName);
                            }
                        } catch (IOException e) {
                            // best effort — skip unreadable class files
                        }
                    }
                }
            }
        }
        return outputClassIndex;
    }

    /** Returns the expected .class file path for a type, accounting for module-prefixed output dirs. */
    private Path classFileFor(String qualifiedName, IncrementalState.TypeInfo info) {
        String moduleName = info != null ? info.moduleName() : "";
        Path base = (useModulePrefixedPaths && !moduleName.isEmpty()) ? outputDir.resolve(moduleName) : outputDir;
        if (qualifiedName.startsWith(MODULE_PREFIX)) {
            return base.resolve("module-info.class");
        }
        return base.resolve(qualifiedName.replace('.', '/') + ".class");
    }

    private static void addInnerClassFiles(Path primaryClassFile, Set<Path> result) {
        if (!Files.exists(primaryClassFile)) {
            return;
        }
        Path dir = primaryClassFile.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        String prefix = primaryClassFile.getFileName().toString().replace(".class", "$");
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".class"))
                    .forEach(result::add);
        } catch (IOException e) {
            // Best effort
        }
    }

    /**
     * Infers the package directory in the output tree for the given source file,
     * based on the types previously recorded for it in the incremental state.
     * Accounts for module-prefixed output directories when {@code useModulePrefixedPaths} is true.
     */
    private Path inferPackageDir(Path sourceFile) {
        if (previousState == null) {
            return null;
        }
        String sourceFilePath = sourceFile.toString();
        for (String type : previousState.getTypesFromSource(sourceFilePath)) {
            if (!type.startsWith(MODULE_PREFIX)) {
                String pkg = type.contains(".")
                        ? type.substring(0, type.lastIndexOf('.')).replace('.', '/')
                        : "";
                // Determine base dir: for module-prefixed output, classes live under outputDir/<module>/
                var info = previousState.getType(type);
                String moduleName = (useModulePrefixedPaths && info != null) ? info.moduleName() : "";
                Path base = (!moduleName.isEmpty()) ? outputDir.resolve(moduleName) : outputDir;
                return pkg.isEmpty() ? base : base.resolve(pkg);
            }
        }
        return null;
    }

    /** Returns the source file to associate with a class, preferring the known path if available. */
    private String resolveSourceFile(String className, String defaultSourceFile) {
        if (state != null) {
            String sf = state.sourceFileFor(className);
            if (sf != null) {
                return sf;
            }
        }
        return defaultSourceFile;
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
        if (abiTracking) {
            AbiManifest.write(buildDir.resolve(AbiManifest.FILENAME), state.getAllAbiFingerprints());
        }
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
        state = new IncrementalState();

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
        var causes = new ArrayList<String>();
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

    // --- Utility ---

    private static boolean hasModuleNameChanged(IncrementalState current, IncrementalState previous) {
        var currentModules = current.getTypes().keySet().stream()
                .filter(k -> k.startsWith(MODULE_PREFIX))
                .collect(Collectors.toSet());
        var previousModules = previous.getTypes().keySet().stream()
                .filter(k -> k.startsWith(MODULE_PREFIX))
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

    private void expandSignatureCascade(String startType, IncrementalState state, Set<String> result) {
        var worklist = new ArrayDeque<String>();
        worklist.add(startType);
        while (!worklist.isEmpty()) {
            String type = worklist.poll();
            for (String consumer : state.getSignatureConsumers(type)) {
                if (result.add(consumer)) {
                    worklist.add(consumer);
                }
            }
        }
    }

    private void deleteClassFile(String qualifiedName, IncrementalState.TypeInfo info) {
        String moduleName = info != null ? info.moduleName() : "";
        Path baseDir = moduleName.isEmpty() ? outputDir : outputDir.resolve(moduleName);

        if (qualifiedName.startsWith(MODULE_PREFIX)) {
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
}
