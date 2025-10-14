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
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    protected final Map<PathType, List<Path>> dependencies;

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
        generatedSourceDirectories = mojo.addGeneratedSourceDirectory();
        /*
         * Get the dependencies. If the module-path contains any automatic (filename-based)
         * dependency and the MOJO is compiling the main code, then a warning will be logged.
         */
        dependencyResolution = mojo.resolveDependencies(hasModuleDeclaration);
        if (dependencyResolution != null) {
            dependencies.putAll(dependencyResolution.getDispatchedPaths());
            copyDependencyValues();
        }
        mojo.resolveProcessorPathEntries(dependencies);
    }

    /**
     * Copies all values of the dependency map in unmodifiable lists.
     * This is used for creating a snapshot of the current state of the dependency map.
     */
    private void copyDependencyValues() {
        dependencies.entrySet().forEach((entry) -> entry.setValue(List.copyOf(entry.getValue())));
    }

    /**
     * {@return whether a release version is specified for all sources}.
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
     * {@return a modifiable list of paths to all dependencies of the given type}.
     * The returned list is intentionally live: elements can be added or removed
     * from the list for changing the state of this executor.
     *
     * @param  pathType  type of path for which to get the dependencies
     */
    protected List<Path> dependencies(PathType pathType) {
        return dependencies.compute(pathType, (key, paths) -> {
            if (paths == null) {
                return new ArrayList<>();
            } else if (paths instanceof ArrayList<?>) {
                return paths;
            } else {
                var copy = new ArrayList<Path>(paths.size() + 4); // Anticipate the addition of new elements.
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
        for (Map.Entry<PathType, List<Path>> entry : dependencies.entrySet()) {
            List<Path> paths = entry.getValue();
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
                            paths = new ArrayList<>(paths);
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
    protected List<Path> prependDependency(final PathType pathType, final Path first) {
        List<Path> paths = dependencies(pathType);
        paths.add(0, first);
        return paths;
    }

    /**
     * Ensures that the given value is non-null, replacing null values by the latest version.
     */
    private static SourceVersion nonNullOrLatest(SourceVersion release) {
        return (release != null) ? release : SourceVersion.latest();
    }

    /**
     * If the given module name is empty, tries to infer a default module name. A module name is inferred
     * (tentatively) when the <abbr>POM</abbr> file does not contain an explicit {@code <module>} element.
     * This method exists only for compatibility with the Maven 3 way to do a modular project.
     *
     * @param moduleName the module name, or an empty string if not explicitly specified
     * @return the specified module name, or an inferred module name if available, or an empty string
     * @throws IOException if the module descriptor cannot be read.
     */
    String inferModuleNameIfMissing(String moduleName) throws IOException {
        return moduleName;
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
    @SuppressWarnings("checkstyle:MethodLength")
    public boolean compile(final JavaCompiler compiler, final Options configuration, final Writer otherOutput)
            throws IOException {
        /*
         * Announce what the compiler is about to do.
         */
        sourcesForDebugFile.clear();
        if (sourceFiles.isEmpty()) {
            String message = "No sources to compile.";
            try {
                Files.delete(outputDirectory);
            } catch (DirectoryNotEmptyException e) {
                message += " However, the output directory is not empty.";
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
        /*
         * Create a `JavaFileManager`, configure all paths (dependencies and sources), then run the compiler.
         * The Java file manager has a cache, so it needs to be disposed after the compilation is completed.
         * The same `JavaFileManager` may be reused for many compilation units (e.g. multi-release) before
         * disposal in order to reuse its cache.
         */
        boolean success = true;
        try (StandardJavaFileManager fileManager = createFileManager(compiler, hasModuleDeclaration)) {
            setDependencyPaths(fileManager);
            if (!generatedSourceDirectories.isEmpty()) {
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, generatedSourceDirectories);
            }
            boolean isVersioned = false;
            Path latestOutputDirectory = null;
            /*
             * More than one compilation unit may exist in the case of a multi-release project.
             * Units are compiled in the order of the release version, with base compiled first.
             * At the beginning of each new iteration, `latestOutputDirectory` is the path to
             * the compiled classes of the previous version.
             */
            compile:
            for (final SourcesForRelease unit : groupByReleaseAndModule()) {
                Path outputForRelease = null;
                boolean isClasspathProject = false;
                boolean isModularProject = false;
                String defaultModuleName = null;
                configuration.setRelease(unit.getReleaseString());
                for (final Map.Entry<String, Set<Path>> root : unit.roots.entrySet()) {
                    final String declaredModuleName = root.getKey();
                    final String moduleName = inferModuleNameIfMissing(declaredModuleName);
                    if (moduleName.isEmpty()) {
                        isClasspathProject = true;
                    } else {
                        isModularProject = true;
                        if (declaredModuleName.isEmpty()) { // Modular project using package source hierarchy.
                            defaultModuleName = moduleName;
                        }
                    }
                    if (isClasspathProject & isModularProject) {
                        throw new CompilationFailureException("Mix of modular and non-modular sources.");
                    }
                    final Set<Path> sourcePaths = root.getValue();
                    if (isClasspathProject) {
                        fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourcePaths);
                    } else {
                        fileManager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, moduleName, sourcePaths);
                    }
                    outputForRelease = outputDirectory; // Modified below if compiling a non-base release.
                    if (isVersioned) {
                        outputForRelease = Files.createDirectories(
                                SourceDirectory.outputDirectoryForReleases(outputForRelease, unit.release));
                        if (isClasspathProject) {
                            /*
                             * For a non-modular project, this block is executed at most once par compilation unit.
                             * Add the paths to the classes compiled for previous versions.
                             */
                            List<Path> classpath = prependDependency(JavaPathType.CLASSES, latestOutputDirectory);
                            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                        } else {
                            /*
                             * For a modular project, this block can be executed an arbitrary number of times
                             * (once per module).
                             */
                            Path latestOutputForModule = latestOutputDirectory.resolve(moduleName);
                            JavaPathType.Modular pathType = JavaPathType.patchModule(moduleName);
                            List<Path> paths = prependDependency(pathType, latestOutputForModule);
                            fileManager.setLocationForModule(StandardLocation.PATCH_MODULE_PATH, moduleName, paths);
                        }
                    }
                }
                /*
                 * At this point, we finished to set the source paths. We have also modified the class-path or
                 * patched the modules with the output directories of codes compiled for lower Java releases.
                 * The `defaultModuleName` is an adjustment done when the project is a Java module, but still
                 * organized in a package source hierarchy instead of a module source hierarchy. Updating the
                 * `unit.roots` map is not needed for this class, but done in case a `target/javac.args` file
                 * will be written after the compilation.
                 */
                if (defaultModuleName != null) {
                    Set<Path> paths = unit.roots.remove("");
                    if (paths != null) {
                        unit.roots.put(defaultModuleName, paths);
                    }
                }
                copyDependencyValues();
                unit.dependencySnapshot = new LinkedHashMap<>(dependencies);
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Set.of(outputForRelease));
                latestOutputDirectory = outputForRelease;
                unit.outputForRelease = outputForRelease;
                /*
                 * Compile the source files now. The following loop should be executed exactly once.
                 * It may be executed twice when compiling test classes overwriting the `module-info`,
                 * in which case the `module-info` needs to be compiled separately from other classes.
                 * However, this is a deprecated practice.
                 */
                JavaCompiler.CompilationTask task;
                for (CompilationTaskSources c : toCompilationTasks(unit)) {
                    Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(c.files);
                    StandardJavaFileManager workaround = fileManager;
                    boolean workaroundNeedsClose = false;
                    // Check flag separately to clearly indicate this entire block is a workaround hack.
                    if (WorkaroundForPatchModule.ENABLED) {
                        if (workaround instanceof WorkaroundForPatchModule wp) {
                            workaround = wp.getFileManagerIfUsable();
                            if (workaround == null) {
                                workaround = createFileManager(compiler, false);
                                wp.copyTo(workaround);
                                workaroundNeedsClose = true;
                            }
                        }
                    }
                    task = compiler.getTask(otherOutput, workaround, listener, configuration.options, null, sources);
                    success = c.compile(task);
                    if (workaroundNeedsClose) {
                        workaround.close();
                    }
                    sourcesForDebugFile.add(unit);
                    if (!success) {
                        break compile;
                    }
                }
                isVersioned = true; // Any further iteration is for a version after the base version.
            }
            /*
             * Post-compilation.
             */
            if (listener instanceof DiagnosticLogger diagnostic) {
                diagnostic.logSummary();
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        if (success && incrementalBuild != null) {
            incrementalBuild.writeCache();
            incrementalBuild = null;
        }
        return success;
    }

    /**
     * Subdivides a compilation unit into one or more compilation tasks.
     * This is a workaround for deprecated practices such as overwriting the main {@code module-info} in the tests.
     * In the latter case, we need to compile the test {@code module-info} separately, before the other test classes.
     */
    CompilationTaskSources[] toCompilationTasks(final SourcesForRelease unit) {
        if (unit.files.isEmpty()) {
            return new CompilationTaskSources[0];
        }
        return new CompilationTaskSources[] {new CompilationTaskSources(unit.files)};
    }
}
