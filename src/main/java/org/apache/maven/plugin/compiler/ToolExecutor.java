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

import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.PathType;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.MavenException;

/**
 * A task which configures and executes a Java tool such as the Java compiler.
 * This class takes a snapshot of the information provided in the <abbr>MOJO</abbr>.
 * Then, it collects additional information such as the source files and the dependencies.
 * The set of source files to compile can optionally be filtered for keeping only the files
 * that changed since the last build with the {@linkplain #applyIncrementalBuild incremental build}.
 *
 * <h2>Thread safety</h2>
 * This class is not thread-safe. However, it is independent of the {@link AbstractCompilerMojo} instance
 * given in argument to the constructor and to the {@linkplain #applyIncrementalBuild incremental build}.
 * After all methods with an {@link AbstractCompilerMojo} argument have been invoked, {@code ToolExecutor}
 * can safety be used in a background thread for launching the compilation (but must still be used by only
 * only thread at a time).
 *
 * @author Martin Desruisseaux
 */
public class ToolExecutor {
    /**
     * The locale for diagnostics, or {@code null} for the platform default.
     */
    private static final Locale LOCALE = null;

    /**
     * The character encoding of source files, or {@code null} for the platform default encoding.
     *
     * @see AbstractCompilerMojo#encoding
     */
    protected final Charset encoding;

    /**
     * The root directories of the Java source files to compile, excluding empty directories.
     * The list needs to be modifiable for allowing the addition of generated source directories.
     *
     * @see AbstractCompilerMojo#compileSourceRoots
     */
    final List<SourceDirectory> sourceDirectories;

    /**
     * The directories where to write generated source files.
     * This set is either empty or a singleton.
     *
     * @see AbstractCompilerMojo#proc
     * @see StandardLocation#SOURCE_OUTPUT
     */
    protected final Set<Path> generatedSourceDirectories;

    /**
     * All source files to compile. May include files for many Java modules and many Java releases.
     * When the compilation will be executed, those files will be grouped in compilation units where
     * each unit will be the source files for one particular Java release.
     *
     * @see StandardLocation#SOURCE_PATH
     * @see StandardLocation#MODULE_SOURCE_PATH
     */
    private List<SourceFile> sourceFiles;

    /**
     * Whether the project contains or is assumed to contain a {@code module-info.java} file.
     * If the user specified explicitly whether the project is a modular or a classpath JAR,
     * then this flag is set to the user's specification without verification.
     * Otherwise, this flag is determined by scanning the list of source files.
     */
    protected final boolean hasModuleDeclaration;

    /**
     * The result of resolving the dependencies, or {@code null} if not available or not needed.
     * For example, this field may be null if the constructor found no file to compile,
     * so there is no need to fetch dependencies.
     */
    final DependencyResolverResult dependencyResolution;

    /**
     * All dependencies grouped by the path types where to place them, together with the modules to patch.
     * The path type can be the class-path, module-path, annotation processor path, patched path, <i>etc.</i>
     * Some path types include a module name.
     *
     * <h4>Modifications during the build of multi-release project</h4>
     * When building a multi-release project, values associated to {@code --class-path}, {@code --module-path}
     * or {@code --patch-module} options are modified every time that {@code ToolExecutor} compiles for a new
     * Java release. The output directories for the previous Java releases are inserted as the first elements
     * of their lists, or new entries are created if no list existed previously for an option.
     *
     * @see #dependencies(PathType)
     * @see #prependDependency(PathType, Path)
     */
    private final Map<PathType, Collection<Path>> dependencies;

    /**
     * The destination directory (or class output directory) for class files.
     * This directory will be given to the {@code -d} Java compiler option
     * when compiling the classes for the base Java release.
     *
     * @see AbstractCompilerMojo#getOutputDirectory()
     */
    protected final Path outputDirectory;

    /**
     * Configuration of the incremental compilation.
     *
     * @see AbstractCompilerMojo#incrementalCompilation
     * @see AbstractCompilerMojo#useIncrementalCompilation
     */
    private final EnumSet<IncrementalBuild.Aspect> incrementalBuildConfig;

    /**
     * The incremental build to save if the build succeed.
     * In case of failure, the cached information will be unchanged.
     */
    private IncrementalBuild incrementalBuild;

    /**
     * Whether only a subset of the files will be compiled. This flag can be {@code true} only when
     * incremental build is enabled and detected that some files do not need to be recompiled.
     */
    private boolean isPartialBuild;

    /**
     * Where to send the compilation warning (never {@code null}). If a null value was specified
     * to the constructor, then this listener sends the warnings to the Maven {@linkplain #logger}.
     */
    protected final DiagnosticListener<? super JavaFileObject> listener;

    /**
     * The Maven logger for reporting information or warnings to the user.
     * Used for messages emitted directly by the Maven compiler plugin.
     * Not necessarily used for messages emitted by the Java compiler.
     *
     * <h4>Thread safety</h4>
     * This logger should be thread-safe if this {@code ToolExecutor} is executed in a background thread.
     *
     * @see AbstractCompilerMojo#logger
     */
    protected final Log logger;

    /**
     * The sources to write in the {@code target/javac.args} debug files.
     * This list contains only the sources for which the compiler has been executed, successfully or not.
     * If a compilation error occurred, the last element in the list contains the sources where the error occurred.
     */
    final List<SourcesForRelease> sourcesForDebugFile;

    /**
     * Creates a new task by taking a snapshot of the current configuration of the given <abbr>MOJO</abbr>.
     * This constructor creates the {@linkplain #outputDirectory output directory} if it does not already exist.
     *
     * @param mojo the <abbr>MOJO</abbr> from which to take a snapshot
     * @param listener where to send compilation warnings, or {@code null} for the Maven logger
     * @throws MojoException if this constructor identifies an invalid parameter in the <abbr>MOJO</abbr>
     * @throws IOException if an error occurred while creating the output directory or scanning the source directories
     * @throws MavenException if an error occurred while fetching dependencies
     *
     * @see AbstractCompilerMojo#createExecutor(DiagnosticListener)
     */
    @SuppressWarnings("deprecation")
    protected ToolExecutor(final AbstractCompilerMojo mojo, DiagnosticListener<? super JavaFileObject> listener)
            throws IOException {

        logger = mojo.logger;
        if (listener == null) {
            Path root = mojo.project.getRootDirectory();
            listener = new DiagnosticLogger(logger, mojo.messageBuilderFactory, LOCALE, root);
        }
        this.listener = listener;
        encoding = mojo.charset();
        incrementalBuildConfig = mojo.incrementalCompilationConfiguration();
        outputDirectory = Files.createDirectories(mojo.getOutputDirectory());
        sourceDirectories = mojo.getSourceDirectories(outputDirectory);
        dependencies = new LinkedHashMap<>();
        sourcesForDebugFile = new ArrayList<>();
        /*
         * Get the source files and whether they include or are assumed to include `module-info.java`.
         * Note that we perform this step after processing compiler arguments, because this block may
         * skip the build if there is no source code to compile. We want arguments to be verified first
         * in order to warn about possible configuration problems.
         */
        if (incrementalBuildConfig.contains(IncrementalBuild.Aspect.MODULES)) {
            boolean hasNoFileMatchers = mojo.hasNoFileMatchers();
            for (SourceDirectory root : sourceDirectories) {
                if (root.moduleName == null) {
                    throw new CompilationFailureException("The <incrementalCompilation> value can be \"modules\" "
                            + "only if all source directories are Java modules.");
                }
                hasNoFileMatchers &= root.includes.isEmpty() && root.excludes.isEmpty();
            }
            if (!hasNoFileMatchers) {
                throw new CompilationFailureException("Include and exclude filters cannot be specified "
                        + "when <incrementalCompilation> is set to \"modules\".");
            }
            hasModuleDeclaration = true;
            sourceFiles = List.of();
        } else {
            /*
             * The order of the two next lines matter for initialization of `SourceDirectory.moduleInfo`.
             * This initialization is done indirectly when the walk invokes the `SourceFile` constructor,
             * which in turn invokes `SourceDirectory.visit(Path)`.
             */
            sourceFiles = new PathFilter(mojo).walkSourceFiles(sourceDirectories);
            hasModuleDeclaration = mojo.hasModuleDeclaration(sourceDirectories);
            if (sourceFiles.isEmpty()) {
                generatedSourceDirectories = Set.of();
                dependencyResolution = null;
                return;
            }
        }
        /*
         * Get the dependencies. If the module-path contains any automatic (filename-based)
         * dependency and the MOJO is compiling the main code, then a warning will be logged.
         */
        dependencyResolution = mojo.resolveDependencies(hasModuleDeclaration);
        if (dependencyResolution != null) {
            dependencies.putAll(dependencyResolution.getDispatchedPaths());
        }
        mojo.resolveProcessorPathEntries(dependencies);
        mojo.amendincrementalCompilation(incrementalBuildConfig, dependencies.keySet());
        generatedSourceDirectories = mojo.addGeneratedSourceDirectory(dependencies.keySet());
        copyDependencyValues();
    }

    /**
     * Copies all values of the dependency map in unmodifiable lists.
     * This is used for creating a snapshot of the current state of the dependency map.
     */
    private void copyDependencyValues() {
        dependencies.entrySet().forEach((entry) -> entry.setValue(List.copyOf(entry.getValue())));
    }

    /**
     * Returns the output directory of the main classes if they were compiled in a previous Maven phase.
     * This method shall always return {@code null} when compiling to main code. The return value can be
     * non-null only when compiling the test classes, in which case the returned path is the directory to
     * prepend to the class-path or module-path before to compile the classes managed by this executor.
     *
     * @return the directory to prepend to the class-path or module-path, or {@code null} if none
     */
    Path getOutputDirectoryOfPreviousPhase() {
        return null;
    }

    /**
     * Returns the directory of the classes compiled for the specified module.
     * If the project is multi-release, this method returns the directory for the base version.
     *
     * <p>This is normally a sub-directory of the same name as the module name.
     * However, when building tests for a project which is both multi-release and multi-module,
     * the directory may exist only for a target Java version higher than the base version.</p>
     *
     * @param outputDirectory the output directory which is the root of modules
     * @param moduleName the name of the module for which the class directory is desired
     * @return directories of classes for the given module
     */
    Path resolveModuleOutputDirectory(Path outputDirectory, String moduleName) {
        return outputDirectory.resolve(moduleName);
    }

    /**
     * Name of the module when using package hierarchy, or {@code null} if not applicable.
     * This is used for setting {@code --patch-module} option during compilation of tests.
     * This field is null in a class-path project or in a multi-module project.
     *
     * <p>This information is used for compatibility with the Maven 3 way to build a modular project.
     * It is recommended to use the {@code <sources>} element instead. We may remove this method in a
     * future version if we abandon compatibility with the Maven 3 way to build modular projects.</p>
     *
     * @deprecated Declare modules in {@code <source>} elements instead.
     */
    @Deprecated(since = "4.0.0")
    String moduleNameFromPackageHierarchy() {
        return null;
    }

    /**
     * {@return whether a release version is specified for all sources}
     */
    final boolean isReleaseSpecifiedForAll() {
        for (SourceDirectory source : sourceDirectories) {
            if (source.release == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Filters the source files to recompile, or cleans the output directory if everything should be rebuilt.
     * If the directory structure of the source files has changed since the last build,
     * or if a compiler option changed, or if a dependency changed,
     * then this method keeps all source files and cleans the {@linkplain #outputDirectory output directory}.
     * Otherwise, the source files that did not changed since the last build are removed from the list of sources
     * to compile. If all source files have been removed, then this method returns {@code false} for notifying the
     * caller that it can skip the build.
     *
     * <p>If this method is invoked many times, all invocations after this first one have no effect.</p>
     *
     * @param mojo the <abbr>MOJO</abbr> from which to take the incremental build configuration
     * @param configuration the options which should match the options used during the last build
     * @throws IOException if an error occurred while accessing the cache file or walking through the directory tree
     * @return whether there is at least one file to recompile
     */
    public boolean applyIncrementalBuild(final AbstractCompilerMojo mojo, final Options configuration)
            throws IOException {
        final boolean checkSources = incrementalBuildConfig.contains(IncrementalBuild.Aspect.SOURCES);
        final boolean checkClasses = incrementalBuildConfig.contains(IncrementalBuild.Aspect.CLASSES);
        final boolean checkDepends = incrementalBuildConfig.contains(IncrementalBuild.Aspect.DEPENDENCIES);
        final boolean checkOptions = incrementalBuildConfig.contains(IncrementalBuild.Aspect.OPTIONS);
        if (checkSources | checkClasses | checkDepends | checkOptions) {
            incrementalBuild =
                    new IncrementalBuild(mojo, sourceFiles, checkSources, configuration, incrementalBuildConfig);
            String causeOfRebuild = null;
            if (checkSources) {
                // Should be first, because this method deletes output files of removed sources.
                causeOfRebuild = incrementalBuild.inputFileTreeChanges();
            }
            if (checkClasses && causeOfRebuild == null) {
                causeOfRebuild = incrementalBuild.markNewOrModifiedSources();
            }
            if (checkDepends && causeOfRebuild == null) {
                List<String> fileExtensions = mojo.fileExtensions;
                causeOfRebuild = incrementalBuild.dependencyChanges(dependencies.values(), fileExtensions);
            }
            if (checkOptions && causeOfRebuild == null) {
                causeOfRebuild = incrementalBuild.optionChanges();
            }
            if (causeOfRebuild != null) {
                if (!sourceFiles.isEmpty()) { // Avoid misleading message such as "all sources changed".
                    logger.info(causeOfRebuild);
                }
            } else {
                isPartialBuild = true;
                sourceFiles = incrementalBuild.getModifiedSources();
                if (IncrementalBuild.isEmptyOrIgnorable(sourceFiles)) {
                    incrementalBuildConfig.clear(); // Prevent this method to be executed twice.
                    logger.info("Nothing to compile - all classes are up to date.");
                    sourceFiles = List.of();
                    return false;
                } else {
                    int n = sourceFiles.size();
                    var sb = new StringBuilder("Compiling ").append(n).append(" modified source file");
                    if (n > 1) {
                        sb.append('s'); // Make plural.
                    }
                    logger.info(sb.append('.'));
                }
            }
            if (!(checkSources | checkDepends | checkOptions)) {
                incrementalBuild.deleteCache();
                incrementalBuild = null;
            }
        }
        incrementalBuildConfig.clear(); // Prevent this method to be executed twice.
        return true;
    }

    /**
     * {@return a modifiable collection of paths to all dependencies of the given type}
     * The returned collection is intentionally live: elements can be added or removed
     * from the collection for changing the state of this executor.
     *
     * @param  pathType  type of path for which to get the dependencies
     */
    protected Deque<Path> dependencies(PathType pathType) {
        return (Deque<Path>) dependencies.compute(pathType, (key, paths) -> {
            if (paths == null) {
                return new ArrayDeque<>();
            } else if (paths instanceof ArrayDeque<Path> deque) {
                return deque;
            } else {
                var copy = new ArrayDeque<Path>(paths.size() + 4); // Anticipate the addition of new elements.
                copy.addAll(paths);
                return copy;
            }
        });
    }

    /**
     * Dispatches sources and dependencies on the kind of paths determined by {@code DependencyResolver}.
     * The targets may be class-path, module-path, annotation processor class-path/module-path, <i>etc</i>.
     *
     * @param fileManager the file manager where to set the dependency paths
     */
    private void setDependencyPaths(final StandardJavaFileManager fileManager) throws IOException {
        final var unresolvedPaths = new ArrayList<Path>();
        for (Map.Entry<PathType, Collection<Path>> entry : dependencies.entrySet()) {
            Collection<Path> paths = entry.getValue();
            PathType key = entry.getKey();
            if (key instanceof JavaPathType type) {
                /*
                 * Dependency to a JAR file (usually).
                 * Placed on: --class-path, --module-path.
                 */
                Optional<JavaFileManager.Location> location = type.location();
                if (location.isPresent()) { // Cannot use `Optional.ifPresent(…)` because of checked IOException.
                    var value = location.get();
                    if (value == StandardLocation.CLASS_PATH) {
                        if (isPartialBuild && !hasModuleDeclaration) {
                            /*
                             * From https://docs.oracle.com/en/java/javase/24/docs/specs/man/javac.html:
                             * "When compiling code for one or more modules, the class output directory will
                             * automatically be checked when searching for previously compiled classes.
                             * When not compiling for modules, for backwards compatibility, the directory is not
                             * automatically checked for previously compiled classes, and so it is recommended to
                             * specify the class output directory as one of the locations on the user class path,
                             * using the --class-path option or one of its alternate forms."
                             */
                            paths = new ArrayDeque<>(paths);
                            paths.add(outputDirectory);
                            entry.setValue(paths);
                        }
                    }
                    fileManager.setLocationFromPaths(value, paths);
                    continue;
                }
            } else if (key instanceof JavaPathType.Modular type) {
                /*
                 * Main code to be tested by the test classes. This is handled as a "dependency".
                 * Placed on: --patch-module-path.
                 */
                Optional<JavaFileManager.Location> location = type.rawType().location();
                if (location.isPresent()) {
                    fileManager.setLocationForModule(location.get(), type.moduleName(), paths);
                    continue;
                }
            }
            unresolvedPaths.addAll(paths);
        }
        if (!unresolvedPaths.isEmpty()) {
            var sb = new StringBuilder("Cannot determine where to place the following artifacts:");
            for (Path p : unresolvedPaths) {
                sb.append(System.lineSeparator()).append(" - ").append(p);
            }
            logger.warn(sb);
        }
    }

    /**
     * Inserts the given path as the first element of the list of paths of the given type.
     * The main purpose of this method is during the build of a multi-release project,
     * for adding the output directory of the code targeting the previous Java release
     * before to compile the code targeting the next Java release. In this context,
     * the {@code type} argument usually identifies a {@code --class-path},
     * {@code --module-path} or {@code --patch-module} option.
     *
     * @param  pathType type of path for which to add an element
     * @param  first the path to put first
     * @return the new paths for the given type, as a modifiable list
     */
    protected Deque<Path> prependDependency(final PathType pathType, final Path first) {
        Deque<Path> paths = dependencies(pathType);
        paths.addFirst(first);
        return paths;
    }

    /**
     * Removes the first <var>n</var> elements of the given collection.
     * This is used for removing {@code --patch-module} items that were added as source directories.
     * The callers should replace the removed items by the output directory of these source files.
     *
     * @param paths  the paths from which to remove the first elements
     * @param count  number of elements to remove, or {@code null} if none
     * @return whether at least one item has been removed
     */
    private static boolean removeFirsts(Deque<Path> paths, Integer count) {
        boolean changed = false;
        if (count != null) {
            for (int i = count; --i >= 0; ) {
                changed |= (paths.removeFirst() != null);
            }
        }
        return changed;
    }

    /**
     * Ensures that the given value is non-null, replacing null values by the latest version.
     */
    private static SourceVersion nonNullOrLatest(SourceVersion release) {
        return (release != null) ? release : SourceVersion.latest();
    }

    /**
     * Groups all sources files first by Java release versions, then by module names.
     * The elements are sorted in the order of {@link SourceVersion} enumeration values,
     * with null version sorted last on the assumption that they will be for the latest
     * version supported by the runtime environment.
     *
     * @return the given sources grouped by Java release versions and module names
     */
    private Collection<SourcesForRelease> groupByReleaseAndModule() {
        var result = new EnumMap<SourceVersion, SourcesForRelease>(SourceVersion.class);
        for (SourceDirectory directory : sourceDirectories) {
            /*
             * We need an entry for every versions even if there is no source to compile for a version.
             * This is needed for configuring the classpath in a consistent way, for example with the
             * output directory of previous version even if we skipped the compilation of that version.
             */
            SourcesForRelease unit = result.computeIfAbsent(
                    nonNullOrLatest(directory.release),
                    (release) -> new SourcesForRelease(directory.release)); // Intentionally ignore the key.
            String moduleName = directory.moduleName;
            if (moduleName == null || moduleName.isBlank()) {
                moduleName = "";
            }
            unit.roots.computeIfAbsent(moduleName, (key) -> new LinkedHashSet<Path>());
        }
        for (SourceFile source : sourceFiles) {
            result.get(nonNullOrLatest(source.directory.release)).add(source);
        }
        return result.values();
    }

    /**
     * Creates the file manager which will be used by the compiler.
     * This method does not configure the locations (sources, dependencies, <i>etc.</i>).
     * Locations will be set by {@link #compile(JavaCompiler, Options, Writer)} on the
     * file manager returned by this method.
     *
     * @param compiler the compiler
     * @param workaround whether to apply {@link WorkaroundForPatchModule}
     * @return the file manager to use
     */
    private StandardJavaFileManager createFileManager(JavaCompiler compiler, boolean workaround) {
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(listener, LOCALE, encoding);
        if (WorkaroundForPatchModule.ENABLED && workaround && !(compiler instanceof ForkedTool)) {
            fileManager = new WorkaroundForPatchModule(fileManager);
        }
        return fileManager;
    }

    /**
     * Checks if there are no sources to compile and handles that case.
     * When there are no sources, this method cleans up the output directory and logs a message.
     *
     * @return {@code true} if there are no sources to compile, {@code false} if there are sources
     */
    private boolean noSourcesToCompile() {
        sourcesForDebugFile.clear();
        if (sourceFiles.isEmpty()) {
            String message = "No sources to compile.";
            try {
                Files.delete(outputDirectory);
            } catch (DirectoryNotEmptyException e) {
                message += " However, the output directory is not empty.";
            } catch (IOException e) {
                // Directory might not exist or have other issues - log at debug level
                logger.debug("Could not delete output directory: %s (ignored)".formatted(e.getMessage()));
            }
            logger.info(message);
            return true;
        }
        if (logger.isDebugEnabled()) {
            int n = sourceFiles.size();
            @SuppressWarnings("checkstyle:MagicNumber")
            var sb = new StringBuilder(n * 40).append("The source files to compile are:");
            for (SourceFile file : sourceFiles) {
                sb.append(System.lineSeparator()).append("    ").append(file);
            }
            logger.debug(sb);
        }
        return false;
    }

    /**
     * Initializes the file manager with dependency paths and generated source directories.
     *
     * @param fileManager the file manager to initialize
     * @throws IOException if an error occurred while setting locations
     */
    private void initializeFileManager(StandardJavaFileManager fileManager) throws IOException {
        setDependencyPaths(fileManager);
        if (!generatedSourceDirectories.isEmpty()) {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, generatedSourceDirectories);
        }
    }

    /**
     * Performs post-compilation tasks such as writing incremental build cache.
     *
     * @throws IOException if an error occurred while writing the cache
     */
    private void finalizeCompilation() throws IOException {
        if (incrementalBuild != null) {
            incrementalBuild.writeCache();
            incrementalBuild = null;
        }
    }

    /**
     * The type of project being compiled, determined by whether it uses classpath or module-path.
     * These are mutually exclusive - a project cannot be both classpath and modular.
     */
    private enum ProjectType {
        /** Non-modular project using classpath. This is the default. */
        CLASSPATH,
        /** Modular project using module-path. */
        MODULAR
    }

    /**
     * Holds both the stable context and mutable state needed during compilation.
     * The context (compiler, fileManager, etc.) is set once at construction.
     * The state (output directories, module tracking) is updated during compilation.
     *
     * <p>This is a non-static inner class to allow access to {@link ToolExecutor} instance members
     * such as {@link #dependencies(PathType)}, {@link #prependDependency(PathType, Path)},
     * {@link #outputDirectory}, and other compilation infrastructure.</p>
     */
    private class CompilationContext {
        // Stable context - set once at construction
        private final JavaCompiler compiler;
        private final StandardJavaFileManager fileManager;
        private final Writer otherOutput;
        private final String moduleNameFromPackageHierarchy;

        // Mutable state - updated during compilation
        /**
         * The output directory of the previous compilation phase or version.
         * For test compilation, this is the main output directory.
         * For multi-release, this is the output of the previous Java version.
         */
        private Path latestOutputDirectory;

        /**
         * Whether we can still add directories to the module-path or class-path.
         * For modular projects, we can only add to module-path once; subsequent
         * additions must use {@code --patch-module}.
         */
        private boolean canAddLatestOutputToPath = true;

        /**
         * Creates compilation context with stable parameters and initial state.
         *
         * @param compiler the Java compiler
         * @param fileManager the file manager for this compilation
         * @param otherOutput where to write additional compiler output
         * @param moduleNameFromPackageHierarchy module name from package hierarchy, or null
         * @param previousPhaseOutput the output directory from the previous phase, or null
         */
        CompilationContext(
                JavaCompiler compiler,
                StandardJavaFileManager fileManager,
                Writer otherOutput,
                String moduleNameFromPackageHierarchy,
                Path previousPhaseOutput) {
            this.compiler = compiler;
            this.fileManager = fileManager;
            this.otherOutput = otherOutput;
            this.moduleNameFromPackageHierarchy = moduleNameFromPackageHierarchy;
            this.latestOutputDirectory = previousPhaseOutput;
        }

        /**
         * Records that a compilation unit completed, updating the baseline for the next phase.
         *
         * @param completedOutput the output directory of the just-completed compilation
         */
        void recordCompletedOutput(Path completedOutput) {
            this.latestOutputDirectory = completedOutput;
        }

        /**
         * Tracks how many source directories were added as patches per module.
         * Keys are module names, values are the count of source directories.
         * Used to remove these source entries and replace them with compiled output.
         */
        final Map<String, Integer> modulesWithSourcesAsPatches = new HashMap<>();

        /**
         * Tracks modules from previous versions that may not be present in the current version.
         * Keys are module names, values indicate whether cleanup is needed.
         */
        final Map<String, Boolean> modulesNotPresentInNewVersion = new LinkedHashMap<>();

        /**
         * Whether we are compiling a version after the base version.
         */
        boolean isVersioned = false;

        /**
         * The type of project (classpath or modular). Defaults to CLASSPATH.
         */
        ProjectType projectType = ProjectType.CLASSPATH;

        /**
         * Marks that subsequent iterations are for versions after the base version.
         */
        void markVersioned() {
            isVersioned = true;
        }

        boolean isClasspathProject() {
            return projectType == ProjectType.CLASSPATH;
        }

        boolean isModularProject() {
            return projectType == ProjectType.MODULAR;
        }

        private void setModularProject() {
            projectType = ProjectType.MODULAR;
        }

        /**
         * Determines the project type by scanning all compilation units.
         * Also validates that there are no conflicting module names and performs
         * any necessary remapping for Maven 3 compatibility.
         * This should be called once before processing any units.
         *
         * @param units all compilation units to scan
         * @throws CompilationFailureException if both explicit and detected module names are present
         */
        void determineProjectType(Collection<SourcesForRelease> units) {
            // Check for explicit module names across all units
            String explicitModuleName = null;
            for (SourcesForRelease unit : units) {
                for (String moduleName : unit.roots.keySet()) {
                    if (!moduleName.isEmpty()) {
                        explicitModuleName = moduleName;
                        break;
                    }
                }
                if (explicitModuleName != null) {
                    break;
                }
            }

            // Validate: can't have both explicit and detected module names
            if (explicitModuleName != null && moduleNameFromPackageHierarchy != null) {
                throw new CompilationFailureException(
                        """
                        Conflicting module names: explicit "%s" vs detected "%s". \
                        Declare the module in a <module> element of <sources>."""
                                .formatted(explicitModuleName, moduleNameFromPackageHierarchy));
            }

            // Set project type
            if (explicitModuleName != null || moduleNameFromPackageHierarchy != null) {
                setModularProject();
            }

            // Remap empty keys to detected module name for all units (Maven 3 compatibility)
            if (moduleNameFromPackageHierarchy != null) {
                for (SourcesForRelease unit : units) {
                    Set<Path> paths = unit.roots.remove("");
                    if (paths != null) {
                        unit.roots.put(moduleNameFromPackageHierarchy, paths);
                    }
                }
            }
        }

        /**
         * Processes and compiles a single compilation unit (one Java release version).
         * This method configures source paths, sets up output directories, and invokes compilation.
         *
         * @param unit the compilation unit to process
         * @param options the compiler options (with release already set)
         * @return {@code true} if compilation succeeded, {@code false} otherwise
         * @throws IOException if an error occurred during compilation
         */
        boolean processCompilationUnit(SourcesForRelease unit, List<String> options) throws IOException {
            // Configure source paths based on project type (determined once at start)
            configureSourcePaths(unit.roots);

            // Clean up modules from previous version that aren't in current version
            cleanupLeftoverModules();

            // Snapshot dependencies for debug file
            copyDependencyValues();
            unit.dependencySnapshot = new LinkedHashMap<>(dependencies);

            // Set up output directory and compile (only if there are files)
            if (!unit.files.isEmpty()) {
                setupOutputDirectory(unit);
                if (!compileUnit(unit, options)) {
                    return false;
                }
            }
            markVersioned();
            return true;
        }

        /**
         * Configures source paths for all roots in a compilation unit.
         * Dispatches to either classpath or modular configuration based on project type
         * (which was determined once before processing any units).
         *
         * <p>Note: Validation and remapping have already been done in
         * {@link #determineProjectType(Collection)}, so module keys are final here.</p>
         *
         * @param roots map of module names to source paths
         * @throws IOException if an error occurred while setting locations
         */
        private void configureSourcePaths(Map<String, Set<Path>> roots) throws IOException {
            if (isClasspathProject()) {
                // Classpath: merge all source paths (typically just one entry with empty key)
                Set<Path> allSources = roots.values().stream()
                        .flatMap(Set::stream)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                configureClasspathSources(allSources);
            } else {
                // Modular: configure each module separately (keys are already final)
                for (var entry : roots.entrySet()) {
                    configureModularSources(entry.getKey(), entry.getValue());
                }
            }
        }

        /**
         * Configures source and class paths for a classpath-based (non-modular) project.
         * Sets up SOURCE_PATH for compilation and CLASS_PATH for multi-release builds.
         *
         * @param sourcePaths the source paths to compile
         * @throws IOException if an error occurred while setting locations
         */
        private void configureClasspathSources(Set<Path> sourcePaths) throws IOException {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourcePaths);

            // For multi-release builds, add previous version's output to class-path
            if (latestOutputDirectory != null) {
                Deque<Path> paths = prependDependency(JavaPathType.CLASSES, latestOutputDirectory);
                fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, paths);
            }
        }

        /**
         * Configures source paths and module settings for a modular project.
         * Sets up MODULE_SOURCE_PATH for compilation, MODULE_PATH for dependencies,
         * and configures patch-module for multi-release builds.
         *
         * @param moduleName the name of the module being compiled
         * @param sourcePaths the source paths for this module
         * @throws IOException if an error occurred while setting locations
         */
        private void configureModularSources(String moduleName, Set<Path> sourcePaths) throws IOException {
            fileManager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, moduleName, sourcePaths);
            modulesNotPresentInNewVersion.put(moduleName, Boolean.FALSE);

            // For multi-release builds, configure module-path and patch-module
            if (latestOutputDirectory != null) {
                // Add previous output to module-path only once (first module processed)
                if (canAddLatestOutputToPath) {
                    canAddLatestOutputToPath = false;
                    Deque<Path> paths = prependDependency(JavaPathType.MODULES, latestOutputDirectory);
                    fileManager.setLocationFromPaths(StandardLocation.MODULE_PATH, paths);
                }

                // Set up patch-module for this module
                addSourcesToPatchModule(moduleName, sourcePaths);
            }
        }

        /**
         * Configures {@code --patch-module} for a module being compiled for a newer Java version.
         * The patch consists of (in order, highest priority first):
         * <ol>
         *   <li>Current source paths (so the compiler sees the new version's sources)</li>
         *   <li>Output from previous Java version (compiled classes to inherit)</li>
         *   <li>Existing patch-module dependencies</li>
         * </ol>
         *
         * @param moduleName the module to patch
         * @param sourcePaths the source paths for this module in the current version
         * @throws IOException if an error occurred while setting locations
         */
        private void addSourcesToPatchModule(String moduleName, Set<Path> sourcePaths) throws IOException {
            final Deque<Path> paths = dependencies(JavaPathType.patchModule(moduleName));
            removeFirsts(paths, modulesWithSourcesAsPatches.put(moduleName, sourcePaths.size()));
            Path latestOutput = resolveModuleOutputDirectory(latestOutputDirectory, moduleName);
            if (Files.exists(latestOutput)) {
                paths.addFirst(latestOutput);
            }
            sourcePaths.forEach(paths::addFirst);
            fileManager.setLocationForModule(StandardLocation.PATCH_MODULE_PATH, moduleName, paths);
        }

        /**
         * Cleans up modules that were present in a previous version but are not in the current version.
         * This clears the source paths and updates patch-module for leftover modules.
         *
         * @throws IOException if an error occurred while setting locations
         */
        private void cleanupLeftoverModules() throws IOException {
            for (var iterator = modulesNotPresentInNewVersion.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<String, Boolean> entry = iterator.next();
                if (entry.getValue()) {
                    String moduleName = entry.getKey();
                    Deque<Path> paths = dependencies(JavaPathType.patchModule(moduleName));
                    if (removeFirsts(paths, modulesWithSourcesAsPatches.remove(moduleName))) {
                        paths.addFirst(latestOutputDirectory.resolve(moduleName));
                    } else if (paths.isEmpty()) {
                        // Not sure why the following is needed, but it has been observed in real projects.
                        paths.add(outputDirectory.resolve(moduleName));
                    }
                    fileManager.setLocationForModule(StandardLocation.PATCH_MODULE_PATH, moduleName, paths);
                    fileManager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, moduleName, Set.of());
                    iterator.remove();
                } else {
                    entry.setValue(Boolean.TRUE); // For compilation of next target version (if any).
                }
            }
        }

        /**
         * Sets up the output directory for a compilation unit.
         *
         * @param unit the compilation unit
         * @throws IOException if an error occurred while creating directories or setting locations
         */
        private void setupOutputDirectory(SourcesForRelease unit) throws IOException {
            Path outputForRelease = isVersioned
                    ? Files.createDirectories(SourceDirectory.outputDirectoryForReleases(
                            isModularProject(), outputDirectory, unit.release))
                    : outputDirectory;
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Set.of(outputForRelease));
            recordCompletedOutput(outputForRelease);
            unit.outputForRelease = outputForRelease;
            sourcesForDebugFile.add(unit);
        }

        /**
         * Compiles a single compilation unit (one Java release version).
         * Caller must ensure {@code unit.files} is not empty.
         *
         * @param unit the compilation unit
         * @param options the compiler options
         * @return {@code true} if compilation succeeded, {@code false} otherwise
         * @throws IOException if an error occurred during compilation
         */
        private boolean compileUnit(SourcesForRelease unit, List<String> options) throws IOException {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(unit.files);

            // TODO: Consider extracting WorkaroundForPatchModule handling to a separate class
            // that encapsulates the "file manager becomes unusable" lifecycle.
            StandardJavaFileManager usableFileManager = fileManager;
            boolean needsClose = false;
            if (WorkaroundForPatchModule.ENABLED && fileManager instanceof WorkaroundForPatchModule wp) {
                usableFileManager = wp.getFileManagerIfUsable();
                if (usableFileManager == null) {
                    usableFileManager = createFileManager(compiler, false);
                    wp.copyTo(usableFileManager);
                    needsClose = true;
                }
            }

            try {
                JavaCompiler.CompilationTask task =
                        compiler.getTask(otherOutput, usableFileManager, listener, options, null, sources);
                return task.call();
            } finally {
                if (needsClose) {
                    usableFileManager.close();
                }
            }
        }
    }

    /**
     * Runs the compilation task.
     *
     * @param compiler the compiler
     * @param configuration the options to give to the Java compiler
     * @param otherOutput where to write additional output from the compiler
     * @return whether the compilation succeeded
     * @throws IOException if an error occurred while reading or writing a file
     * @throws MojoException if the compilation failed for a reason identified by this method
     * @throws RuntimeException if any other kind of  error occurred
     */
    public boolean compile(final JavaCompiler compiler, final Options configuration, final Writer otherOutput)
            throws IOException {

        if (noSourcesToCompile()) {
            return true;
        }

        boolean success = true;
        try (StandardJavaFileManager fileManager = createFileManager(compiler, hasModuleDeclaration)) {
            initializeFileManager(fileManager);
            final var context = new CompilationContext(
                    compiler,
                    fileManager,
                    otherOutput,
                    moduleNameFromPackageHierarchy(),
                    getOutputDirectoryOfPreviousPhase());

            // Determine project type once from all units before processing
            Collection<SourcesForRelease> units = groupByReleaseAndModule();
            context.determineProjectType(units);

            // Compile each release version in order (base version first for multi-release projects)
            for (final SourcesForRelease unit : units) {
                configuration.setRelease(unit.getReleaseString());
                if (!context.processCompilationUnit(unit, configuration.options)) {
                    success = false;
                    break;
                }
            }

            // Post-compilation logging
            if (listener instanceof DiagnosticLogger diagnostic) {
                diagnostic.logSummary();
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        // Write incremental build cache on success
        if (success) {
            finalizeCompilation();
        }
        return success;
    }
}
