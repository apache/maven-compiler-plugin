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
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.PathScope;
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
        super.execute();
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
     * @param  executor  the executor where to add implicit dependencies.
     * @throws IOException if this method needs to walk through directories and that operation failed
     *
     * @deprecated For compatibility with the previous way to build multi-releases JAR file.
     */
    @Deprecated(since = "4.0.0")
    private void addImplicitDependencies(final ToolExecutor executor) throws IOException {
        final var paths = new TreeMap<SourceVersion, Path>();
        final Path root = SourceDirectory.outputDirectoryForReleases(outputDirectory);
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
         * Find the module name. If many module-info classes are found,
         * the most basic one (with lowest Java release number) is taken.
         * We need to remember the release where the module has been found.
         */
        String moduleName = null;
        SourceVersion moduleAt = null;
        for (Map.Entry<SourceVersion, Path> entry : paths.entrySet()) {
            Path path = entry.getValue().resolve(MODULE_INFO + CLASS_FILE_SUFFIX);
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    moduleName = ModuleDescriptor.read(in).name();
                }
                moduleAt = entry.getKey();
                break;
            }
        }
        /*
         * If no module name was found in the classes compiled for previous Java releases,
         * search in the source files for the Java release of the current compilation unit.
         */
        if (moduleName == null) {
            for (SourceDirectory dir : executor.sourceDirectories) {
                moduleName = parseModuleInfoName(dir.root.resolve(MODULE_INFO + JAVA_FILE_SUFFIX));
                if (moduleName != null) {
                    moduleAt = dir.release;
                    if (moduleAt == null) {
                        moduleAt = SourceVersion.RELEASE_0;
                    }
                    break;
                }
            }
        }
        /*
         * Add previous versions as dependencies on the class-path or module-path, depending on whether
         * the project is modular. If `module-info.java` is defined in some version higher than the base
         * version, then we need to add the same paths on both the class-oath and module-path. Note that
         * we stop this duplication after we reach a version where the `module-info` is available.
         */
        NavigableMap<SourceVersion, Path> classpath = paths;
        if (moduleName != null) {
            classpath = paths.headMap(moduleAt, false); // All versions before `module-info` was introduced.
            executor.dependencies(JavaPathType.patchModule(moduleName))
                    .addAll(paths.descendingMap().values());
        }
        if (!classpath.isEmpty()) {
            executor.dependencies(JavaPathType.CLASSES)
                    .addAll(classpath.descendingMap().values());
        }
    }
}
