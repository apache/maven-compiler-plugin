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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.PathType;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyResolverResult;

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
     * All dependencies grouped by the path types where to place them.
     * The path type can be the class-path, module-path, annotation processor path, <i>etc.</i>
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
    private final EnumSet<IncrementalBuild.Aspect> incrementalCompilation;

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
     * @see AbstractCompilerMojo#logger
     */
    protected final Log logger;

    /**
     * Creates a new task by taking a snapshot of the current configuration of the given <abbr>MOJO</abbr>.
     * This constructor creates the {@linkplain #outputDirectory output directory} if it does not already exist.
     *
     * @param mojo the <abbr>MOJO</abbr> from which to take a snapshot
     * @param listener where to send compilation warnings, or {@code null} for the Maven logger
     * @throws MojoException if this constructor identifies an invalid parameter in the <abbr>MOJO</abbr>
     * @throws IOException if an error occurred while creating the output directory or scanning the source directories
     *
     * @see AbstractCompilerMojo#createExecutor(DiagnosticListener)
     */
    @SuppressWarnings("deprecation")
    protected ToolExecutor(final AbstractCompilerMojo mojo, DiagnosticListener<? super JavaFileObject> listener)
            throws IOException {

        logger = mojo.logger;
        if (listener == null) {
            listener = new DiagnosticLogger(logger, mojo.messageBuilderFactory, LOCALE);
        }
        this.listener = listener;
        encoding = mojo.charset();
        incrementalCompilation = mojo.incrementalCompilationConfiguration();
        outputDirectory = Files.createDirectories(mojo.getOutputDirectory());
        sourceDirectories = mojo.getSourceDirectories(outputDirectory);
        /*
         * Get the source files and whether they include or are assumed to include `module-info.java`.
         * Note that we perform this step after processing compiler arguments, because this block may
         * skip the build if there is no source code to compile. We want arguments to be verified first
         * in order to warn about possible configuration problems.
         */
        if (incrementalCompilation.contains(IncrementalBuild.Aspect.MODULES)) {
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
            // The order of the two next lines matter for initialization of `SourceDirectory.moduleInfo`.
            sourceFiles = new PathFilter(mojo).walkSourceFiles(sourceDirectories);
            hasModuleDeclaration = mojo.hasModuleDeclaration(sourceDirectories);
            if (sourceFiles.isEmpty()) {
                generatedSourceDirectories = Set.of();
                dependencyResolution = null;
                dependencies = Map.of();
                return;
            }
        }
        generatedSourceDirectories = mojo.addGeneratedSourceDirectory();
        /*
         * Get the dependencies. If the module-path contains any automatic (filename-based)
         * dependency and the MOJO is compiling the main code, then a warning will be logged.
         *
         * NOTE: this code assumes that the map and the list values are modifiable.
         * This is true with `org.apache.maven.impl.DefaultDependencyResolverResult`,
         * but may not be true in the general case. To be safe, we should perform a deep copy.
         * But it would be unnecessary copies in most cases.
         */
        dependencyResolution = mojo.resolveDependencies(hasModuleDeclaration);
        dependencies = (dependencyResolution != null)
                ? dependencyResolution.getDispatchedPaths() // TODO: deep clone here if we want to be safe.
                : new LinkedHashMap<>();
        mojo.resolveProcessorPathEntries(dependencies);
        mojo.addImplicitDependencies(sourceDirectories, dependencies, hasModuleDeclaration);
    }

    /**
     * {@return the source files to compile}.
     */
    public Stream<Path> getSourceFiles() {
        return sourceFiles.stream().map((s) -> s.file);
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
        final boolean checkSources = incrementalCompilation.contains(IncrementalBuild.Aspect.SOURCES);
        final boolean checkClasses = incrementalCompilation.contains(IncrementalBuild.Aspect.CLASSES);
        final boolean checkDepends = incrementalCompilation.contains(IncrementalBuild.Aspect.DEPENDENCIES);
        final boolean checkOptions = incrementalCompilation.contains(IncrementalBuild.Aspect.OPTIONS);
        final boolean rebuildOnAdd = incrementalCompilation.contains(IncrementalBuild.Aspect.ADDITIONS);
        incrementalCompilation.clear(); // Prevent this method to be executed twice.
        if (checkSources | checkClasses | checkDepends | checkOptions) {
            final var incrementalBuild = new IncrementalBuild(mojo, sourceFiles);
            String causeOfRebuild = null;
            if (checkSources) {
                // Should be first, because this method deletes output files of removed sources.
                causeOfRebuild = incrementalBuild.inputFileTreeChanges(mojo.staleMillis, rebuildOnAdd);
            }
            if (checkClasses && causeOfRebuild == null) {
                causeOfRebuild = incrementalBuild.markNewOrModifiedSources(mojo.staleMillis, rebuildOnAdd);
            }
            if (checkDepends && causeOfRebuild == null) {
                List<String> fileExtensions = mojo.fileExtensions;
                if (fileExtensions == null || fileExtensions.isEmpty()) {
                    fileExtensions = List.of("class", "jar");
                }
                causeOfRebuild = incrementalBuild.dependencyChanges(dependencies.values(), fileExtensions);
            }
            int optionsHash = 0; // Hash code collision may happen, this is a "best effort" only.
            if (checkOptions) {
                optionsHash = configuration.options.hashCode();
                if (causeOfRebuild == null) {
                    causeOfRebuild = incrementalBuild.optionChanges(optionsHash);
                }
            }
            if (causeOfRebuild != null) {
                logger.info(causeOfRebuild);
            } else {
                sourceFiles = incrementalBuild.getModifiedSources();
                if (IncrementalBuild.isEmptyOrIgnorable(sourceFiles)) {
                    logger.info("Nothing to compile - all classes are up to date.");
                    sourceFiles = List.of();
                    return false;
                }
            }
            if (checkSources | checkDepends | checkOptions) {
                incrementalBuild.writeCache(optionsHash, checkSources);
            }
        }
        return true;
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
            var sb = new StringBuilder(n * 40).append("Compiling ").append(n).append(" source files:");
            for (SourceFile file : sourceFiles) {
                sb.append(System.lineSeparator()).append("    ").append(file);
            }
            logger.debug(sb);
        }
        /*
         * Create a `JavaFileManager`, configure all paths (dependencies and sources), then run the compiler.
         * The Java file manager has a cache, so it needs to be disposed after the compilation is completed.
         * The same `JavaFileManager` may be reused for many compilation units (e.g. multi-releases) before
         * disposal in order to reuse its cache.
         */
        boolean success = true;
        final var unresolvedPaths = new ArrayList<Path>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(listener, LOCALE, encoding)) {
            /*
             * Dispatch all dependencies on the kind of paths determined by `DependencyResolver`:
             * class-path, module-path, annotation processor class-path/module-path, etc.
             * This configuration will be unchanged for all compilation units.
             */
            List<String> patchedOptions = configuration.options; // Workaround for JDK-TBD.
            for (Map.Entry<PathType, List<Path>> entry : dependencies.entrySet()) {
                List<Path> paths = entry.getValue();
                PathType key = entry.getKey(); // TODO: replace by pattern matching in Java 21.
                if (key instanceof JavaPathType type) {
                    Optional<JavaFileManager.Location> location = type.location();
                    if (location.isPresent()) { // Cannot use `Optional.ifPresent(…)` because of checked IOException.
                        fileManager.setLocationFromPaths(location.get(), paths);
                        continue;
                    }
                } else if (key instanceof JavaPathType.Modular type) {
                    Optional<JavaFileManager.Location> location = type.rawType().location();
                    if (location.isPresent()) {
                        try {
                            fileManager.setLocationForModule(location.get(), type.moduleName(), paths);
                        } catch (UnsupportedOperationException e) { // Workaround forJDK-TBD.
                            if (patchedOptions == configuration.options) {
                                patchedOptions = new ArrayList<>(patchedOptions);
                            }
                            patchedOptions.addAll(Arrays.asList(type.option(paths)));
                        }
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
            if (!generatedSourceDirectories.isEmpty()) {
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, generatedSourceDirectories);
            }
            /*
             * More than one compilation unit may exist in the case of a multi-releases project.
             * Units are compiled in the order of the release version, with base compiled first.
             */
            compile:
            for (SourcesForRelease unit : SourcesForRelease.groupByReleaseAndModule(sourceFiles)) {
                for (Map.Entry<String, Set<Path>> root : unit.roots.entrySet()) {
                    String moduleName = root.getKey();
                    if (moduleName.isBlank()) {
                        fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, root.getValue());
                    } else {
                        fileManager.setLocationForModule(
                                StandardLocation.MODULE_SOURCE_PATH, moduleName, root.getValue());
                    }
                }
                /*
                 * TODO: for all compilations after the base one, add the base to class-path or module-path.
                 * TODO: prepend META-INF/version/## to output directory if needed.
                 */
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Set.of(outputDirectory));
                /*
                 * Compile the source files now. The following loop should be executed exactly once.
                 * It may be executed twice when compiling test classes overwriting the `module-info`,
                 * in which case the `module-info` needs to be compiled separately from other classes.
                 * However, this is a deprecated practice.
                 */
                JavaCompiler.CompilationTask task;
                for (CompilationTaskSources c : toCompilationTasks(unit)) {
                    Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(c.files);
                    task = compiler.getTask(otherOutput, fileManager, listener, patchedOptions, null, sources);
                    patchedOptions = configuration.options; // Patched options shall be used only once.
                    success = c.compile(task);
                    if (!success) {
                        break compile;
                    }
                }
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
        return success;
    }

    /**
     * Subdivides a compilation unit into one or more compilation tasks.
     * This is a workaround for deprecated practices such as overwriting the main {@code module-info} in the tests.
     * In the latter case, we need to compile the test {@code module-info} separately, before the other test classes.
     */
    CompilationTaskSources[] toCompilationTasks(final SourcesForRelease unit) {
        return new CompilationTaskSources[] {new CompilationTaskSources(unit.files)};
    }
}
