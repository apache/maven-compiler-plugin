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

import javax.tools.OptionChecker;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * @deprecated Replaced by specifying the release version together with the source directory.
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
     * Creates a new compiler MOJO.
     */
    public CompilerMojo() {
        super(PathScope.MAIN_COMPILE);
    }

    /**
     * Runs the Java compiler on the main source code.
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
     * Parses the parameters declared in the MOJO.
     *
     * @param  compiler  the tools to use for verifying the validity of options
     * @return the options after validation
     */
    @Override
    @SuppressWarnings("deprecation")
    protected Options acceptParameters(final OptionChecker compiler) {
        Options compilerConfiguration = super.acceptParameters(compiler);
        compilerConfiguration.addUnchecked(compilerArgs);
        compilerConfiguration.addUnchecked(compilerArgument);
        return compilerConfiguration;
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
            return outputDirectory.resolve(Path.of("META-INF", "versions", release));
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
     * If compiling a multi-release JAR in the old deprecated way, add the previous versions to the path.
     *
     * @param sourceDirectories the source directories
     * @param addTo where to add dependencies
     * @param hasModuleDeclaration whether the main sources have or should have a {@code module-info} file
     * @throws IOException if this method needs to walk through directories and that operation failed
     *
     * @deprecated For compatibility with the previous way to build multi-releases JAR file.
     */
    @Override
    @Deprecated(since = "4.0.0")
    protected void addImplicitDependencies(
            List<SourceDirectory> sourceDirectories, Map<PathType, List<Path>> addTo, boolean hasModuleDeclaration)
            throws IOException {
        if (SUPPORT_LEGACY && multiReleaseOutput) {
            var paths = new TreeMap<Integer, Path>();
            Path root = outputDirectory.resolve(Path.of("META-INF", "versions"));
            Files.walk(root, 1).forEach((path) -> {
                int version;
                if (path.equals(root)) {
                    path = outputDirectory;
                    version = 0;
                } else {
                    try {
                        version = Integer.parseInt(path.getFileName().toString());
                    } catch (NumberFormatException e) {
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
            /*
             * If no module name was found in the classes compiled for previous Java releases,
             * search in the source files for the Java release of the current compilation unit.
             */
            if (moduleName == null) {
                for (SourceDirectory dir : sourceDirectories) {
                    moduleName = parseModuleInfoName(dir.root.resolve(MODULE_INFO + JAVA_FILE_SUFFIX));
                    if (moduleName != null) {
                        break;
                    }
                }
            }
            var pathType = (moduleName != null) ? JavaPathType.patchModule(moduleName) : JavaPathType.CLASSES;
            addTo.computeIfAbsent(pathType, (key) -> new ArrayList<>())
                    .addAll(paths.descendingMap().values());
        }
    }
}
