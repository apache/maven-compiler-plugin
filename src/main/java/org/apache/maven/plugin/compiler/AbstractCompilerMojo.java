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
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.api.*;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.DependencyCoordinatesFactory;
import org.apache.maven.api.services.DependencyCoordinatesFactoryRequest;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.ToolchainManager;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.CompilerResult;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SingleTargetSourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.languages.java.version.JavaVersion;
import org.codehaus.plexus.logging.AbstractLogger;
import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * TODO: At least one step could be optimized, currently the plugin will do two
 * scans of all the source code if the compiler has to have the entire set of
 * sources. This is currently the case for at least the C# compiler and most
 * likely all the other .NET compilers too.
 *
 * @author others
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 * @since 2.0
 */
public abstract class AbstractCompilerMojo implements Mojo {
    protected static final String PS = File.pathSeparator;

    private static final String INPUT_FILES_LST_FILENAME = "inputFiles.lst";

    static final String DEFAULT_SOURCE = "1.8";

    static final String DEFAULT_TARGET = "1.8";

    // Used to compare with older targets
    static final String MODULE_INFO_TARGET = "1.9";

    // ----------------------------------------------------------------------
    // Configurables
    // ----------------------------------------------------------------------

    /**
     * Indicates whether the build will continue even if there are compilation errors.
     *
     * @since 2.0.2
     */
    @Parameter(property = "maven.compiler.failOnError", defaultValue = "true")
    protected boolean failOnError = true;

    /**
     * Indicates whether the build will continue even if there are compilation warnings.
     *
     * @since 3.6
     */
    @Parameter(property = "maven.compiler.failOnWarning", defaultValue = "false")
    protected boolean failOnWarning;

    /**
     * Set to <code>true</code> to include debugging information in the compiled class files.
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-g">javac -g</a>
     * @see #debuglevel
     */
    @Parameter(property = "maven.compiler.debug", defaultValue = "true")
    protected boolean debug = true;

    /**
     * Set to <code>true</code> to generate metadata for reflection on method parameters.
     * @since 3.6.2
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-parameters">javac -parameters</a>
     */
    @Parameter(property = "maven.compiler.parameters", defaultValue = "false")
    protected boolean parameters;

    /**
     * Set to <code>true</code> to enable preview language features of the java compiler
     * @since 3.10.1
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-enable-preview">javac --enable-preview</a>
     */
    @Parameter(property = "maven.compiler.enablePreview", defaultValue = "false")
    protected boolean enablePreview;

    /**
     * Set to <code>true</code> to show messages about what the compiler is doing.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-verbose">javac -verbose</a>
     */
    @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
    protected boolean verbose;

    /**
     * Sets whether to show source locations where deprecated APIs are used.
     */
    @Parameter(property = "maven.compiler.showDeprecation", defaultValue = "false")
    protected boolean showDeprecation;

    /**
     * Set to <code>true</code> to optimize the compiled code using the compiler's optimization methods.
     * @deprecated This property is a no-op in {@code javac}.
     */
    @Deprecated
    @Parameter(property = "maven.compiler.optimize", defaultValue = "false")
    protected boolean optimize;

    /**
     * Set to <code>false</code> to disable warnings during compilation.
     */
    @Parameter(property = "maven.compiler.showWarnings", defaultValue = "true")
    protected boolean showWarnings;

    /**
     * <p>The {@code -source} argument for the Java compiler.</p>
     *
     * <p><b>NOTE: </b></p>
     * <p>Since 3.8.0 the default value has changed from 1.5 to 1.6</p>
     * <p>Since 3.9.0 the default value has changed from 1.6 to 1.7</p>
     * <p>Since 3.11.0 the default value has changed from 1.7 to 1.8</p>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-source">javac -source</a>
     */
    @Parameter(property = "maven.compiler.source", defaultValue = DEFAULT_SOURCE)
    protected String source;

    /**
     * <p>The {@code -target} argument for the Java compiler.</p>
     *
     * <p><b>NOTE: </b></p>
     * <p>Since 3.8.0 the default value has changed from 1.5 to 1.6</p>
     * <p>Since 3.9.0 the default value has changed from 1.6 to 1.7</p>
     * <p>Since 3.11.0 the default value has changed from 1.7 to 1.8</p>
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-target">javac -target</a>
     */
    @Parameter(property = "maven.compiler.target", defaultValue = DEFAULT_TARGET)
    protected String target;

    /**
     * The {@code -release} argument for the Java compiler, supported since Java9
     *
     * @since 3.6
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-release">javac -release</a>
     */
    @Parameter(property = "maven.compiler.release")
    protected String release;

    /**
     * The {@code -encoding} argument for the Java compiler.
     *
     * @since 2.1
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-encoding">javac -encoding</a>
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
    protected String encoding;

    /**
     * Sets the granularity in milliseconds of the last modification
     * date for testing whether a source needs recompilation.
     */
    @Parameter(property = "lastModGranularityMs", defaultValue = "0")
    protected int staleMillis;

    /**
     * The compiler id of the compiler to use. See this
     * <a href="non-javac-compilers.html">guide</a> for more information.
     */
    @Parameter(property = "maven.compiler.compilerId", defaultValue = "javac")
    protected String compilerId;

    /**
     * Version of the compiler to use, ex. "1.3", "1.5", if {@link #fork} is set to <code>true</code>.
     * @deprecated This parameter is no longer evaluated by the underlying compilers, instead the actual
     * version of the {@code javac} binary is automatically retrieved.
     */
    @Deprecated
    @Parameter(property = "maven.compiler.compilerVersion")
    protected String compilerVersion;

    /**
     * Allows running the compiler in a separate process.
     * If <code>false</code> it uses the built in compiler, while if <code>true</code> it will use an executable.
     */
    @Parameter(property = "maven.compiler.fork", defaultValue = "false")
    protected boolean fork;

    /**
     * Initial size, in megabytes, of the memory allocation pool, ex. "64", "64m"
     * if {@link #fork} is set to <code>true</code>.
     *
     * @since 2.0.1
     */
    @Parameter(property = "maven.compiler.meminitial")
    protected String meminitial;

    /**
     * Sets the maximum size, in megabytes, of the memory allocation pool, ex. "128", "128m"
     * if {@link #fork} is set to <code>true</code>.
     *
     * @since 2.0.1
     */
    @Parameter(property = "maven.compiler.maxmem")
    protected String maxmem;

    /**
     * Sets the executable of the compiler to use when {@link #fork} is <code>true</code>.
     */
    @Parameter(property = "maven.compiler.executable")
    protected String executable;

    /**
     * <p>
     * Sets whether annotation processing is performed or not. Only applies to JDK 1.6+
     * If not set, both compilation and annotation processing are performed at the same time.
     * </p>
     * <p>Allowed values are:</p>
     * <ul>
     * <li><code>none</code> - no annotation processing is performed.</li>
     * <li><code>only</code> - only annotation processing is done, no compilation.</li>
     * <li><code>full</code> - annotation processing and compilation.</li>
     * </ul>
     *
     * <code>full</code> is the default. Starting with JDK 21, this option must be set explicitly.
     *
     * @since 2.2
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-proc">javac -proc</a>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#annotation-processing">javac Annotation Processing</a>
     */
    @Parameter(property = "maven.compiler.proc")
    protected String proc;

    /**
     * <p>
     * Names of annotation processors to run. Only applies to JDK 1.6+
     * If not set, the default annotation processors discovery process applies.
     * </p>
     *
     * @since 2.2
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-processor">javac -processor</a>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#annotation-processing">javac Annotation Processing</a>
     */
    @Parameter
    protected String[] annotationProcessors;

    /**
     * <p>
     * Classpath elements to supply as annotation processor path. If specified, the compiler will detect annotation
     * processors only in those classpath elements. If omitted, the default classpath is used to detect annotation
     * processors. The detection itself depends on the configuration of {@code annotationProcessors}.
     * </p>
     * <p>
     * Each classpath element is specified using their Maven coordinates (groupId, artifactId, version, classifier,
     * type). Transitive dependencies are added automatically. Exclusions are supported as well. Example:
     * </p>
     *
     * <pre>
     * &lt;configuration&gt;
     *   &lt;annotationProcessorPaths&gt;
     *     &lt;path&gt;
     *       &lt;groupId&gt;org.sample&lt;/groupId&gt;
     *       &lt;artifactId&gt;sample-annotation-processor&lt;/artifactId&gt;
     *       &lt;version&gt;1.2.3&lt;/version&gt; &lt;!-- Optional - taken from dependency management if not specified --&gt;
     *       &lt;!-- Optionally exclude transitive dependencies --&gt;
     *       &lt;exclusions&gt;
     *         &lt;exclusion&gt;
     *           &lt;groupId&gt;org.sample&lt;/groupId&gt;
     *           &lt;artifactId&gt;sample-dependency&lt;/artifactId&gt;
     *         &lt;/exclusion&gt;
     *       &lt;/exclusions&gt;
     *     &lt;/path&gt;
     *     &lt;!-- ... more ... --&gt;
     *   &lt;/annotationProcessorPaths&gt;
     * &lt;/configuration&gt;
     * </pre>
     *
     * <b>Note:</b> Exclusions are supported from version 3.11.0.
     *
     * @since 3.5
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-processor-path">javac -processorpath</a>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#annotation-processing">javac Annotation Processing</a>
     *
     */
    @Parameter
    protected List<DependencyCoordinates> annotationProcessorPaths;

    /**
     * <p>
     * Whether to use the Maven dependency management section when resolving transitive dependencies of annotation
     * processor paths.
     * </p>
     * <p>
     * This flag does not enable / disable the ability to resolve the version of annotation processor paths
     * from dependency management section. It only influences the resolution of transitive dependencies of those
     * top-level paths.
     * </p>
     *
     * @since 3.12.0
     */
    @Parameter(defaultValue = "false")
    protected boolean annotationProcessorPathsUseDepMgmt;

    /**
     * <p>
     * Sets the arguments to be passed to the compiler.
     * </p>
     * <p>
     * Note that {@code -J} options are only passed through if {@link #fork} is set to {@code true}.
     * </p>
     * Example:
     * <pre>
     * &lt;compilerArgs&gt;
     *   &lt;arg&gt;-Xmaxerrs&lt;/arg&gt;
     *   &lt;arg&gt;1000&lt;/arg&gt;
     *   &lt;arg&gt;-Xlint&lt;/arg&gt;
     *   &lt;arg&gt;-J-Duser.language=en_us&lt;/arg&gt;
     * &lt;/compilerArgs&gt;
     * </pre>
     *
     * @since 3.1
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-J">javac -J</a>
     */
    @Parameter
    protected List<String> compilerArgs;

    /**
     * <p>
     * Sets the unformatted single argument string to be passed to the compiler. To pass multiple arguments such as
     * <code>-Xmaxerrs 1000</code> (which are actually two arguments) you have to use {@link #compilerArgs}.
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler varies based on the compiler version.
     * </p>
     * <p>
     * Note that {@code -J} options are only passed through if {@link #fork} is set to {@code true}.
     * </p>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-J">javac -J</a>
     */
    @Parameter
    protected String compilerArgument;

    /**
     * Sets the name of the output file when compiling a set of
     * sources to a single file.
     * <p/>
     * expression="${project.build.finalName}"
     */
    @Parameter
    private String outputFileName;

    /**
     * Keyword list to be appended to the <code>-g</code> command-line switch. Legal values are none or a
     * comma-separated list of the following keywords: <code>lines</code>, <code>vars</code>, and <code>source</code>.
     * If debug level is not specified, by default, nothing will be appended to <code>-g</code>.
     * If {@link #debug} is not turned on, this attribute will be ignored.
     *
     * @since 2.1
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-g-custom">javac -G:[lines,vars,source]</a>
     */
    @Parameter(property = "maven.compiler.debuglevel")
    private String debuglevel;

    /**
     * Keyword to be appended to the <code>-implicit:</code> command-line switch.
     *
     * @since 3.10.2
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-implicit">javac -implicit</a>
     */
    @Parameter(property = "maven.compiler.implicit")
    protected String implicit;

    /**
     * <p>
     * Specify the requirements for this jdk toolchain for using a different {@code javac} than the one of the JRE used
     * by Maven. This overrules the toolchain selected by the
     * <a href="https://maven.apache.org/plugins/maven-toolchains-plugin/">maven-toolchain-plugin</a>.
     * </p>
     * (see <a href="https://maven.apache.org/guides/mini/guide-using-toolchains.html"> Guide to Toolchains</a> for more
     * info)
     *
     * <pre>
     * &lt;configuration&gt;
     *   &lt;jdkToolchain&gt;
     *     &lt;version&gt;11&lt;/version&gt;
     *   &lt;/jdkToolchain&gt;
     *   ...
     * &lt;/configuration&gt;
     *
     * &lt;configuration&gt;
     *   &lt;jdkToolchain&gt;
     *     &lt;version&gt;1.8&lt;/version&gt;
     *     &lt;vendor&gt;zulu&lt;/vendor&gt;
     *   &lt;/jdkToolchain&gt;
     *   ...
     * &lt;/configuration&gt;
     * </pre>
     * <strong>note:</strong> requires at least Maven 3.3.1
     *
     * @since 3.6
     */
    @Parameter
    protected Map<String, String> jdkToolchain;

    // ----------------------------------------------------------------------
    // Read-only parameters
    // ----------------------------------------------------------------------

    /**
     * The directory to run the compiler from if fork is true.
     */
    @Parameter(defaultValue = "${project.basedir}", required = true, readonly = true)
    protected Path basedir;

    /**
     * The target directory of the compiler if fork is true.
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    protected Path buildDirectory;

    /**
     * Plexus compiler manager.
     */
    @Inject
    protected CompilerManager compilerManager;

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Inject
    protected Session session;

    /**
     * The current project instance. This is used for propagating generated-sources paths as compile/testCompile source
     * roots.
     */
    @Inject
    protected Project project;

    /**
     * Strategy to re use javacc class created:
     * <ul>
     * <li><code>reuseCreated</code> (default): will reuse already created but in case of multi-threaded builds, each
     * thread will have its own instance</li>
     * <li><code>reuseSame</code>: the same Javacc class will be used for each compilation even for multi-threaded build
     * </li>
     * <li><code>alwaysNew</code>: a new Javacc class will be created for each compilation</li>
     * </ul>
     * Note this parameter value depends on the os/jdk you are using, but the default value should work on most of env.
     *
     * @since 2.5
     */
    @Parameter(defaultValue = "${reuseCreated}", property = "maven.compiler.compilerReuseStrategy")
    protected String compilerReuseStrategy = "reuseCreated";

    /**
     * @since 2.5
     */
    @Parameter(defaultValue = "false", property = "maven.compiler.skipMultiThreadWarning")
    protected boolean skipMultiThreadWarning;

    /**
     * The underlying compiler now uses <a href="https://docs.oracle.com/en/java/javase/17/docs/api/java.compiler/javax/tools/package-summary.html">{@code javax.tools} API</a>
     * if available in your current JDK.
     * Set this to {@code true} to always use the legacy <a href="https://docs.oracle.com/en/java/javase/17/docs/api/jdk.compiler/com/sun/tools/javac/package-summary.html">
     * {@code com.sun.tools.javac} API</a> instead.
     * <p>
     * <em>This only has an effect for {@link #compilerId} being {@code javac} and {@link #fork} being {@code false}</em>.
     *
     * @since 3.13
     */
    @Parameter(defaultValue = "false", property = "maven.compiler.forceLegacyJavacApi")
    protected boolean forceLegacyJavacApi;

    /**
     * @since 3.0 needed for storing the status for the incremental build support.
     */
    @Parameter(defaultValue = "maven-status/${mojo.plugin.descriptor.artifactId}/${mojo.goal}/${mojo.executionId}")
    protected String mojoStatusPath;

    /**
     * File extensions to check timestamp for incremental build.
     * Default contains only <code>class</code> and <code>jar</code>.
     *
     * @since 3.1
     */
    @Parameter
    protected List<String> fileExtensions;

    /**
     * <p>to enable/disable incremental compilation feature.</p>
     * <p>This leads to two different modes depending on the underlying compiler. The default javac compiler does the
     * following:</p>
     * <ul>
     * <li>true <strong>(default)</strong> in this mode the compiler plugin determines whether any JAR files the
     * current module depends on have changed in the current build run; or any source file was added, removed or
     * changed since the last compilation. If this is the case, the compiler plugin recompiles all sources.</li>
     * <li>false <strong>(not recommended)</strong> this only compiles source files which are newer than their
     * corresponding class files, namely which have changed since the last compilation. This does not
     * recompile other classes which use the changed class, potentially leaving them with references to methods that no
     * longer exist, leading to errors at runtime.</li>
     * </ul>
     *
     * @since 3.1
     */
    @Parameter(defaultValue = "true", property = "maven.compiler.useIncrementalCompilation")
    protected boolean useIncrementalCompilation = true;

    /**
     * Package info source files that only contain javadoc and no annotation on the package
     * can lead to no class file being generated by the compiler.  This causes a file miss
     * on the next compilations and forces an unnecessary recompilation. The default value
     * of <code>true</code> causes an empty class file to be generated.  This behavior can
     * be changed by setting this parameter to <code>false</code>.
     *
     * @since 3.10
     */
    @Parameter(defaultValue = "true", property = "maven.compiler.createMissingPackageInfoClass")
    protected boolean createMissingPackageInfoClass = true;

    @Parameter(defaultValue = "false", property = "maven.compiler.showCompilationChanges")
    protected boolean showCompilationChanges = false;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     * @since 3.12.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    protected String outputTimestamp;

    @Inject
    protected ProjectManager projectManager;

    @Inject
    protected ArtifactManager artifactManager;

    @Inject
    protected ToolchainManager toolchainManager;

    @Inject
    protected MessageBuilderFactory messageBuilderFactory;

    @Inject
    protected Log logger;

    protected abstract SourceInclusionScanner getSourceInclusionScanner(int staleMillis);

    protected abstract SourceInclusionScanner getSourceInclusionScanner(String inputFileEnding);

    protected abstract List<String> getClasspathElements();

    protected abstract List<String> getModulepathElements();

    protected abstract Map<String, JavaModuleDescriptor> getPathElements();

    protected abstract List<Path> getCompileSourceRoots();

    protected abstract void preparePaths(Set<Path> sourceFiles);

    protected abstract Path getOutputDirectory();

    protected abstract String getSource();

    protected abstract String getTarget();

    protected abstract String getRelease();

    protected abstract String getCompilerArgument();

    protected abstract Path getGeneratedSourcesDirectory();

    protected abstract String getDebugFileName();

    protected final Project getProject() {
        return project;
    }

    private boolean targetOrReleaseSet;

    @Override
    public void execute() {
        // ----------------------------------------------------------------------
        // Look up the compiler. This is done before other code than can
        // cause the mojo to return before the lookup is done possibly resulting
        // in misconfigured POMs still building.
        // ----------------------------------------------------------------------

        Compiler compiler;

        getLog().debug("Using compiler '" + compilerId + "'.");

        try {
            compiler = compilerManager.getCompiler(compilerId);
            if (compiler instanceof LogEnabled) {
                ((LogEnabled) compiler).enableLogging(new MavenLogger());
            }
        } catch (NoSuchCompilerException e) {
            throw new MojoException("No such compiler '" + e.getCompilerId() + "'.");
        }

        // -----------toolchains start here ----------------------------------
        // use the compilerId as identifier for toolchains as well.
        Optional<Toolchain> tc = getToolchain();
        if (tc.isPresent()) {
            getLog().info("Toolchain in maven-compiler-plugin: " + tc.get());
            if (executable != null) {
                getLog().warn("Toolchains are ignored, 'executable' parameter is set to " + executable);
            } else {
                fork = true;
                // TODO somehow shaky dependency between compilerId and tool executable.
                executable = tc.get().findTool(compilerId);
            }
        }
        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        List<Path> compileSourceRoots = removeEmptyCompileSourceRoots(getCompileSourceRoots());

        if (compileSourceRoots.isEmpty()) {
            getLog().info("No sources to compile");
            return;
        }

        // Verify that target or release is set
        if (!targetOrReleaseSet) {
            MessageBuilder mb = messageBuilderFactory
                    .builder()
                    .a("No explicit value set for target or release! ")
                    .a("To ensure the same result even after upgrading this plugin, please add ")
                    .newline()
                    .newline();

            writePlugin(mb);

            getLog().warn(mb.build());
        }

        // ----------------------------------------------------------------------
        // Create the compiler configuration
        // ----------------------------------------------------------------------

        CompilerConfiguration compilerConfiguration = new CompilerConfiguration();

        compilerConfiguration.setOutputLocation(
                getOutputDirectory().toAbsolutePath().toString());

        compilerConfiguration.setOptimize(optimize);

        compilerConfiguration.setDebug(debug);

        compilerConfiguration.setDebugFileName(getDebugFileName());

        compilerConfiguration.setImplicitOption(implicit);

        if (debug && StringUtils.isNotEmpty(debuglevel)) {
            String[] split = StringUtils.split(debuglevel, ",");
            for (String aSplit : split) {
                if (!(aSplit.equalsIgnoreCase("none")
                        || aSplit.equalsIgnoreCase("lines")
                        || aSplit.equalsIgnoreCase("vars")
                        || aSplit.equalsIgnoreCase("source"))) {
                    throw new IllegalArgumentException("The specified debug level: '" + aSplit + "' is unsupported. "
                            + "Legal values are 'none', 'lines', 'vars', and 'source'.");
                }
            }
            compilerConfiguration.setDebugLevel(debuglevel);
        }

        compilerConfiguration.setParameters(parameters);

        compilerConfiguration.setEnablePreview(enablePreview);

        compilerConfiguration.setVerbose(verbose);

        compilerConfiguration.setShowWarnings(showWarnings);

        compilerConfiguration.setFailOnWarning(failOnWarning);

        compilerConfiguration.setShowDeprecation(showDeprecation);

        compilerConfiguration.setSourceVersion(getSource());

        compilerConfiguration.setTargetVersion(getTarget());

        compilerConfiguration.setReleaseVersion(getRelease());

        compilerConfiguration.setProc(proc);

        Path generatedSourcesDirectory = getGeneratedSourcesDirectory();
        compilerConfiguration.setGeneratedSourcesDirectory(
                generatedSourcesDirectory != null
                        ? generatedSourcesDirectory.toFile().getAbsoluteFile()
                        : null);

        if (generatedSourcesDirectory != null) {
            if (!Files.exists(generatedSourcesDirectory)) {
                try {
                    Files.createDirectories(generatedSourcesDirectory);
                } catch (IOException e) {
                    throw new MojoException("Unable to create directory: " + generatedSourcesDirectory, e);
                }
            }

            Path generatedSourcesPath = generatedSourcesDirectory.toAbsolutePath();

            compileSourceRoots.add(generatedSourcesPath);

            ProjectScope scope = isTestCompile() ? ProjectScope.TEST : ProjectScope.MAIN;

            getLog().debug("Adding " + generatedSourcesPath + " to " + scope.id() + "-compile source roots:\n  "
                    + StringUtils.join(
                            projectManager.getCompileSourceRoots(project, scope).iterator(), "\n  "));

            projectManager.addCompileSourceRoot(project, scope, generatedSourcesPath);

            getLog().debug("New " + scope.id() + "-compile source roots:\n  "
                    + StringUtils.join(
                            projectManager.getCompileSourceRoots(project, scope).iterator(), "\n  "));
        }

        compilerConfiguration.setSourceLocations(
                compileSourceRoots.stream().map(Path::toString).collect(Collectors.toList()));

        compilerConfiguration.setAnnotationProcessors(annotationProcessors);

        compilerConfiguration.setProcessorPathEntries(resolveProcessorPathEntries());

        compilerConfiguration.setSourceEncoding(encoding);

        compilerConfiguration.setFork(fork);

        if (fork) {
            if (!StringUtils.isEmpty(meminitial)) {
                String value = getMemoryValue(meminitial);

                if (value != null) {
                    compilerConfiguration.setMeminitial(value);
                } else {
                    getLog().info("Invalid value for meminitial '" + meminitial + "'. Ignoring this option.");
                }
            }

            if (!StringUtils.isEmpty(maxmem)) {
                String value = getMemoryValue(maxmem);

                if (value != null) {
                    compilerConfiguration.setMaxmem(value);
                } else {
                    getLog().info("Invalid value for maxmem '" + maxmem + "'. Ignoring this option.");
                }
            }
        }

        compilerConfiguration.setExecutable(executable);

        compilerConfiguration.setWorkingDirectory(basedir.toFile());

        compilerConfiguration.setCompilerVersion(compilerVersion);

        compilerConfiguration.setBuildDirectory(buildDirectory.toFile());

        compilerConfiguration.setOutputFileName(outputFileName);

        if (CompilerConfiguration.CompilerReuseStrategy.AlwaysNew.getStrategy().equals(this.compilerReuseStrategy)) {
            compilerConfiguration.setCompilerReuseStrategy(CompilerConfiguration.CompilerReuseStrategy.AlwaysNew);
        } else if (CompilerConfiguration.CompilerReuseStrategy.ReuseSame.getStrategy()
                .equals(this.compilerReuseStrategy)) {
            if (getRequestThreadCount() > 1) {
                if (!skipMultiThreadWarning) {
                    getLog().warn("You are in a multi-thread build and compilerReuseStrategy is set to reuseSame."
                            + " This can cause issues in some environments (os/jdk)!"
                            + " Consider using reuseCreated strategy."
                            + System.lineSeparator()
                            + "If your env is fine with reuseSame, you can skip this warning with the "
                            + "configuration field skipMultiThreadWarning "
                            + "or -Dmaven.compiler.skipMultiThreadWarning=true");
                }
            }
            compilerConfiguration.setCompilerReuseStrategy(CompilerConfiguration.CompilerReuseStrategy.ReuseSame);
        } else {

            compilerConfiguration.setCompilerReuseStrategy(CompilerConfiguration.CompilerReuseStrategy.ReuseCreated);
        }

        getLog().debug("CompilerReuseStrategy: "
                + compilerConfiguration.getCompilerReuseStrategy().getStrategy());

        compilerConfiguration.setForceJavacCompilerUse(forceLegacyJavacApi);

        boolean canUpdateTarget;

        IncrementalBuildHelper incrementalBuildHelper = null;

        final Set<Path> sources;

        if (useIncrementalCompilation) {
            getLog().debug("useIncrementalCompilation enabled");
            try {
                canUpdateTarget = compiler.canUpdateTarget(compilerConfiguration);

                sources = getCompileSources(compiler, compilerConfiguration);

                preparePaths(sources);

                incrementalBuildHelper =
                        new IncrementalBuildHelper(mojoStatusPath, sources, buildDirectory, getOutputDirectory());

                // Strategies used to detect modifications.
                boolean cleanState = isCleanState(incrementalBuildHelper);
                if (!cleanState) {
                    List<String> added = new ArrayList<>();
                    List<String> removed = new ArrayList<>();
                    boolean immutableOutputFile = compiler.getCompilerOutputStyle()
                                    .equals(CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES)
                            && !canUpdateTarget;
                    boolean dependencyChanged = isDependencyChanged();
                    boolean sourceChanged = isSourceChanged(compilerConfiguration, compiler);
                    boolean inputFileTreeChanged = incrementalBuildHelper.inputFileTreeChanged(added, removed);
                    // CHECKSTYLE_OFF: LineLength
                    if (immutableOutputFile || dependencyChanged || sourceChanged || inputFileTreeChanged)
                    // CHECKSTYLE_ON: LineLength
                    {
                        String cause = immutableOutputFile
                                ? "immutable single output file"
                                : (dependencyChanged
                                        ? "changed dependency"
                                        : (sourceChanged ? "changed source code" : "added or removed source files"));
                        getLog().info("Recompiling the module because of " + cause + ".");
                        if (showCompilationChanges) {
                            for (String fileAdded : added) {
                                getLog().info("\t+ " + fileAdded);
                            }
                            for (String fileRemoved : removed) {
                                getLog().info("\t- " + fileRemoved);
                            }
                        }

                        compilerConfiguration.setSourceFiles(
                                sources.stream().map(Path::toFile).collect(Collectors.toSet()));
                    } else {
                        getLog().info("Nothing to compile - all classes are up to date.");

                        return;
                    }
                }
            } catch (CompilerException e) {
                throw new MojoException("Error while computing stale sources.", e);
            }
        } else {
            getLog().debug("useIncrementalCompilation disabled");

            Set<File> staleSources;
            try {
                staleSources =
                        computeStaleSources(compilerConfiguration, compiler, getSourceInclusionScanner(staleMillis));

                canUpdateTarget = compiler.canUpdateTarget(compilerConfiguration);

                if (compiler.getCompilerOutputStyle().equals(CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES)
                        && !canUpdateTarget) {
                    getLog().info("RESCANNING!");
                    // TODO: This second scan for source files is sub-optimal
                    String inputFileEnding = compiler.getInputFileEnding(compilerConfiguration);

                    staleSources = computeStaleSources(
                            compilerConfiguration, compiler, getSourceInclusionScanner(inputFileEnding));
                }

            } catch (CompilerException e) {
                throw new MojoException("Error while computing stale sources.", e);
            }

            if (staleSources.isEmpty()) {
                getLog().info("Nothing to compile - all classes are up to date.");

                return;
            }

            compilerConfiguration.setSourceFiles(staleSources);

            try {
                // MCOMPILER-366: if sources contain the module-descriptor it must be used to define the modulepath
                sources = getCompileSources(compiler, compilerConfiguration);

                if (getLog().isDebugEnabled()) {
                    getLog().debug("#sources: " + sources.size());
                    for (Path file : sources) {
                        getLog().debug(file.toString());
                    }
                }

                preparePaths(sources);
            } catch (CompilerException e) {
                throw new MojoException("Error while computing stale sources.", e);
            }
        }

        // Dividing pathElements of classPath and modulePath is based on sourceFiles
        compilerConfiguration.setClasspathEntries(getClasspathElements());

        compilerConfiguration.setModulepathEntries(getModulepathElements());

        compilerConfiguration.setIncludes(getIncludes());

        compilerConfiguration.setExcludes(getExcludes());

        String effectiveCompilerArgument = getCompilerArgument();

        if ((effectiveCompilerArgument != null) || (compilerArgs != null)) {
            if (!StringUtils.isEmpty(effectiveCompilerArgument)) {
                compilerConfiguration.addCompilerCustomArgument(effectiveCompilerArgument, null);
            }
            if (compilerArgs != null) {
                for (String arg : compilerArgs) {
                    compilerConfiguration.addCompilerCustomArgument(arg, null);
                }
            }
        }

        // ----------------------------------------------------------------------
        // Dump configuration
        // ----------------------------------------------------------------------
        if (getLog().isDebugEnabled()) {
            getLog().debug("Classpath:");

            for (String s : getClasspathElements()) {
                getLog().debug(" " + s);
            }

            if (!getModulepathElements().isEmpty()) {
                getLog().debug("Modulepath:");
                for (String s : getModulepathElements()) {
                    getLog().debug(" " + s);
                }
            }

            getLog().debug("Source roots:");

            for (Path root : getCompileSourceRoots()) {
                getLog().debug(" " + root);
            }

            try {
                if (fork) {
                    if (compilerConfiguration.getExecutable() != null) {
                        getLog().debug("Executable: ");
                        getLog().debug(" " + compilerConfiguration.getExecutable());
                    }
                }

                String[] cl = compiler.createCommandLine(compilerConfiguration);
                if (cl != null && cl.length > 0 && getLog().isDebugEnabled()) {
                    getLog().debug("Command line options:");
                    getLog().debug(String.join(" ", cl));
                }
            } catch (CompilerException ce) {
                getLog().debug("Compilation error", ce);
            }
        }

        List<String> jpmsLines = new ArrayList<>();

        // See http://openjdk.java.net/jeps/261
        final List<String> runtimeArgs = Arrays.asList(
                "--upgrade-module-path", "--add-exports", "--add-reads", "--add-modules", "--limit-modules");

        // Custom arguments are all added as keys to an ordered Map
        Iterator<Map.Entry<String, String>> entryIter =
                compilerConfiguration.getCustomCompilerArgumentsEntries().iterator();
        while (entryIter.hasNext()) {
            Map.Entry<String, String> entry = entryIter.next();

            if (runtimeArgs.contains(entry.getKey())) {
                jpmsLines.add(entry.getKey());

                String value = entry.getValue();
                if (value == null) {
                    entry = entryIter.next();
                    value = entry.getKey();
                }
                jpmsLines.add(value);
            } else if ("--patch-module".equals(entry.getKey())) {
                String value = entry.getValue();
                if (value == null) {
                    entry = entryIter.next();
                    value = entry.getKey();
                }

                String[] values = value.split("=");

                String patchModule = values[0] + "=";

                Set<Path> sourceRoots = new HashSet<>(getCompileSourceRoots());

                String[] files = values[1].split(PS);
                Set<String> patchModules = new LinkedHashSet<>(files.length, 1);

                for (String file : files) {
                    Path filePath = Paths.get(file);
                    if (getOutputDirectory().equals(filePath)) {
                        patchModules.add("_"); // this jar
                    } else if (getOutputDirectory().startsWith(filePath)) {
                        // multirelease, can be ignored
                        continue;
                    } else if (sourceRoots.contains(filePath)) {
                        patchModules.add("_"); // this jar
                    } else {
                        JavaModuleDescriptor descriptor = getPathElements().get(file);

                        if (descriptor == null) {
                            if (Files.isDirectory(filePath)) {
                                patchModules.add(file);
                            } else {
                                getLog().warn("Can't locate " + file);
                            }
                        } else if (!values[0].equals(descriptor.name())) {
                            patchModules.add(descriptor.name());
                        }
                    }
                }

                if (!patchModules.isEmpty()) {
                    jpmsLines.add("--patch-module");
                    jpmsLines.add(patchModule + String.join(", ", patchModules));
                }
            }
        }

        if (!jpmsLines.isEmpty()) {
            Path jpmsArgs = getOutputDirectory().toAbsolutePath().resolve("META-INF/jpms.args");
            try {
                Files.createDirectories(jpmsArgs.getParent());

                Files.write(jpmsArgs, jpmsLines, Charset.defaultCharset());
            } catch (IOException e) {
                getLog().warn(e.getMessage());
            }
        }

        // ----------------------------------------------------------------------
        // Compile!
        // ----------------------------------------------------------------------

        if (StringUtils.isEmpty(compilerConfiguration.getSourceEncoding())) {
            getLog().warn("File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
                    + ", i.e. build is platform dependent!");
        }

        CompilerResult compilerResult;

        if (useIncrementalCompilation) {
            incrementalBuildHelper.beforeRebuildExecution();
            getLog().debug("incrementalBuildHelper#beforeRebuildExecution");
        }

        try {
            compilerResult = compiler.performCompile(compilerConfiguration);
        } catch (Exception e) {
            // TODO: don't catch Exception
            throw new MojoException("Fatal error compiling", e);
        }

        if (createMissingPackageInfoClass
                && compilerResult.isSuccess()
                && compiler.getCompilerOutputStyle() == CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE) {
            try {
                SourceMapping sourceMapping = getSourceMapping(compilerConfiguration, compiler);
                createMissingPackageInfoClasses(compilerConfiguration, sourceMapping, sources);
            } catch (Exception e) {
                getLog().warn("Error creating missing package info classes", e);
            }
        }

        if (outputTimestamp != null
                && !outputTimestamp.isEmpty()
                && (outputTimestamp.length() > 1 || Character.isDigit(outputTimestamp.charAt(0)))) {
            // if Reproducible Builds mode, apply workaround
            patchJdkModuleVersion(compilerResult, sources);
        }

        if (useIncrementalCompilation) {
            if (Files.exists(getOutputDirectory())) {
                getLog().debug("incrementalBuildHelper#afterRebuildExecution");
                // now scan the same directory again and create a diff
                incrementalBuildHelper.afterRebuildExecution();
            } else {
                getLog().debug(
                                "skip incrementalBuildHelper#afterRebuildExecution as the output directory doesn't exist");
            }
        }

        List<CompilerMessage> warnings = new ArrayList<>();
        List<CompilerMessage> errors = new ArrayList<>();
        List<CompilerMessage> others = new ArrayList<>();
        for (CompilerMessage message : compilerResult.getCompilerMessages()) {
            if (message.getKind() == CompilerMessage.Kind.ERROR) {
                errors.add(message);
            } else if (message.getKind() == CompilerMessage.Kind.WARNING
                    || message.getKind() == CompilerMessage.Kind.MANDATORY_WARNING) {
                warnings.add(message);
            } else {
                others.add(message);
            }
        }

        if (failOnError && !compilerResult.isSuccess()) {
            for (CompilerMessage message : others) {
                assert message.getKind() != CompilerMessage.Kind.ERROR
                        && message.getKind() != CompilerMessage.Kind.WARNING
                        && message.getKind() != CompilerMessage.Kind.MANDATORY_WARNING;
                getLog().info(message.toString());
            }
            if (!warnings.isEmpty()) {
                getLog().info("-------------------------------------------------------------");
                getLog().warn("COMPILATION WARNING : ");
                getLog().info("-------------------------------------------------------------");
                for (CompilerMessage warning : warnings) {
                    getLog().warn(warning.toString());
                }
                getLog().info(warnings.size() + ((warnings.size() > 1) ? " warnings " : " warning"));
                getLog().info("-------------------------------------------------------------");
            }

            if (!errors.isEmpty()) {
                getLog().info("-------------------------------------------------------------");
                getLog().error("COMPILATION ERROR : ");
                getLog().info("-------------------------------------------------------------");
                for (CompilerMessage error : errors) {
                    getLog().error(error.toString());
                }
                getLog().info(errors.size() + ((errors.size() > 1) ? " errors " : " error"));
                getLog().info("-------------------------------------------------------------");
            }

            if (!errors.isEmpty()) {
                throw new CompilationFailureException(errors);
            } else {
                throw new CompilationFailureException(warnings);
            }
        } else {
            for (CompilerMessage message : compilerResult.getCompilerMessages()) {
                switch (message.getKind()) {
                    case NOTE:
                    case OTHER:
                        getLog().info(message.toString());
                        break;

                    case ERROR:
                        getLog().error(message.toString());
                        break;

                    case MANDATORY_WARNING:
                    case WARNING:
                    default:
                        getLog().warn(message.toString());
                        break;
                }
            }
        }
    }

    private void createMissingPackageInfoClasses(
            CompilerConfiguration compilerConfiguration, SourceMapping sourceMapping, Set<Path> sources)
            throws InclusionScanException, IOException {
        for (Path source : sources) {
            String path = source.toString();
            if (path.endsWith(File.separator + "package-info.java")) {
                for (Path rootPath : getCompileSourceRoots()) {
                    String root = rootPath.toString() + File.separator;
                    if (path.startsWith(root)) {
                        String rel = path.substring(root.length());
                        Set<File> files = sourceMapping.getTargetFiles(
                                getOutputDirectory().toFile(), rel);
                        for (File file : files) {
                            if (!file.exists()) {
                                File parentFile = file.getParentFile();

                                if (!parentFile.exists()) {
                                    Files.createDirectories(parentFile.toPath());
                                }

                                byte[] bytes = generatePackage(compilerConfiguration, rel);
                                Files.write(file.toPath(), bytes);
                            }
                        }
                    }
                }
            }
        }
    }

    private byte[] generatePackage(CompilerConfiguration compilerConfiguration, String javaFile) {
        int version = getOpcode(compilerConfiguration);
        String internalPackageName = javaFile.substring(0, javaFile.length() - ".java".length());
        if (File.separatorChar != '/') {
            internalPackageName = internalPackageName.replace(File.separatorChar, '/');
        }
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                version,
                Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalPackageName,
                null,
                "java/lang/Object",
                null);
        cw.visitSource("package-info.java", null);
        return cw.toByteArray();
    }

    private int getOpcode(CompilerConfiguration compilerConfiguration) {
        String version = compilerConfiguration.getReleaseVersion();
        if (version == null) {
            version = compilerConfiguration.getTargetVersion();
            if (version == null) {
                version = "1.5";
            }
        }
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        int iVersion = Integer.parseInt(version);
        if (iVersion < 2) {
            throw new IllegalArgumentException("Unsupported java version '" + version + "'");
        }
        return iVersion - 2 + Opcodes.V1_2;
    }

    protected boolean isTestCompile() {
        return false;
    }

    /**
     * @return all source files for the compiler
     */
    private Set<Path> getCompileSources(Compiler compiler, CompilerConfiguration compilerConfiguration)
            throws MojoException, CompilerException {
        String inputFileEnding = compiler.getInputFileEnding(compilerConfiguration);
        if (StringUtils.isEmpty(inputFileEnding)) {
            // see MCOMPILER-199 GroovyEclipseCompiler doesn't set inputFileEnding
            // so we can presume it's all files from the source directory
            inputFileEnding = ".*";
        }
        SourceInclusionScanner scanner = getSourceInclusionScanner(inputFileEnding);

        SourceMapping mapping = getSourceMapping(compilerConfiguration, compiler);

        scanner.addSourceMapping(mapping);

        Set<Path> compileSources = new HashSet<>();

        for (Path sourceRoot : getCompileSourceRoots()) {
            if (!Files.isDirectory(sourceRoot)
                    || sourceRoot.toFile().equals(compilerConfiguration.getGeneratedSourcesDirectory())) {
                continue;
            }

            try {
                scanner.getIncludedSources(sourceRoot.toFile(), null).forEach(f -> compileSources.add(f.toPath()));
            } catch (InclusionScanException e) {
                throw new MojoException(
                        "Error scanning source root: '" + sourceRoot + "' for stale files to recompile.", e);
            }
        }

        return compileSources;
    }

    protected abstract Set<String> getIncludes();

    protected abstract Set<String> getExcludes();

    /**
     * @param compilerConfiguration
     * @param compiler
     * @return <code>true</code> if at least a single source file is newer than it's class file
     */
    private boolean isSourceChanged(CompilerConfiguration compilerConfiguration, Compiler compiler)
            throws CompilerException, MojoException {
        Set<File> staleSources =
                computeStaleSources(compilerConfiguration, compiler, getSourceInclusionScanner(staleMillis));

        if (getLog().isDebugEnabled() || showCompilationChanges) {
            for (File f : staleSources) {
                if (showCompilationChanges) {
                    getLog().info("Stale source detected: " + f.getAbsolutePath());
                } else {
                    getLog().debug("Stale source detected: " + f.getAbsolutePath());
                }
            }
        }
        return !staleSources.isEmpty();
    }

    /**
     * try to get thread count if a Maven 3 build, using reflection as the plugin must not be maven3 api dependent
     *
     * @return number of thread for this build or 1 if not multi-thread build
     */
    protected int getRequestThreadCount() {
        return session.getDegreeOfConcurrency();
    }

    protected Instant getBuildStartTime() {
        return session.getStartTime();
    }

    private String getMemoryValue(String setting) {
        String value = null;

        // Allow '128' or '128m'
        if (isDigits(setting)) {
            value = setting + "m";
        } else if ((isDigits(setting.substring(0, setting.length() - 1)))
                && (setting.toLowerCase().endsWith("m"))) {
            value = setting;
        }
        return value;
    }

    protected final Optional<Toolchain> getToolchain() {
        if (jdkToolchain != null) {
            List<Toolchain> tcs = toolchainManager.getToolchains(session, "jdk", jdkToolchain);
            if (tcs != null && !tcs.isEmpty()) {
                return Optional.of(tcs.get(0));
            }
        }
        return toolchainManager.getToolchainFromBuildContext(session, "jdk");
    }

    private boolean isDigits(String string) {
        for (int i = 0; i < string.length(); i++) {
            if (!Character.isDigit(string.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private Set<File> computeStaleSources(
            CompilerConfiguration compilerConfiguration, Compiler compiler, SourceInclusionScanner scanner)
            throws MojoException, CompilerException {
        SourceMapping mapping = getSourceMapping(compilerConfiguration, compiler);

        Path outputDirectory;
        CompilerOutputStyle outputStyle = compiler.getCompilerOutputStyle();
        if (outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES) {
            outputDirectory = buildDirectory;
        } else {
            outputDirectory = getOutputDirectory();
        }

        scanner.addSourceMapping(mapping);

        Set<File> staleSources = new HashSet<>();

        for (Path sourceRoot : getCompileSourceRoots()) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }

            try {
                staleSources.addAll(scanner.getIncludedSources(sourceRoot.toFile(), outputDirectory.toFile()));
            } catch (InclusionScanException e) {
                throw new MojoException(
                        "Error scanning source root: \'" + sourceRoot + "\' for stale files to recompile.", e);
            }
        }

        return staleSources;
    }

    private SourceMapping getSourceMapping(CompilerConfiguration compilerConfiguration, Compiler compiler)
            throws CompilerException, MojoException {
        CompilerOutputStyle outputStyle = compiler.getCompilerOutputStyle();

        SourceMapping mapping;
        if (outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE) {
            mapping = new SuffixMapping(
                    compiler.getInputFileEnding(compilerConfiguration),
                    compiler.getOutputFileEnding(compilerConfiguration));
        } else if (outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES) {
            mapping = new SingleTargetSourceMapping(
                    compiler.getInputFileEnding(compilerConfiguration), compiler.getOutputFile(compilerConfiguration));

        } else {
            throw new MojoException("Unknown compiler output style: '" + outputStyle + "'.");
        }
        return mapping;
    }

    /**
     * @todo also in ant plugin. This should be resolved at some point so that it does not need to
     * be calculated continuously - or should the plugins accept empty source roots as is?
     */
    private static List<Path> removeEmptyCompileSourceRoots(List<Path> compileSourceRootsList) {
        if (compileSourceRootsList != null) {
            return compileSourceRootsList.stream().filter(Files::exists).collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }

    /**
     *
     */
    protected boolean isCleanState(IncrementalBuildHelper ibh) {
        Path mojoConfigBase;
        try {
            mojoConfigBase = ibh.getMojoStatusDirectory();
        } catch (MojoException e) {
            // we cannot get the mojo status dir, so don't do anything beside logging
            getLog().warn("Error reading mojo status directory.");
            return false;
        }
        Path mojoConfigFile = mojoConfigBase.resolve(INPUT_FILES_LST_FILENAME);
        return !Files.exists(mojoConfigFile);
    }

    /**
     * We just compare the timestamps of all local dependency files (inter-module dependency classpath) and the own
     * generated classes and if we got a file which is &gt;= the build-started timestamp, then we caught a file which
     * got changed during this build.
     *
     * @return <code>true</code> if at least one single dependency has changed.
     */
    protected boolean isDependencyChanged() {
        if (session == null) {
            // we just cannot determine it, so don't do anything beside logging
            getLog().info("Cannot determine build start date, skipping incremental build detection.");
            return false;
        }

        if (fileExtensions == null || fileExtensions.isEmpty()) {
            fileExtensions = Collections.unmodifiableList(Arrays.asList("class", "jar"));
        }

        Instant buildStartTime = getBuildStartTime();

        List<String> pathElements = new ArrayList<>();
        pathElements.addAll(getClasspathElements());
        pathElements.addAll(getModulepathElements());

        for (String pathElement : pathElements) {
            File artifactPath = new File(pathElement);
            if (artifactPath.isDirectory() || artifactPath.isFile()) {
                if (hasNewFile(artifactPath, buildStartTime)) {
                    if (showCompilationChanges) {
                        getLog().info("New dependency detected: " + artifactPath.getAbsolutePath());
                    } else {
                        getLog().debug("New dependency detected: " + artifactPath.getAbsolutePath());
                    }
                    return true;
                }
            }
        }

        // obviously there was no new file detected.
        return false;
    }

    /**
     * @param classPathEntry entry to check
     * @param buildStartTime time build start
     * @return if any changes occurred
     */
    private boolean hasNewFile(File classPathEntry, Instant buildStartTime) {
        // TODO: rewrite with NIO api
        if (!classPathEntry.exists()) {
            return false;
        }

        if (classPathEntry.isFile()) {
            return classPathEntry.lastModified() >= buildStartTime.toEpochMilli()
                    && fileExtensions.contains(FileUtils.getExtension(classPathEntry.getName()));
        }

        File[] children = classPathEntry.listFiles();

        for (File child : children) {
            if (hasNewFile(child, buildStartTime)) {
                return true;
            }
        }

        return false;
    }

    private List<String> resolveProcessorPathEntries() throws MojoException {
        if (annotationProcessorPaths == null || annotationProcessorPaths.isEmpty()) {
            return null;
        }

        try {
            Session session = this.session.withRemoteRepositories(projectManager.getRemoteProjectRepositories(project));
            List<org.apache.maven.api.DependencyCoordinates> coords =
                    annotationProcessorPaths.stream().map(this::toCoordinates).collect(Collectors.toList());
            return session
                    .getService(DependencyResolver.class)
                    .resolve(DependencyResolverRequest.builder()
                            .session(session)
                            .dependencies(coords)
                            .managedDependencies(project.getManagedDependencies())
                            .pathScope(PathScope.MAIN_RUNTIME)
                            .build())
                    .getPaths()
                    .stream()
                    .map(Path::toString)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new MojoException("Resolution of annotationProcessorPath dependencies failed: " + e.getMessage(), e);
        }
    }

    private org.apache.maven.api.DependencyCoordinates toCoordinates(DependencyCoordinates coord) {
        return session.getService(DependencyCoordinatesFactory.class)
                .create(DependencyCoordinatesFactoryRequest.builder()
                        .session(session)
                        .groupId(coord.getGroupId())
                        .artifactId(coord.getArtifactId())
                        .classifier(coord.getClassifier())
                        .type(coord.getType())
                        .version(getAnnotationProcessorPathVersion(coord))
                        .exclusions(toExclusions(coord.getExclusions()))
                        .build());
    }

    private Collection<Exclusion> toExclusions(Set<DependencyExclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) {
            return List.of();
        }
        return exclusions.stream()
                .map(e -> (Exclusion) new Exclusion() {
                    @Override
                    public String getGroupId() {
                        return e.getGroupId();
                    }

                    @Override
                    public String getArtifactId() {
                        return e.getArtifactId();
                    }
                })
                .toList();
    }

    private String getAnnotationProcessorPathVersion(DependencyCoordinates annotationProcessorPath)
            throws MojoException {
        String configuredVersion = annotationProcessorPath.getVersion();
        if (configuredVersion != null) {
            return configuredVersion;
        } else {
            List<org.apache.maven.api.DependencyCoordinates> managedDependencies = project.getManagedDependencies();
            return findManagedVersion(annotationProcessorPath, managedDependencies)
                    .orElseThrow(() -> new MojoException(String.format(
                            "Cannot find version for annotation processor path '%s'. The version needs to be either"
                                    + " provided directly in the plugin configuration or via dependency management.",
                            annotationProcessorPath)));
        }
    }

    private Optional<String> findManagedVersion(
            DependencyCoordinates dependencyCoordinate,
            List<org.apache.maven.api.DependencyCoordinates> managedDependencies) {
        return managedDependencies.stream()
                .filter(dep -> Objects.equals(dep.getGroupId(), dependencyCoordinate.getGroupId())
                        && Objects.equals(dep.getArtifactId(), dependencyCoordinate.getArtifactId())
                        && Objects.equals(dep.getClassifier(), dependencyCoordinate.getClassifier())
                        && Objects.equals(dep.getType().id(), dependencyCoordinate.getType()))
                .findAny()
                .map(d -> d.getVersionConstraint().asString());
    }

    private void writePlugin(MessageBuilder mb) {
        mb.a("    <plugin>").newline();
        mb.a("      <groupId>org.apache.maven.plugins</groupId>").newline();
        mb.a("      <artifactId>maven-compiler-plugin</artifactId>").newline();

        String version = getMavenCompilerPluginVersion();
        if (version != null) {
            mb.a("      <version>").a(version).a("</version>").newline();
        }
        writeConfig(mb);

        mb.a("    </plugin>").newline();
    }

    private void writeConfig(MessageBuilder mb) {
        mb.a("      <configuration>").newline();

        if (release != null && !release.isEmpty()) {
            mb.a("        <release>").a(release).a("</release>").newline();
        } else if (JavaVersion.JAVA_VERSION.isAtLeast("9")) {
            String rls = target.replaceAll(".\\.", "");
            // when using Java9+, motivate to use release instead of source/target
            mb.a("        <release>").a(rls).a("</release>").newline();
        } else {
            mb.a("        <source>").a(source).a("</source>").newline();
            mb.a("        <target>").a(target).a("</target>").newline();
        }
        mb.a("      </configuration>").newline();
    }

    private String getMavenCompilerPluginVersion() {
        Properties pomProperties = new Properties();

        try (InputStream is = AbstractCompilerMojo.class.getResourceAsStream(
                "/META-INF/maven/org.apache.maven.plugins/maven-compiler-plugin/pom.properties")) {
            if (is != null) {
                pomProperties.load(is);
            }
        } catch (IOException e) {
            // noop
        }

        return pomProperties.getProperty("version");
    }

    public void setTarget(String target) {
        this.target = target;
        targetOrReleaseSet = true;
    }

    public void setRelease(String release) {
        this.release = release;
        targetOrReleaseSet = true;
    }

    final String getImplicit() {
        return implicit;
    }

    /**
     * JDK-8318913 workaround: Patch module-info.class to set the java release version for java/jdk modules.
     *
     * @param compilerResult should succeed.
     * @param sources the list of the source files to check for the "module-info.java"
     *
     * @see <a href="https://issues.apache.org/jira/browse/MCOMPILER-542">MCOMPILER-542</a>
     * @see <a href="https://bugs.openjdk.org/browse/JDK-8318913">JDK-8318913</a>
     */
    private void patchJdkModuleVersion(CompilerResult compilerResult, Set<Path> sources) throws MojoException {
        if (compilerResult.isSuccess() && getModuleDeclaration(sources).isPresent()) {
            Path moduleDescriptor = getOutputDirectory().resolve("module-info.class");
            if (Files.isRegularFile(moduleDescriptor)) {
                try {
                    final byte[] descriptorOriginal = Files.readAllBytes(moduleDescriptor);
                    final byte[] descriptorMod =
                            ModuleInfoTransformer.transform(descriptorOriginal, getRelease(), getLog());
                    if (descriptorMod != null) {
                        Files.write(moduleDescriptor, descriptorMod);
                    }
                } catch (IOException ex) {
                    throw new MojoException("Error reading or writing module-info.class", ex);
                }
            }
        }
    }

    protected final Optional<Path> getModuleDeclaration(final Set<Path> sourceFiles) {
        for (Path sourceFile : sourceFiles) {
            if ("module-info.java".equals(sourceFile.getFileName().toString())) {
                return Optional.of(sourceFile);
            }
        }
        return Optional.empty();
    }

    protected Log getLog() {
        return logger;
    }

    class MavenLogger extends AbstractLogger {
        MavenLogger() {
            super(0, AbstractCompilerMojo.this.getClass().getName());
        }

        @Override
        public void debug(String message, Throwable throwable) {
            logger.debug(message, throwable);
        }

        @Override
        public boolean isDebugEnabled() {
            return logger.isDebugEnabled();
        }

        @Override
        public void info(String message, Throwable throwable) {
            logger.info(message, throwable);
        }

        @Override
        public boolean isInfoEnabled() {
            return logger.isInfoEnabled();
        }

        @Override
        public void warn(String message, Throwable throwable) {
            logger.warn(message, throwable);
        }

        @Override
        public boolean isWarnEnabled() {
            return logger.isWarnEnabled();
        }

        @Override
        public void error(String message, Throwable throwable) {
            logger.error(message, throwable);
        }

        @Override
        public boolean isErrorEnabled() {
            return logger.isErrorEnabled();
        }

        @Override
        public void fatalError(String message, Throwable throwable) {
            logger.error(message, throwable);
        }

        @Override
        public boolean isFatalErrorEnabled() {
            return isFatalErrorEnabled();
        }

        @Override
        public Logger getChildLogger(String name) {
            return this;
        }
    }

    protected static <T> Set<T> add(Set<T> t1, Set<T> t2) {
        Set<T> s = new HashSet<>();
        s.addAll(t1);
        s.addAll(t2);
        return s;
    }
}
