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
import javax.tools.JavaFileObject;
import javax.tools.OptionChecker;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.PathType;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import static org.apache.maven.plugin.compiler.SourceDirectory.CLASS_FILE_SUFFIX;
import static org.apache.maven.plugin.compiler.SourceDirectory.JAVA_FILE_SUFFIX;
import static org.apache.maven.plugin.compiler.SourceDirectory.MODULE_INFO;

/**
 * Compiles application sources.
 * Each instance shall be used only once, then discarded.
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @author Martin Desruisseaux
 * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html">javac Command</a>
 * @since 2.0
 */
@Mojo(name = "compile", defaultPhase = "compile")
public class CompilerMojo extends AbstractCompilerMojo {
    /**
     * Set this to {@code true} to bypass compilation of main sources.
     * Its use is not recommended, but quite convenient on occasion.
     */
    @Parameter(property = "maven.main.skip")
    protected boolean skipMain;

    /**
     * Specify where to place generated source files created by annotation processing.
     *
     * @since 2.2
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/annotations")
    protected Path generatedSourcesDirectory;

    /**
     * A set of inclusion filters for the compiler.
     */
    @Parameter
    protected Set<String> includes;

    /**
     * A set of exclusion filters for the compiler.
     */
    @Parameter
    protected Set<String> excludes;

    /**
     * A set of exclusion filters for the incremental calculation.
     * Updated source files, if excluded by this filter, will not cause the project to be rebuilt.
     *
     * <h4>Limitation</h4>
     * In the current implementation, those exclusion filters are applied for added or removed files,
     * but not yet for removed files.
     *
     * @since 3.11
     */
    @Parameter
    protected Set<String> incrementalExcludes;

    /**
     * The directory for compiled classes.
     *
     * @see #getOutputDirectory()
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
    protected Path outputDirectory;

    /**
     * Projects main artifact.
     */
    @Parameter(defaultValue = "${project.mainArtifact}", readonly = true, required = true)
    protected ProducedArtifact projectArtifact;

    /**
     * When set to {@code true}, the classes will be placed in {@code META-INF/versions/${release}}.
     * <p>
     * <strong>Note:</strong> A jar is only a multi-release jar if {@code META-INF/MANIFEST.MF} contains
     * {@code Multi-Release: true}. You need to set this by configuring the <a href=
     * "https://maven.apache.org/plugins/maven-jar-plugin/examples/manifest-customization.html">maven-jar-plugin</a>.
     * This implies that you cannot test a multi-release jar using the {@link #outputDirectory}.
     * </p>
     *
     * @since 3.7.1
     *
     * @deprecated Replaced by specifying the {@code <targetVersion>} value inside a {@code <source>} element.
     */
    @Parameter
    @Deprecated(since = "4.0.0")
    protected boolean multiReleaseOutput;

    /**
     * The file where to dump the command-line when debug is activated or when the compilation failed.
     * For example, if the value is {@code "javac"}, then the Java compiler can be launched from the
     * command-line by typing {@code javac @target/javac.args}.
     * The debug file will contain the compiler options together with the list of source files to compile.
     *
     * @since 3.10.0
     */
    @Parameter(defaultValue = "javac.args")
    protected String debugFileName;

    /**
     * Target directory that have been temporarily created as symbolic link before compilation.
     * This is used as a workaround for the fact that, when compiling a modular project with
     * all the module-related compiler options, the classes are written in a directory with
     * the module name. It does not fit in the {@code META-INF/versions/<release>} pattern.
     * Temporary symbolic link is a workaround for this problem.
     *
     * <h4>Example</h4>
     * When compiling the {@code my.app} module for Java 17, the desired output directory is:
     *
     * <blockquote>{@code target/classes/META-INF/versions/17}</blockquote>
     *
     * But {@code javac}, when used with the {@code --module-source-path} option,
     * will write the classes in the following directory:
     *
     * <blockquote>{@code target/classes/META-INF/versions/17/my.app}</blockquote>
     *
     * We workaround this problem with a symbolic link which redirects {@code 17/my.app} to {@code 17}.
     * We need to do this only when compiling multi-releases project in the old deprecated way.
     * When using the recommended {@code <sources>} approach, the plugins are designed to work
     * with the directory layout produced by {@code javac} instead of fighting against it.
     *
     * @deprecated For compatibility with the previous way to build multi-releases JAR file.
     *             May be removed after we drop support of the old way to do multi-releases.
     */
    @Deprecated(since = "4.0.0")
    private Path directoryLevelToRemove;

    /**
     * Creates a new compiler <abbr>MOJO</abbr> for the main code.
     */
    public CompilerMojo() {
        super(PathScope.MAIN_COMPILE);
    }

    /**
     * Runs the Java compiler on the main source code.
     * If {@link #skipMain} is {@code true}, then this method logs a message and does nothing else.
     * Otherwise, this method executes the steps described in the method of the parent class.
     *
     * @throws MojoException if the compiler cannot be run.
     */
    @Override
    public void execute() throws MojoException {
        if (skipMain) {
            logger.info("Not compiling main sources");
            return;
        }
        try {
            super.execute();
        } finally {
            if (directoryLevelToRemove != null) {
                try {
                    Files.delete(directoryLevelToRemove);
                } catch (IOException e) {
                    throw new CompilationFailureException("I/O error while organizing multi-release classes.", e);
                }
            }
        }
        @SuppressWarnings("LocalVariableHidesMemberVariable")
        Path outputDirectory = getOutputDirectory();
        if (Files.isDirectory(outputDirectory) && projectArtifact != null) {
            artifactManager.setPath(projectArtifact, outputDirectory);
        }
    }

    /**
     * Parses the parameters declared in the <abbr>MOJO</abbr>.
     *
     * @param  compiler  the tools to use for verifying the validity of options
     * @return the options after validation
     */
    @Override
    @SuppressWarnings("deprecation")
    public Options parseParameters(final OptionChecker compiler) {
        Options configuration = super.parseParameters(compiler);
        configuration.addUnchecked(compilerArgs);
        configuration.addUnchecked(compilerArgument);
        return configuration;
    }

    /**
     * {@return the path where to place generated source files created by annotation processing on the main classes}.
     */
    @Nullable
    @Override
    protected Path getGeneratedSourcesDirectory() {
        return generatedSourcesDirectory;
    }

    /**
     * {@return the inclusion filters for the compiler, or an empty set for all Java source files}.
     */
    @Override
    protected Set<String> getIncludes() {
        return (includes != null) ? includes : Set.of();
    }

    /**
     * {@return the exclusion filters for the compiler, or an empty set if none}.
     */
    @Override
    protected Set<String> getExcludes() {
        return (excludes != null) ? excludes : Set.of();
    }

    /**
     * {@return the exclusion filters for the incremental calculation, or an empty set if none}.
     */
    @Override
    protected Set<String> getIncrementalExcludes() {
        return (incrementalExcludes != null) ? incrementalExcludes : Set.of();
    }

    /**
     * {@return the destination directory for main class files}.
     * If {@link #multiReleaseOutput} is true <em>(deprecated)</em>,
     * the output will be in a {@code META-INF/versions} subdirectory.
     */
    @Nonnull
    @Override
    protected Path getOutputDirectory() {
        if (SUPPORT_LEGACY && multiReleaseOutput && release != null) {
            return SourceDirectory.outputDirectoryForReleases(outputDirectory).resolve(release);
        }
        return outputDirectory;
    }

    /**
     * {@return the file where to dump the command-line when debug is activated or when the compilation failed}.
     */
    @Nullable
    @Override
    protected String getDebugFileName() {
        return debugFileName;
    }

    /**
     * Creates a new task for compiling the main classes.
     *
     * @param listener where to send compilation warnings, or {@code null} for the Maven logger
     * @throws MojoException if this method identifies an invalid parameter in this <abbr>MOJO</abbr>
     * @return the task to execute for compiling the main code using the configuration in this <abbr>MOJO</abbr>
     * @throws IOException if an error occurred while creating the output directory or scanning the source directories
     */
    @Override
    public ToolExecutor createExecutor(DiagnosticListener<? super JavaFileObject> listener) throws IOException {
        ToolExecutor executor = super.createExecutor(listener);
        if (SUPPORT_LEGACY && multiReleaseOutput) {
            addImplicitDependencies(executor);
        }
        return executor;
    }

    /**
     * Adds the compilation outputs of previous Java releases to the class-path ot module-path.
     * This method should be invoked only when compiling a multi-release <abbr>JAR</abbr> in the
     * old deprecated way.
     *
     * <p>The {@code executor} argument may be {@code null} if the caller is only interested in the
     * module name, with no executor to modify. The module name found by this method is specific to
     * the way that projects are organized when {@link #multiReleaseOutput} is {@code true}.</p>
     *
     * @param  executor  the executor where to add implicit dependencies, or {@code null} if none
     * @return the module name, or {@code null} if none
     * @throws IOException if this method needs to walk through directories and that operation failed
     *
     * @deprecated For compatibility with the previous way to build multi-releases JAR file.
     *             May be removed after we drop support of the old way to do multi-releases.
     */
    @Deprecated(since = "4.0.0")
    private String addImplicitDependencies(final ToolExecutor executor) throws IOException {
        final Path root = SourceDirectory.outputDirectoryForReleases(outputDirectory);
        if (Files.notExists(root)) {
            return null;
        }
        final var paths = new TreeMap<SourceVersion, Path>();
        Files.walk(root, 1).forEach((path) -> {
            SourceVersion version;
            if (path.equals(root)) {
                path = outputDirectory;
                version = SourceVersion.RELEASE_0;
            } else {
                try {
                    version = SourceVersion.valueOf("RELEASE_" + path.getFileName());
                } catch (IllegalArgumentException e) {
                    throw new CompilationFailureException("Invalid version number for " + path, e);
                }
            }
            if (paths.put(version, path) != null) {
                throw new CompilationFailureException("Duplicated version number for " + path);
            }
        });
        /*
         * Search for the module name. If many module-info classes are found,
         * the most basic one (with lowest Java release number) is selected.
         */
        String moduleName = null;
        for (Path path : paths.values()) {
            path = path.resolve(MODULE_INFO + CLASS_FILE_SUFFIX);
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    moduleName = ModuleDescriptor.read(in).name();
                }
                break;
            }
        }
        if (executor != null) {
            /*
             * If no module name was found in the classes compiled for previous Java releases,
             * search in the source files for the Java release of the current compilation unit.
             */
            if (moduleName == null) {
                for (SourceDirectory dir : executor.sourceDirectories) {
                    moduleName = parseModuleInfoName(dir.root.resolve(MODULE_INFO + JAVA_FILE_SUFFIX));
                    if (moduleName != null) {
                        break;
                    }
                }
            }
            /*
             * Add previous versions as dependencies on the class-path or module-path, depending on whether
             * the project is modular. Each path should be on either the class-path or module-path, but not
             * both. If a path for a modular project seems needed on the class-path, it may be a sign that
             * other options are not used correctly (e.g., `--source-path` versus `--module-source-path`).
             */
            PathType type = JavaPathType.CLASSES;
            if (moduleName != null) {
                type = JavaPathType.patchModule(moduleName);
                Path javacTarget = executor.outputDirectory.resolve(moduleName);
                directoryLevelToRemove = Files.createSymbolicLink(javacTarget, javacTarget.getParent());
            }
            if (!paths.isEmpty()) {
                executor.dependencies(type).addAll(paths.descendingMap().values());
            }
        }
        return moduleName;
    }

    /**
     * {@return the module name in a previous execution of the compiler plugin, or {@code null} if none}.
     *
     * @deprecated For compatibility with the previous way to build multi-releases JAR file.
     *             May be removed after we drop support of the old way to do multi-releases.
     */
    @Override
    @Deprecated(since = "4.0.0")
    final String moduleOfPreviousExecution() throws IOException {
        if (SUPPORT_LEGACY && multiReleaseOutput) {
            return addImplicitDependencies(null);
        }
        return super.moduleOfPreviousExecution();
    }
}
