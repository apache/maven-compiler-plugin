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
import javax.tools.JavaFileObject;
import javax.tools.OptionChecker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.PathType;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.MessageBuilder;

import static org.apache.maven.plugin.compiler.SourceDirectory.CLASS_FILE_SUFFIX;
import static org.apache.maven.plugin.compiler.SourceDirectory.JAVA_FILE_SUFFIX;
import static org.apache.maven.plugin.compiler.SourceDirectory.MODULE_INFO;

/**
 * Compiles application test sources.
 * Each instance shall be used only once, then discarded.
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @author Martin Desruisseaux
 * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html">javac Command</a>
 * @since 2.0
 */
@Mojo(name = "testCompile", defaultPhase = "test-compile")
public class TestCompilerMojo extends AbstractCompilerMojo {
    /**
     * Whether to bypass compilation of test sources.
     * Its use is not recommended, but quite convenient on occasion.
     *
     * @see CompilerMojo#skipMain
     */
    @Parameter(property = "maven.test.skip")
    protected boolean skip;

    /**
     * Specify where to place generated source files created by annotation processing.
     *
     * @see CompilerMojo#generatedSourcesDirectory
     * @since 2.2
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/test-annotations")
    protected Path generatedTestSourcesDirectory;

    /**
     * A set of inclusion filters for the compiler.
     *
     * @see CompilerMojo#includes
     */
    @Parameter
    protected Set<String> testIncludes;

    /**
     * A set of exclusion filters for the compiler.
     *
     * @see CompilerMojo#excludes
     */
    @Parameter
    protected Set<String> testExcludes;

    /**
     * A set of exclusion filters for the incremental calculation.
     * Updated files, if excluded by this filter, will not cause the project to be rebuilt.
     *
     * @see CompilerMojo#incrementalExcludes
     * @since 3.11
     */
    @Parameter
    protected Set<String> testIncrementalExcludes;

    /**
     * The {@code --source} argument for the test Java compiler.
     *
     * @see CompilerMojo#source
     * @since 2.1
     */
    @Parameter(property = "maven.compiler.testSource")
    protected String testSource;

    /**
     * The {@code --target} argument for the test Java compiler.
     *
     * @see CompilerMojo#target
     * @since 2.1
     */
    @Parameter(property = "maven.compiler.testTarget")
    protected String testTarget;

    /**
     * the {@code --release} argument for the test Java compiler
     *
     * @see CompilerMojo#release
     * @since 3.6
     */
    @Parameter(property = "maven.compiler.testRelease")
    protected String testRelease;

    /**
     * The arguments to be passed to the test compiler.
     * If this parameter is specified, it replaces {@link #compilerArgs}.
     * Otherwise, the {@code compilerArgs} parameter is used.
     *
     * @see CompilerMojo#compilerArgs
     * @since 4.0.0
     */
    @Parameter
    protected List<String> testCompilerArgs;

    /**
     * The arguments to be passed to test compiler.
     *
     * @deprecated Replaced by {@link #testCompilerArgs} for consistency with the main phase.
     *
     * @since 2.1
     */
    @Parameter
    @Deprecated(since = "4.0.0")
    protected Map<String, String> testCompilerArguments;

    /**
     * The single argument string to be passed to the test compiler.
     * If this parameter is specified, it replaces {@link #compilerArgument}.
     * Otherwise, the {@code compilerArgument} parameter is used.
     *
     * @deprecated Use {@link #testCompilerArgs} instead.
     *
     * @see CompilerMojo#compilerArgument
     * @since 2.1
     */
    @Parameter
    @Deprecated(since = "4.0.0")
    protected String testCompilerArgument;

    /**
     * The directory where compiled test classes go.
     * This parameter should only be modified in special cases.
     * See the {@link CompilerMojo#outputDirectory} for more information.
     *
     * @see CompilerMojo#outputDirectory
     * @see #getOutputDirectory()
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    protected Path outputDirectory;

    /**
     * The output directory of the main classes.
     * This directory will be added to the class-path or module-path.
     * Its value should be the same as {@link CompilerMojo#outputDirectory}.
     *
     * @see CompilerMojo#outputDirectory
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
    protected Path mainOutputDirectory;

    /**
     * Whether to place the main classes on the module path when {@code module-info} is present.
     * When {@code false}, always places the main classes on the class path.
     * Dependencies are also placed on the class-path, unless their type is {@code module-jar}.
     *
     * @since 3.11
     *
     * @deprecated Use {@code "claspath-jar"} dependency type instead, and avoid {@code module-info.java} in tests.
     */
    @Deprecated(since = "4.0.0")
    @Parameter(defaultValue = "true")
    protected boolean useModulePath = true;

    /**
     * Whether a {@code module-info.java} file is defined in the test sources.
     * In such case, it has precedence over the {@code module-info.java} in main sources.
     * This is defined for compatibility with Maven 3, but not recommended.
     *
     * <p>This field exists in this class only for transferring this information
     * to {@link ToolExecutorForTest#hasTestModuleInfo}, which is the class that
     * needs this information.</p>
     */
    transient boolean hasTestModuleInfo;

    /**
     * Path to the {@code module-info.class} file of the main code, or {@code null} if that file does not exist.
     * This field exists only for transferring this information to {@link ToolExecutorForTest#hasTestModuleInfo},
     * and should be {@code null} the rest of the time.
     */
    transient Path mainModulePath;

    /**
     * The file where to dump the command-line when debug is activated or when the compilation failed.
     * For example, if the value is {@code "javac-test"}, then the Java compiler can be launched
     * from the command-line by typing {@code javac @target/javac-test.args}.
     * The debug file will contain the compiler options together with the list of source files to compile.
     *
     * @see CompilerMojo#debugFileName
     * @since 3.10.0
     */
    @Parameter(defaultValue = "javac-test.args")
    protected String debugFileName;

    /**
     * Creates a new compiler <abbr>MOJO</abbr> for the tests.
     */
    public TestCompilerMojo() {
        super(PathScope.TEST_COMPILE);
    }

    /**
     * Runs the Java compiler on the test source code.
     * If {@link #skip} is {@code true}, then this method logs a message and does nothing else.
     * Otherwise, this method executes the steps described in the method of the parent class.
     *
     * @throws MojoException if the compiler cannot be run.
     */
    @Override
    public void execute() throws MojoException {
        if (skip) {
            logger.info("Not compiling test sources");
            return;
        }
        super.execute();
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
        configuration.addUnchecked(
                testCompilerArgs == null || testCompilerArgs.isEmpty() ? compilerArgs : testCompilerArgs);
        if (testCompilerArguments != null) {
            for (Map.Entry<String, String> entry : testCompilerArguments.entrySet()) {
                configuration.addUnchecked(List.of(entry.getKey(), entry.getValue()));
            }
        }
        configuration.addUnchecked(testCompilerArgument == null ? compilerArgument : testCompilerArgument);
        return configuration;
    }

    /**
     * {@return the path where to place generated source files created by annotation processing on the test classes}.
     */
    @Nullable
    @Override
    protected Path getGeneratedSourcesDirectory() {
        return generatedTestSourcesDirectory;
    }

    /**
     * {@return the inclusion filters for the compiler, or an empty set for all Java source files}.
     */
    @Override
    protected Set<String> getIncludes() {
        return (testIncludes != null) ? testIncludes : Set.of();
    }

    /**
     * {@return the exclusion filters for the compiler, or an empty set if none}.
     */
    @Override
    protected Set<String> getExcludes() {
        return (testExcludes != null) ? testExcludes : Set.of();
    }

    /**
     * {@return the exclusion filters for the incremental calculation, or an empty set if none}.
     */
    @Override
    protected Set<String> getIncrementalExcludes() {
        return (testIncrementalExcludes != null) ? testIncrementalExcludes : Set.of();
    }

    /**
     * If a different source version has been specified for the tests, returns that version.
     * Otherwise returns the same source version as the main code.
     *
     * @return the {@code --source} argument for the Java compiler
     */
    @Nullable
    @Override
    protected String getSource() {
        return testSource == null ? source : testSource;
    }

    /**
     * If a different target version has been specified for the tests, returns that version.
     * Otherwise returns the same target version as the main code.
     *
     * @return the {@code --target} argument for the Java compiler
     */
    @Nullable
    @Override
    protected String getTarget() {
        return testTarget == null ? target : testTarget;
    }

    /**
     * If a different release version has been specified for the tests, returns that version.
     * Otherwise returns the same release version as the main code.
     *
     * @return the {@code --release} argument for the Java compiler
     */
    @Nullable
    @Override
    protected String getRelease() {
        return testRelease == null ? release : testRelease;
    }

    /**
     * {@return the destination directory for test class files}.
     */
    @Nonnull
    @Override
    protected Path getOutputDirectory() {
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
     * {@return the module name declared in the test sources}. We have to parse the source instead
     * of the {@code module-info.class} file because the classes may not have been compiled yet.
     * This is not very reliable, but putting a {@code module-info.java} file in the tests is
     * deprecated anyway.
     */
    final String getTestModuleName(List<SourceDirectory> compileSourceRoots) throws IOException {
        for (SourceDirectory directory : compileSourceRoots) {
            if (directory.moduleName != null) {
                return directory.moduleName;
            }
            String name = parseModuleInfoName(directory.getModuleInfo().orElse(null));
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /**
     * {@return whether the project has at least one {@code module-info.class} file}.
     *
     * @param roots root directories of the sources to compile
     * @throws IOException if this method needed to read a module descriptor and failed
     */
    @Override
    final boolean hasModuleDeclaration(final List<SourceDirectory> roots) throws IOException {
        for (SourceDirectory root : roots) {
            if (root.getModuleInfo().isPresent()) {
                hasTestModuleInfo = true;
                break;
            }
        }
        if (hasTestModuleInfo) {
            MessageBuilder message = messageBuilderFactory.builder();
            message.a("Overwriting the ")
                    .warning(MODULE_INFO + JAVA_FILE_SUFFIX)
                    .a(" file in the test directory is deprecated. Use ")
                    .info("--add-reads")
                    .a(", ")
                    .info("--add-modules")
                    .a(" and related options instead.");
            logger.warn(message.toString());
            if (SUPPORT_LEGACY) {
                return useModulePath;
            }
        }
        return useModulePath && mainModulePath != null;
    }

    /**
     * Adds the main compilation output directories as test dependencies.
     *
     * @param sourceDirectories the source directories (ignored)
     * @param addTo where to add dependencies
     * @param hasModuleDeclaration whether the main sources have or should have a {@code module-info} file
     */
    @Override
    final void addImplicitDependencies(
            List<SourceDirectory> sourceDirectories, Map<PathType, List<Path>> addTo, boolean hasModuleDeclaration) {
        var pathType = hasModuleDeclaration ? JavaPathType.MODULES : JavaPathType.CLASSES;
        if (Files.exists(mainOutputDirectory)) {
            addTo.computeIfAbsent(pathType, (key) -> new ArrayList<>()).add(mainOutputDirectory);
        }
    }

    /**
     * Creates a new task for compiling the test classes.
     *
     * @param listener where to send compilation warnings, or {@code null} for the Maven logger
     * @throws MojoException if this method identifies an invalid parameter in this <abbr>MOJO</abbr>
     * @return the task to execute for compiling the tests using the configuration in this <abbr>MOJO</abbr>
     * @throws IOException if an error occurred while creating the output directory or scanning the source directories
     */
    @Override
    public ToolExecutor createExecutor(DiagnosticListener<? super JavaFileObject> listener) throws IOException {
        try {
            Path file = mainOutputDirectory.resolve(MODULE_INFO + CLASS_FILE_SUFFIX);
            if (Files.isRegularFile(file)) {
                mainModulePath = file;
            }
            return new ToolExecutorForTest(this, listener);
        } finally {
            // Reset the fields that were used only for transfering information to `ToolExecutorForTest`.
            hasTestModuleInfo = false;
            mainModulePath = null;
        }
    }
}
