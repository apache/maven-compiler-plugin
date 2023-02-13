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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.java.DefaultJavaToolChain;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.codehaus.plexus.languages.java.jpms.ModuleNameSource;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsResult;

/**
 * Compiles application sources
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl </a>
 * @since 2.0
 */
@Mojo(
        name = "compile",
        defaultPhase = LifecyclePhase.COMPILE,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class CompilerMojo extends AbstractCompilerMojo {
    /**
     * The source directories containing the sources to be compiled.
     */
    @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = false, required = true)
    private List<String> compileSourceRoots;

    /**
     * The directory for compiled classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
    private File outputDirectory;

    /**
     * Projects main artifact.
     *
     * @todo this is an export variable, really
     */
    @Parameter(defaultValue = "${project.artifact}", readonly = true, required = true)
    private Artifact projectArtifact;

    /**
     * A list of inclusion filters for the compiler.
     */
    @Parameter
    private Set<String> includes = new HashSet<>();

    /**
     * A list of exclusion filters for the compiler.
     */
    @Parameter
    private Set<String> excludes = new HashSet<>();

    /**
     * A list of exclusion filters for the incremental calculation.
     * @since 3.11
     */
    @Parameter
    private Set<String> incrementalExcludes = new HashSet<>();

    /**
     * <p>
     * Specify where to place generated source files created by annotation processing. Only applies to JDK 1.6+
     * </p>
     *
     * @since 2.2
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/annotations")
    private File generatedSourcesDirectory;

    /**
     * Set this to 'true' to bypass compilation of main sources. Its use is NOT RECOMMENDED, but quite convenient on
     * occasion.
     */
    @Parameter(property = "maven.main.skip")
    private boolean skipMain;

    @Parameter(defaultValue = "${project.compileClasspathElements}", readonly = true, required = true)
    private List<String> compilePath;

    /**
     * <p>
     * When set to {@code true}, the classes will be placed in <code>META-INF/versions/${release}</code> The release
     * value must be set, otherwise the plugin will fail.
     * </p>
     * <strong>Note: </strong> A jar is only a multirelease jar if <code>META-INF/MANIFEST.MF</code> contains
     * <code>Multi-Release: true</code>. You need to set this by configuring the <a href=
     * "https://maven.apache.org/plugins/maven-jar-plugin/examples/manifest-customization.html">maven-jar-plugin</a>.
     * This implies that you cannot test a multirelease jar using the outputDirectory.
     *
     * @since 3.7.1
     */
    @Parameter
    private boolean multiReleaseOutput;

    /**
     * when forking and debug activated the commandline used will be dumped in this file
     * @since 3.10.0
     */
    @Parameter(defaultValue = "javac")
    private String debugFileName;

    final LocationManager locationManager = new LocationManager();

    private List<String> classpathElements;

    private List<String> modulepathElements;

    private Map<String, JavaModuleDescriptor> pathElements;

    protected List<String> getCompileSourceRoots() {
        return compileSourceRoots;
    }

    @Override
    protected List<String> getClasspathElements() {
        return classpathElements;
    }

    @Override
    protected List<String> getModulepathElements() {
        return modulepathElements;
    }

    @Override
    protected Map<String, JavaModuleDescriptor> getPathElements() {
        return pathElements;
    }

    protected File getOutputDirectory() {
        File dir;
        if (!multiReleaseOutput) {
            dir = outputDirectory;
        } else {
            dir = new File(outputDirectory, "META-INF/versions/" + release);
        }
        return dir;
    }

    public void execute() throws MojoExecutionException, CompilationFailureException {
        if (skipMain) {
            getLog().info("Not compiling main sources");
            return;
        }

        if (multiReleaseOutput && release == null) {
            throw new MojoExecutionException("When using 'multiReleaseOutput' the release must be set");
        }

        super.execute();

        if (outputDirectory.isDirectory()) {
            projectArtifact.setFile(outputDirectory);
        }
    }

    @Override
    protected Set<String> getIncludes() {
        return includes;
    }

    @Override
    protected Set<String> getExcludes() {
        return excludes;
    }

    @Override
    protected void preparePaths(Set<File> sourceFiles) {
        // assert compilePath != null;

        File moduleDescriptorPath = null;

        boolean hasModuleDescriptor = false;
        for (File sourceFile : sourceFiles) {
            if ("module-info.java".equals(sourceFile.getName())) {
                moduleDescriptorPath = sourceFile;
                hasModuleDescriptor = true;
                break;
            }
        }

        if (hasModuleDescriptor) {
            // For now only allow named modules. Once we can create a graph with ASM we can specify exactly the modules
            // and we can detect if auto modules are used. In that case, MavenProject.setFile() should not be used, so
            // you cannot depend on this project and so it won't be distributed.

            modulepathElements = new ArrayList<>(compilePath.size());
            classpathElements = new ArrayList<>(compilePath.size());
            pathElements = new LinkedHashMap<>(compilePath.size());

            ResolvePathsResult<File> resolvePathsResult;
            try {
                Collection<File> dependencyArtifacts = getCompileClasspathElements(getProject());

                ResolvePathsRequest<File> request = ResolvePathsRequest.ofFiles(dependencyArtifacts)
                        .setIncludeStatic(true)
                        .setMainModuleDescriptor(moduleDescriptorPath);

                Toolchain toolchain = getToolchain();
                if (toolchain instanceof DefaultJavaToolChain) {
                    request.setJdkHome(new File(((DefaultJavaToolChain) toolchain).getJavaHome()));
                }

                resolvePathsResult = locationManager.resolvePaths(request);

                for (Entry<File, Exception> pathException :
                        resolvePathsResult.getPathExceptions().entrySet()) {
                    Throwable cause = pathException.getValue();
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    String fileName = pathException.getKey().getName();
                    getLog().warn("Can't extract module name from " + fileName + ": " + cause.getMessage());
                }

                JavaModuleDescriptor moduleDescriptor = resolvePathsResult.getMainModuleDescriptor();

                detectFilenameBasedAutomodules(resolvePathsResult, moduleDescriptor);

                for (Map.Entry<File, JavaModuleDescriptor> entry :
                        resolvePathsResult.getPathElements().entrySet()) {
                    pathElements.put(entry.getKey().getPath(), entry.getValue());
                }

                if (compilerArgs == null) {
                    compilerArgs = new ArrayList<>();
                }

                for (File file : resolvePathsResult.getClasspathElements()) {
                    classpathElements.add(file.getPath());

                    if (multiReleaseOutput) {
                        if (getOutputDirectory().toPath().startsWith(file.getPath())) {
                            compilerArgs.add("--patch-module");
                            compilerArgs.add(String.format("%s=%s", moduleDescriptor.name(), file.getPath()));
                        }
                    }
                }

                for (File file : resolvePathsResult.getModulepathElements().keySet()) {
                    modulepathElements.add(file.getPath());
                }

                compilerArgs.add("--module-version");
                compilerArgs.add(getProject().getVersion());

            } catch (IOException e) {
                getLog().warn(e.getMessage());
            }
        } else {
            classpathElements = new ArrayList<>();
            for (File element : getCompileClasspathElements(getProject())) {
                classpathElements.add(element.getPath());
            }
            modulepathElements = Collections.emptyList();
        }
    }

    private void detectFilenameBasedAutomodules(
            final ResolvePathsResult<File> resolvePathsResult, final JavaModuleDescriptor moduleDescriptor) {
        List<String> automodulesDetected = new ArrayList<>();
        for (Entry<File, ModuleNameSource> entry :
                resolvePathsResult.getModulepathElements().entrySet()) {
            if (ModuleNameSource.FILENAME.equals(entry.getValue())) {
                automodulesDetected.add(entry.getKey().getName());
            }
        }

        if (!automodulesDetected.isEmpty()) {
            final String message = "Required filename-based automodules detected: "
                    + automodulesDetected + ". "
                    + "Please don't publish this project to a public artifact repository!";

            if (moduleDescriptor.exports().isEmpty()) {
                // application
                getLog().info(message);
            } else {
                // library
                writeBoxedWarning(message);
            }
        }
    }

    private List<File> getCompileClasspathElements(MavenProject project) {
        // 3 is outputFolder + 2 preserved for multirelease
        List<File> list = new ArrayList<>(project.getArtifacts().size() + 3);

        if (multiReleaseOutput) {
            File versionsFolder = new File(project.getBuild().getOutputDirectory(), "META-INF/versions");

            // in reverse order
            for (int version = Integer.parseInt(getRelease()) - 1; version >= 9; version--) {
                File versionSubFolder = new File(versionsFolder, String.valueOf(version));
                if (versionSubFolder.exists()) {
                    list.add(versionSubFolder);
                }
            }
        }

        list.add(new File(project.getBuild().getOutputDirectory()));

        for (Artifact a : project.getArtifacts()) {
            list.add(a.getFile());
        }
        return list;
    }

    protected SourceInclusionScanner getSourceInclusionScanner(int staleMillis) {
        if (includes.isEmpty() && excludes.isEmpty() && incrementalExcludes.isEmpty()) {
            return new StaleSourceScanner(staleMillis);
        }

        if (includes.isEmpty()) {
            includes.add("**/*.java");
        }

        Set<String> excludesIncr = new HashSet<>(excludes);
        excludesIncr.addAll(this.incrementalExcludes);
        return new StaleSourceScanner(staleMillis, includes, excludesIncr);
    }

    protected SourceInclusionScanner getSourceInclusionScanner(String inputFileEnding) {
        // it's not defined if we get the ending with or without the dot '.'
        String defaultIncludePattern = "**/*" + (inputFileEnding.startsWith(".") ? "" : ".") + inputFileEnding;

        if (includes.isEmpty()) {
            includes.add(defaultIncludePattern);
        }
        Set<String> excludesIncr = new HashSet<>(excludes);
        excludesIncr.addAll(excludesIncr);
        return new SimpleSourceInclusionScanner(includes, excludesIncr);
    }

    protected String getSource() {
        return source;
    }

    protected String getTarget() {
        return target;
    }

    @Override
    protected String getRelease() {
        return release;
    }

    protected String getCompilerArgument() {
        return compilerArgument;
    }

    protected Map<String, String> getCompilerArguments() {
        return compilerArguments;
    }

    protected File getGeneratedSourcesDirectory() {
        return generatedSourcesDirectory;
    }

    @Override
    protected String getDebugFileName() {
        return debugFileName;
    }

    private void writeBoxedWarning(String message) {
        String line = StringUtils.repeat("*", message.length() + 4);
        getLog().warn(line);
        getLog().warn("* " + MessageUtils.buffer().strong(message) + " *");
        getLog().warn(line);
    }
}
