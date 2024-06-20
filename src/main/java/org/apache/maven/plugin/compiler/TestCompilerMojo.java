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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.api.JavaToolchain;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsResult;

/**
 * Compiles application test sources.
 * By default uses the <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html">javac</a> compiler
 * of the JDK used to execute Maven. This can be overwritten through <a href="https://maven.apache.org/guides/mini/guide-using-toolchains.html">Toolchains</a>
 * or parameter {@link AbstractCompilerMojo#compilerId}.
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @since 2.0
 * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html">javac Command</a>
 */
@Mojo(name = "testCompile", defaultPhase = "test-compile")
public class TestCompilerMojo extends AbstractCompilerMojo {
    /**
     * Set this to 'true' to bypass compilation of test sources.
     * Its use is NOT RECOMMENDED, but quite convenient on occasion.
     */
    @Parameter(property = "maven.test.skip")
    private boolean skip;

    /**
     * The source directories containing the test-source to be compiled.
     */
    @Parameter
    private List<String> compileSourceRoots;

    /**
     * The directory where compiled test classes go.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private Path mainOutputDirectory;

    /**
     * The directory where compiled test classes go.
     * <p>
     * This parameter should only be modified in special cases.
     * See the {@link CompilerMojo#outputDirectory} for more information.
     *
     * @see CompilerMojo#outputDirectory
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    private Path outputDirectory;

    /**
     * A list of inclusion filters for the compiler.
     */
    @Parameter
    private Set<String> testIncludes = new HashSet<>();

    /**
     * A list of exclusion filters for the compiler.
     */
    @Parameter
    private Set<String> testExcludes = new HashSet<>();

    /**
     * A list of exclusion filters for the incremental calculation.
     * @since 3.11
     */
    @Parameter
    private Set<String> testIncrementalExcludes = new HashSet<>();

    /**
     * The -source argument for the test Java compiler.
     *
     * @since 2.1
     */
    @Parameter(property = "maven.compiler.testSource")
    private String testSource;

    /**
     * The -target argument for the test Java compiler.
     *
     * @since 2.1
     */
    @Parameter(property = "maven.compiler.testTarget")
    private String testTarget;

    /**
     * the -release argument for the test Java compiler
     *
     * @since 3.6
     */
    @Parameter(property = "maven.compiler.testRelease")
    private String testRelease;

    /**
     * <p>
     * Sets the arguments to be passed to test compiler (prepending a dash) if fork is set to true.
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler
     * varies based on the compiler version.
     * </p>
     *
     * @since 2.1
     */
    @Parameter
    private Map<String, String> testCompilerArguments;

    /**
     * <p>
     * Sets the unformatted argument string to be passed to test compiler if fork is set to true.
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler
     * varies based on the compiler version.
     * </p>
     *
     * @since 2.1
     */
    @Parameter
    private String testCompilerArgument;

    /**
     * <p>
     * Specify where to place generated source files created by annotation processing.
     * Only applies to JDK 1.6+
     * </p>
     *
     * @since 2.2
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/test-annotations")
    private Path generatedTestSourcesDirectory;

    /**
     * <p>
     * When {@code true}, uses the module path when compiling with a release or target of 9+ and
     * <em>module-info.java</em> or <em>module-info.class</em> is present.
     * When {@code false}, always uses the class path.
     * </p>
     *
     * @since 3.11
     */
    @Parameter(defaultValue = "true")
    private boolean useModulePath;

    @Parameter
    private List<String> testPath;

    /**
     * when forking and debug activated the commandline used will be dumped in this file
     * @since 3.10.0
     */
    @Parameter(defaultValue = "javac-test")
    private String debugFileName;

    final LocationManager locationManager = new LocationManager();

    private Map<String, JavaModuleDescriptor> pathElements;

    private List<String> classpathElements;

    private List<String> modulepathElements;

    public void execute() throws MojoException {
        if (skip) {
            getLog().info("Not compiling test sources");
            return;
        }
        super.execute();
    }

    protected List<Path> getCompileSourceRoots() {
        if (compileSourceRoots == null || compileSourceRoots.isEmpty()) {
            return projectManager.getCompileSourceRoots(getProject(), ProjectScope.TEST);
        } else {
            return compileSourceRoots.stream().map(Paths::get).collect(Collectors.toList());
        }
    }

    @Override
    protected Map<String, JavaModuleDescriptor> getPathElements() {
        return pathElements;
    }

    protected List<String> getClasspathElements() {
        return classpathElements;
    }

    @Override
    protected List<String> getModulepathElements() {
        return modulepathElements;
    }

    protected Path getOutputDirectory() {
        return outputDirectory;
    }

    @Override
    protected void preparePaths(Set<Path> sourceFiles) {
        List<String> testPath = this.testPath;
        if (testPath == null) {
            Stream<String> s1 = Stream.of(outputDirectory.toString(), mainOutputDirectory.toString());
            Stream<String> s2 = session.resolveDependencies(getProject(), PathScope.TEST_COMPILE).stream()
                    .map(Path::toString);
            testPath = Stream.concat(s1, s2).collect(Collectors.toList());
        }

        Path mainOutputDirectory = Paths.get(getProject().getBuild().getOutputDirectory());

        Path mainModuleDescriptorClassFile = mainOutputDirectory.resolve("module-info.class");
        JavaModuleDescriptor mainModuleDescriptor = null;

        Path testModuleDescriptorJavaFile = Paths.get("module-info.java");
        JavaModuleDescriptor testModuleDescriptor = null;

        // Go through the source files to respect includes/excludes
        for (Path sourceFile : sourceFiles) {
            // @todo verify if it is the root of a sourcedirectory?
            if ("module-info.java".equals(sourceFile.getFileName().toString())) {
                testModuleDescriptorJavaFile = sourceFile;
                break;
            }
        }

        // Get additional information from the main module descriptor, if available
        if (Files.exists(mainModuleDescriptorClassFile)) {
            ResolvePathsResult<String> result;

            try {
                ResolvePathsRequest<String> request = ResolvePathsRequest.ofStrings(testPath)
                        .setIncludeStatic(true)
                        .setMainModuleDescriptor(
                                mainModuleDescriptorClassFile.toAbsolutePath().toString());

                Optional<Toolchain> toolchain = getToolchain();
                if (toolchain.isPresent() && toolchain.get() instanceof JavaToolchain) {
                    request.setJdkHome(((JavaToolchain) toolchain.get()).getJavaHome());
                }

                result = locationManager.resolvePaths(request);

                for (Entry<String, Exception> pathException :
                        result.getPathExceptions().entrySet()) {
                    Throwable cause = pathException.getValue();
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    String fileName =
                            Paths.get(pathException.getKey()).getFileName().toString();
                    getLog().warn("Can't extract module name from " + fileName + ": " + cause.getMessage());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            mainModuleDescriptor = result.getMainModuleDescriptor();

            pathElements = new LinkedHashMap<>(result.getPathElements().size());
            pathElements.putAll(result.getPathElements());

            modulepathElements = new ArrayList<>(result.getModulepathElements().keySet());
            classpathElements = new ArrayList<>(result.getClasspathElements());
        }

        // Get additional information from the test module descriptor, if available
        if (Files.exists(testModuleDescriptorJavaFile)) {
            ResolvePathsResult<String> result;

            try {
                ResolvePathsRequest<String> request = ResolvePathsRequest.ofStrings(testPath)
                        .setMainModuleDescriptor(
                                testModuleDescriptorJavaFile.toAbsolutePath().toString());

                Optional<Toolchain> toolchain = getToolchain();
                if (toolchain.isPresent() && toolchain.get() instanceof JavaToolchain) {
                    request.setJdkHome(((JavaToolchain) toolchain.get()).getJavaHome());
                }

                result = locationManager.resolvePaths(request);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            testModuleDescriptor = result.getMainModuleDescriptor();
        }

        if (release != null && !release.isEmpty()) {
            if (Integer.parseInt(release) < 9) {
                pathElements = Collections.emptyMap();
                modulepathElements = Collections.emptyList();
                classpathElements = testPath;
                return;
            }
        } else if (Double.parseDouble(getTarget()) < Double.parseDouble(MODULE_INFO_TARGET)) {
            pathElements = Collections.emptyMap();
            modulepathElements = Collections.emptyList();
            classpathElements = testPath;
            return;
        }

        if (testModuleDescriptor != null) {
            modulepathElements = testPath;
            classpathElements = Collections.emptyList();

            if (mainModuleDescriptor != null) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Main and test module descriptors exist:");
                    getLog().debug("  main module = " + mainModuleDescriptor.name());
                    getLog().debug("  test module = " + testModuleDescriptor.name());
                }

                if (testModuleDescriptor.name().equals(mainModuleDescriptor.name())) {
                    if (compilerArgs == null) {
                        compilerArgs = new ArrayList<>();
                    }
                    compilerArgs.add("--patch-module");

                    StringBuilder patchModuleValue = new StringBuilder();
                    patchModuleValue.append(testModuleDescriptor.name());
                    patchModuleValue.append('=');

                    for (Path root : projectManager.getCompileSourceRoots(getProject(), ProjectScope.MAIN)) {
                        if (Files.exists(root)) {
                            patchModuleValue.append(root).append(PS);
                        }
                    }

                    compilerArgs.add(patchModuleValue.toString());
                } else {
                    getLog().debug("Black-box testing - all is ready to compile");
                }
            } else {
                // No main binaries available? Means we're a test-only project.
                if (!Files.exists(mainOutputDirectory)) {
                    return;
                }
                // very odd
                // Means that main sources must be compiled with -modulesource and -Xmodule:<moduleName>
                // However, this has a huge impact since you can't simply use it as a classpathEntry
                // due to extra folder in between
                throw new UnsupportedOperationException(
                        "Can't compile test sources " + "when main sources are missing a module descriptor");
            }
        } else {
            if (mainModuleDescriptor != null) {
                if (compilerArgs == null) {
                    compilerArgs = new ArrayList<>();
                }
                compilerArgs.add("--patch-module");

                StringBuilder patchModuleValue = new StringBuilder(mainModuleDescriptor.name())
                        .append('=')
                        .append(mainOutputDirectory)
                        .append(PS);
                for (Path root : getCompileSourceRoots()) {
                    patchModuleValue.append(root).append(PS);
                }

                compilerArgs.add(patchModuleValue.toString());

                compilerArgs.add("--add-reads");
                compilerArgs.add(mainModuleDescriptor.name() + "=ALL-UNNAMED");
            } else {
                modulepathElements = Collections.emptyList();
                classpathElements = testPath;
            }
        }
    }

    protected SourceInclusionScanner getSourceInclusionScanner(int staleMillis) {
        SourceInclusionScanner scanner;

        if (testIncludes.isEmpty() && testExcludes.isEmpty() && testIncrementalExcludes.isEmpty()) {
            scanner = new StaleSourceScanner(staleMillis);
        } else {
            if (testIncludes.isEmpty()) {
                testIncludes.add("**/*.java");
            }
            Set<String> excludesIncr = new HashSet<>(testExcludes);
            excludesIncr.addAll(this.testIncrementalExcludes);
            scanner = new StaleSourceScanner(staleMillis, testIncludes, excludesIncr);
        }

        return scanner;
    }

    protected SourceInclusionScanner getSourceInclusionScanner(String inputFileEnding) {
        SourceInclusionScanner scanner;

        // it's not defined if we get the ending with or without the dot '.'
        String defaultIncludePattern = "**/*" + (inputFileEnding.startsWith(".") ? "" : ".") + inputFileEnding;

        if (testIncludes.isEmpty() && testExcludes.isEmpty() && testIncrementalExcludes.isEmpty()) {
            testIncludes = Collections.singleton(defaultIncludePattern);
            scanner = new SimpleSourceInclusionScanner(testIncludes, Collections.emptySet());
        } else {
            if (testIncludes.isEmpty()) {
                testIncludes.add(defaultIncludePattern);
            }
            Set<String> excludesIncr = new HashSet<>(testExcludes);
            excludesIncr.addAll(this.testIncrementalExcludes);
            scanner = new SimpleSourceInclusionScanner(testIncludes, excludesIncr);
        }

        return scanner;
    }

    protected String getSource() {
        return testSource == null ? source : testSource;
    }

    protected String getTarget() {
        return testTarget == null ? target : testTarget;
    }

    @Override
    protected String getRelease() {
        return testRelease == null ? release : testRelease;
    }

    protected String getCompilerArgument() {
        return testCompilerArgument == null ? compilerArgument : testCompilerArgument;
    }

    protected Path getGeneratedSourcesDirectory() {
        return generatedTestSourcesDirectory;
    }

    @Override
    protected String getDebugFileName() {
        return debugFileName;
    }

    @Override
    protected boolean isTestCompile() {
        return true;
    }

    @Override
    protected Set<String> getIncludes() {
        return testIncludes;
    }

    @Override
    protected Set<String> getExcludes() {
        return testExcludes;
    }
}
