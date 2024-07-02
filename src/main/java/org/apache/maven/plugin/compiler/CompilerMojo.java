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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
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

import org.apache.maven.api.*;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.codehaus.plexus.languages.java.jpms.ModuleNameSource;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsResult;
import org.codehaus.plexus.util.StringUtils;

/**
 * Compiles application sources.
 * By default uses the <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html">javac</a> compiler
 * of the JDK used to execute Maven. This can be overwritten through <a href="https://maven.apache.org/guides/mini/guide-using-toolchains.html">Toolchains</a>
 * or parameter {@link AbstractCompilerMojo#compilerId}.
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl </a>
 * @since 2.0
 * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html">javac Command</a>
 */
@Mojo(name = "compile", defaultPhase = "compile")
public class CompilerMojo extends AbstractCompilerMojo {
    /**
     * The source directories containing the sources to be compiled.
     */
    @Parameter
    protected List<String> compileSourceRoots;

    /**
     * Projects main artifact.
     */
    @Parameter(defaultValue = "${project.mainArtifact}", readonly = true, required = true)
    protected Artifact projectArtifact;

    /**
     * The directory for compiled classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
    protected Path outputDirectory;

    /**
     * A list of inclusion filters for the compiler.
     */
    @Parameter
    protected Set<String> includes = new HashSet<>();

    /**
     * A list of exclusion filters for the compiler.
     */
    @Parameter
    protected Set<String> excludes = new HashSet<>();

    /**
     * A list of exclusion filters for the incremental calculation.
     * @since 3.11
     */
    @Parameter
    protected Set<String> incrementalExcludes = new HashSet<>();

    /**
     * <p>
     * Specify where to place generated source files created by annotation processing. Only applies to JDK 1.6+
     * </p>
     *
     * @since 2.2
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/annotations")
    protected Path generatedSourcesDirectory;

    /**
     * Set this to {@code true} to bypass compilation of main sources. Its use is NOT RECOMMENDED, but quite convenient on
     * occasion.
     */
    @Parameter(property = "maven.main.skip")
    protected boolean skipMain;

    @Parameter
    protected List<String> compilePath;

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
    protected boolean multiReleaseOutput;

    /**
     * When both {@link AbstractCompilerMojo#fork} and {@link AbstractCompilerMojo#debug} are enabled the commandline arguments used
     * will be dumped to this file.
     * @since 3.10.0
     */
    @Parameter(defaultValue = "javac")
    protected String debugFileName;

    final LocationManager locationManager = new LocationManager();

    private List<String> classpathElements;

    private List<String> modulepathElements;

    private Map<String, JavaModuleDescriptor> pathElements;

    protected List<Path> getCompileSourceRoots() {
        if (compileSourceRoots == null || compileSourceRoots.isEmpty()) {
            return projectManager.getCompileSourceRoots(getProject(), ProjectScope.MAIN);
        } else {
            return compileSourceRoots.stream().map(Paths::get).collect(Collectors.toList());
        }
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

    protected Path getOutputDirectory() {
        Path dir;
        if (!multiReleaseOutput) {
            dir = outputDirectory;
        } else {
            dir = outputDirectory.resolve("META-INF/versions/" + release);
        }
        return dir;
    }

    public void execute() throws MojoException {
        if (skipMain) {
            getLog().info("Not compiling main sources");
            return;
        }

        if (multiReleaseOutput && release == null) {
            throw new MojoException("When using 'multiReleaseOutput' the release must be set");
        }

        super.execute();

        if (Files.isDirectory(outputDirectory) && projectArtifact != null) {
            artifactManager.setPath(projectArtifact, outputDirectory);
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
    protected void preparePaths(Set<Path> sourceFiles) {
        // assert compilePath != null;
        List<String> compilePath = this.compilePath;
        if (compilePath == null) {
            Stream<String> s1 = Stream.of(getOutputDirectory().toString());
            Stream<String> s2 = session.resolveDependencies(getProject(), PathScope.MAIN_COMPILE).stream()
                    .map(Path::toString);
            compilePath = Stream.concat(s1, s2).toList();
        }

        Path moduleDescriptorPath = null;

        boolean hasModuleDescriptor = false;
        for (Path sourceFile : sourceFiles) {
            if ("module-info.java".equals(sourceFile.getFileName().toString())) {
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
                Collection<File> dependencyArtifacts = getCompileClasspathElements(getProject()).stream()
                        .map(Path::toFile)
                        .collect(Collectors.toList());

                ResolvePathsRequest<File> request = ResolvePathsRequest.ofFiles(dependencyArtifacts)
                        .setIncludeStatic(true)
                        .setMainModuleDescriptor(moduleDescriptorPath.toFile());

                Optional<Toolchain> toolchain = getToolchain();
                if (toolchain.isPresent() && toolchain.get() instanceof JavaToolchain) {
                    request.setJdkHome(new File(((JavaToolchain) toolchain.get()).getJavaHome()));
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
                        if (getOutputDirectory().startsWith(file.getPath())) {
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
            for (Path element : getCompileClasspathElements(getProject())) {
                classpathElements.add(element.toString());
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

    private List<Path> getCompileClasspathElements(Project project) {
        List<Path> artifacts = session.resolveDependencies(project, PathScope.MAIN_COMPILE);

        // 3 is outputFolder + 2 preserved for multirelease
        List<Path> list = new ArrayList<>(artifacts.size() + 3);

        if (multiReleaseOutput) {
            Path versionsFolder = outputDirectory.resolve("META-INF/versions");

            // in reverse order
            for (int version = Integer.parseInt(getRelease()) - 1; version >= 9; version--) {
                Path versionSubFolder = versionsFolder.resolve(String.valueOf(version));
                if (Files.exists(versionSubFolder)) {
                    list.add(versionSubFolder);
                }
            }
        }

        list.add(outputDirectory);

        list.addAll(artifacts);

        return list;
    }

    protected SourceInclusionScanner getSourceInclusionScanner(int staleMillis) {
        if (includes.isEmpty() && excludes.isEmpty() && incrementalExcludes.isEmpty()) {
            return new StaleSourceScanner(staleMillis);
        }

        if (includes.isEmpty()) {
            includes.add("**/*.java");
        }

        return new StaleSourceScanner(staleMillis, includes, add(excludes, incrementalExcludes));
    }

    protected SourceInclusionScanner getSourceInclusionScanner(String inputFileEnding) {
        // it's not defined if we get the ending with or without the dot '.'
        String defaultIncludePattern = "**/*" + (inputFileEnding.startsWith(".") ? "" : ".") + inputFileEnding;

        if (includes.isEmpty()) {
            includes.add(defaultIncludePattern);
        }
        return new SimpleSourceInclusionScanner(includes, add(excludes, incrementalExcludes));
    }

    @Override
    protected String getSource() {
        return source;
    }

    @Override
    protected String getTarget() {
        return target;
    }

    @Override
    protected String getRelease() {
        return release;
    }

    @Override
    protected String getCompilerArgument() {
        return compilerArgument;
    }

    protected Path getGeneratedSourcesDirectory() {
        return generatedSourcesDirectory;
    }

    @Override
    protected String getDebugFileName() {
        return debugFileName;
    }

    private void writeBoxedWarning(String message) {
        String line = StringUtils.repeat("*", message.length() + 4);
        getLog().warn(line);
        getLog().warn("* " + strong(message) + " *");
        getLog().warn(line);
    }

    private String strong(String message) {
        return session.getService(MessageBuilderFactory.class)
                .builder()
                .strong(message)
                .build();
    }
}
