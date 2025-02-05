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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.java.DefaultJavaToolChain;
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
@Mojo(
        name = "testCompile",
        defaultPhase = LifecyclePhase.TEST_COMPILE,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.TEST)
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
    @Parameter(defaultValue = "${project.testCompileSourceRoots}", readonly = false, required = true)
    private List<String> compileSourceRoots;

    /**
     * The directory where compiled test classes go.
     * <p>
     * This parameter should only be modified in special cases.
     * See the {@link CompilerMojo#outputDirectory} for more information.
     *
     * @see CompilerMojo#outputDirectory
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true, readonly = false)
    private File outputDirectory;

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
    private File generatedTestSourcesDirectory;

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

    @Parameter(defaultValue = "${project.testClasspathElements}", readonly = true)
    private List<String> testPath;

    /**
     * when forking and debug activated the commandline used will be dumped in this file
     * @since 3.10.0
     */
    @Parameter(defaultValue = "javac-test")
    private String debugFileName;

    final LocationManager locationManager = new LocationManager();

    private Map<String, JavaModuleDescriptor> pathElements;

    private Collection<String> classpathElements;

    private Collection<String> modulepathElements;

    public void execute() throws MojoExecutionException, CompilationFailureException {
        if (skip) {
            getLog().info("Not compiling test sources");
            return;
        }
        super.execute();
    }

    protected List<String> getCompileSourceRoots() {
        return compileSourceRoots;
    }

    @Override
    protected Map<String, JavaModuleDescriptor> getPathElements() {
        return pathElements;
    }

    protected List<String> getClasspathElements() {
        return new ArrayList<>(classpathElements);
    }

    @Override
    protected List<String> getModulepathElements() {
        return new ArrayList<>(modulepathElements);
    }

    protected File getOutputDirectory() {
        return outputDirectory;
    }

    @Override
    @SuppressWarnings("checkstyle:MethodLength")
    protected void preparePaths(Set<File> sourceFiles) {
        File mainOutputDirectory = new File(getProject().getBuild().getOutputDirectory());

        File mainModuleDescriptorClassFile = new File(mainOutputDirectory, "module-info.class");
        JavaModuleDescriptor mainModuleDescriptor = null;

        File testModuleDescriptorJavaFile = new File("module-info.java");
        JavaModuleDescriptor testModuleDescriptor = null;

        // Go through the source files to respect includes/excludes
        for (File sourceFile : sourceFiles) {
            // @todo verify if it is the root of a sourcedirectory?
            if ("module-info.java".equals(sourceFile.getName())) {
                testModuleDescriptorJavaFile = sourceFile;
                break;
            }
        }

        // Get additional information from the main module descriptor, if available
        if (mainModuleDescriptorClassFile.exists()) {
            ResolvePathsResult<String> result;

            try {
                ResolvePathsRequest<String> request = ResolvePathsRequest.ofStrings(testPath)
                        .setIncludeStatic(true)
                        .setMainModuleDescriptor(mainModuleDescriptorClassFile.getAbsolutePath());

                Toolchain toolchain = getToolchain();
                if (toolchain instanceof DefaultJavaToolChain) {
                    request.setJdkHome(((DefaultJavaToolChain) toolchain).getJavaHome());
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

            modulepathElements = result.getModulepathElements().keySet();
            classpathElements = result.getClasspathElements();
        }

        // Get additional information from the test module descriptor, if available
        if (testModuleDescriptorJavaFile.exists()) {
            ResolvePathsResult<String> result;

            try {
                ResolvePathsRequest<String> request = ResolvePathsRequest.ofStrings(testPath)
                        .setMainModuleDescriptor(testModuleDescriptorJavaFile.getAbsolutePath());

                Toolchain toolchain = getToolchain();
                if (toolchain instanceof DefaultJavaToolChain) {
                    request.setJdkHome(((DefaultJavaToolChain) toolchain).getJavaHome());
                }

                result = locationManager.resolvePaths(request);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            testModuleDescriptor = result.getMainModuleDescriptor();
        }

        if (!useModulePath) {
            pathElements = Collections.emptyMap();
            modulepathElements = Collections.emptyList();
            classpathElements = testPath;
            return;
        }
        if (StringUtils.isNotEmpty(getRelease())) {
            if (isOlderThanJDK9(getRelease())) {
                pathElements = Collections.emptyMap();
                modulepathElements = Collections.emptyList();
                classpathElements = testPath;
                return;
            }
        } else if (isOlderThanJDK9(getTarget())) {
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

                    for (String root : getProject().getCompileSourceRoots()) {
                        if (Files.exists(Paths.get(root))) {
                            patchModuleValue.append(root).append(PS);
                        }
                    }

                    compilerArgs.add(patchModuleValue.toString());
                } else {
                    getLog().debug("Black-box testing - all is ready to compile");
                }
            } else {
                // No main binaries available? Means we're a test-only project.
                if (!mainOutputDirectory.exists()) {
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
                for (String root : compileSourceRoots) {
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

    static boolean isOlderThanJDK9(String version) {
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2)) < 9;
        }

        return Integer.parseInt(version) < 9;
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

    protected Map<String, String> getCompilerArguments() {
        return testCompilerArguments == null ? compilerArguments : testCompilerArguments;
    }

    protected File getGeneratedSourcesDirectory() {
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
