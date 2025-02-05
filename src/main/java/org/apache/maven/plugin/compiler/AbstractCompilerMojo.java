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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
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
import java.util.stream.Stream;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.incremental.IncrementalBuildHelper;
import org.apache.maven.shared.incremental.IncrementalBuildHelperRequest;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.logging.MessageBuilder;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
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
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
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
public abstract class AbstractCompilerMojo extends AbstractMojo {
    protected static final String PS = System.getProperty("path.separator");

    private static final String INPUT_FILES_LST_FILENAME = "inputFiles.lst";

    static final String DEFAULT_SOURCE = "1.8";

    static final String DEFAULT_TARGET = "1.8";

    // ----------------------------------------------------------------------
    // Configurables
    // ----------------------------------------------------------------------

    /**
     * Indicates whether the build will continue even if there are compilation errors.
     *
     * @since 2.0.2
     */
    @Parameter(property = "maven.compiler.failOnError", defaultValue = "true")
    private boolean failOnError = true;

    /**
     * Indicates whether the build will continue even if there are compilation warnings.
     *
     * @since 3.6
     */
    @Parameter(property = "maven.compiler.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    /**
     * Set to <code>true</code> to include debugging information in the compiled class files.
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-g">javac -g</a>
     * @see #debuglevel
     */
    @Parameter(property = "maven.compiler.debug", defaultValue = "true")
    private boolean debug = true;

    /**
     * Set to <code>true</code> to generate metadata for reflection on method parameters.
     * @since 3.6.2
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-parameters">javac -parameters</a>
     */
    @Parameter(property = "maven.compiler.parameters", defaultValue = "false")
    private boolean parameters;

    /**
     * Set to <code>true</code> to enable preview language features of the java compiler
     * @since 3.10.1
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-enable-preview">javac --enable-preview</a>
     */
    @Parameter(property = "maven.compiler.enablePreview", defaultValue = "false")
    private boolean enablePreview;

    /**
     * Set to <code>true</code> to show messages about what the compiler is doing.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-verbose">javac -verbose</a>
     */
    @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * Sets whether to show source locations where deprecated APIs are used.
     */
    @Parameter(property = "maven.compiler.showDeprecation", defaultValue = "false")
    private boolean showDeprecation;

    /**
     * Set to <code>true</code> to optimize the compiled code using the compiler's optimization methods.
     * @deprecated This property is a no-op in {@code javac}.
     */
    @Deprecated
    @Parameter(property = "maven.compiler.optimize", defaultValue = "false")
    private boolean optimize;

    /**
     * Set to <code>false</code> to disable warnings during compilation.
     */
    @Parameter(property = "maven.compiler.showWarnings", defaultValue = "true")
    private boolean showWarnings;

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
    private String encoding;

    /**
     * Sets the granularity in milliseconds of the last modification
     * date for testing whether a source needs recompilation.
     */
    @Parameter(property = "lastModGranularityMs", defaultValue = "0")
    private int staleMillis;

    /**
     * The compiler id of the compiler to use. See this
     * <a href="non-javac-compilers.html">guide</a> for more information.
     */
    @Parameter(property = "maven.compiler.compilerId", defaultValue = "javac")
    private String compilerId;

    /**
     * Version of the compiler to use, ex. "1.3", "1.5", if {@link #fork} is set to <code>true</code>.
     * @deprecated This parameter is no longer evaluated by the underlying compilers, instead the actual
     * version of the {@code javac} binary is automatically retrieved.
     */
    @Deprecated
    @Parameter(property = "maven.compiler.compilerVersion")
    private String compilerVersion;

    /**
     * Allows running the compiler in a separate process.
     * If <code>false</code> it uses the built in compiler, while if <code>true</code> it will use an executable.
     */
    @Parameter(property = "maven.compiler.fork", defaultValue = "false")
    private boolean fork;

    /**
     * Initial size, in megabytes, of the memory allocation pool, ex. "64", "64m"
     * if {@link #fork} is set to <code>true</code>.
     *
     * @since 2.0.1
     */
    @Parameter(property = "maven.compiler.meminitial")
    private String meminitial;

    /**
     * Sets the maximum size, in megabytes, of the memory allocation pool, ex. "128", "128m"
     * if {@link #fork} is set to <code>true</code>.
     *
     * @since 2.0.1
     */
    @Parameter(property = "maven.compiler.maxmem")
    private String maxmem;

    /**
     * Sets the executable of the compiler to use when {@link #fork} is <code>true</code>.
     */
    @Parameter(property = "maven.compiler.executable")
    private String executable;

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
    private String proc;

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
    private String[] annotationProcessors;

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
    private List<DependencyCoordinate> annotationProcessorPaths;

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
    private boolean annotationProcessorPathsUseDepMgmt;

    /**
     * <p>
     * Sets the arguments to be passed to the compiler (prepending a dash).
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler varies based on the compiler version.
     * </p>
     * <p>
     * Note that {@code -J} options are only passed through if {@link #fork} is set to {@code true}.
     * </p>
     * <p>
     * To pass <code>-Xmaxerrs 1000 -Xlint -Xlint:-path -Averbose=true</code> you should include the following:
     * </p>
     *
     * <pre>
     * &lt;compilerArguments&gt;
     *   &lt;Xmaxerrs&gt;1000&lt;/Xmaxerrs&gt;
     *   &lt;Xlint/&gt;
     *   &lt;Xlint:-path/&gt;
     *   &lt;Averbose&gt;true&lt;/Averbose&gt;
     * &lt;/compilerArguments&gt;
     * </pre>
     *
     * @since 2.0.1
     * @deprecated use {@link #compilerArgs} instead.
     */
    @Parameter
    @Deprecated
    protected Map<String, String> compilerArguments;

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
    private String implicit;

    /**
     *
     */
    @Component
    private ToolchainManager toolchainManager;

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
    private Map<String, String> jdkToolchain;

    // ----------------------------------------------------------------------
    // Read-only parameters
    // ----------------------------------------------------------------------

    /**
     * The directory to run the compiler from if fork is true.
     */
    @Parameter(defaultValue = "${basedir}", required = true, readonly = true)
    private File basedir;

    /**
     * The target directory of the compiler if fork is true.
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    private File buildDirectory;

    /**
     * Plexus compiler manager.
     */
    @Component
    private CompilerManager compilerManager;

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * The current project instance. This is used for propagating generated-sources paths as compile/testCompile source
     * roots.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

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
    private String compilerReuseStrategy = "reuseCreated";

    /**
     * @since 2.5
     */
    @Parameter(defaultValue = "false", property = "maven.compiler.skipMultiThreadWarning")
    private boolean skipMultiThreadWarning;

    /**
     * Legacy parameter name of {@link #forceLegacyJavacApi}. Only considered if {@link #forceLegacyJavacApi} is
     * not set or {@code false}.
     * @since 3.0
     * @deprecated Use {@link #forceLegacyJavacApi} instead
     */
    @Deprecated
    @Parameter(defaultValue = "false", property = "maven.compiler.forceJavacCompilerUse")
    private boolean forceJavacCompilerUse;

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
    private boolean forceLegacyJavacApi;

    /**
     * @since 3.0 needed for storing the status for the incremental build support.
     */
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    /**
     * File extensions to check timestamp for incremental build.
     *
     * @since 3.1
     */
    @Parameter(defaultValue = "class,jar")
    private Set<String> fileExtensions;

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
    private boolean useIncrementalCompilation = true;

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
    private boolean createMissingPackageInfoClass = true;

    @Parameter(defaultValue = "false", property = "maven.compiler.showCompilationChanges")
    private boolean showCompilationChanges = false;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     * @since 3.12.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * Resolves the artifacts needed.
     */
    @Component
    private RepositorySystem repositorySystem;

    /**
     * Artifact handler manager.
     */
    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    protected abstract SourceInclusionScanner getSourceInclusionScanner(int staleMillis);

    protected abstract SourceInclusionScanner getSourceInclusionScanner(String inputFileEnding);

    protected abstract List<String> getClasspathElements();

    protected abstract List<String> getModulepathElements();

    protected abstract Map<String, JavaModuleDescriptor> getPathElements();

    protected abstract List<String> getCompileSourceRoots();

    protected abstract void preparePaths(Set<File> sourceFiles);

    protected abstract File getOutputDirectory();

    protected abstract String getSource();

    protected abstract String getTarget();

    protected abstract String getRelease();

    protected abstract String getCompilerArgument();

    protected abstract Map<String, String> getCompilerArguments();

    protected abstract File getGeneratedSourcesDirectory();

    protected abstract String getDebugFileName();

    protected final MavenProject getProject() {
        return project;
    }

    protected final Optional<Path> getModuleDeclaration(final Set<File> sourceFiles) {
        for (File sourceFile : sourceFiles) {
            if ("module-info.java".equals(sourceFile.getName())) {
                return Optional.of(sourceFile.toPath());
            }
        }
        return Optional.empty();
    }

    private boolean targetOrReleaseSet;

    @Override
    @SuppressWarnings("checkstyle:MethodLength")
    public void execute() throws MojoExecutionException, CompilationFailureException {
        // ----------------------------------------------------------------------
        // Look up the compiler. This is done before other code than can
        // cause the mojo to return before the lookup is done possibly resulting
        // in misconfigured POMs still building.
        // ----------------------------------------------------------------------

        Compiler compiler;

        getLog().debug("Using compiler '" + compilerId + "'.");

        try {
            compiler = compilerManager.getCompiler(compilerId);
        } catch (NoSuchCompilerException e) {
            throw new MojoExecutionException("No such compiler '" + e.getCompilerId() + "'.", e);
        }

        // -----------toolchains start here ----------------------------------
        // use the compilerId as identifier for toolchains as well.
        Toolchain tc = getToolchain();
        if (tc != null) {
            getLog().info("Toolchain in maven-compiler-plugin: " + tc);
            if (executable != null) {
                getLog().warn("Toolchains are ignored, 'executable' parameter is set to " + executable);
            } else {
                fork = true;
                // TODO somehow shaky dependency between compilerId and tool executable.
                executable = tc.findTool(compilerId);
            }
        }
        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        List<String> compileSourceRoots = removeEmptyCompileSourceRoots(getCompileSourceRoots());

        if (compileSourceRoots.isEmpty()) {
            getLog().info("No sources to compile");

            return;
        }

        // Verify that target or release is set
        if (!targetOrReleaseSet) {
            MessageBuilder mb = MessageUtils.buffer()
                    .a("No explicit value set for target or release! ")
                    .a("To ensure the same result even after upgrading this plugin, please add ")
                    .newline()
                    .newline();

            writePlugin(mb);

            getLog().warn(mb.toString());
        }

        // ----------------------------------------------------------------------
        // Create the compiler configuration
        // ----------------------------------------------------------------------

        CompilerConfiguration compilerConfiguration = new CompilerConfiguration();

        compilerConfiguration.setOutputLocation(getOutputDirectory().getAbsolutePath());

        compilerConfiguration.setOptimize(optimize);

        compilerConfiguration.setDebug(debug);

        compilerConfiguration.setDebugFileName(getDebugFileName());

        compilerConfiguration.setImplicitOption(implicit);

        if (debug && (debuglevel != null && !debuglevel.isEmpty())) {
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

        if (failOnWarning && !showWarnings) {
            getLog().warn("The property failOnWarning is set to true, but showWarnings is set to false.");
            getLog().warn("With compiler's warnings silenced the failOnWarning has no effect.");
        }

        compilerConfiguration.setShowDeprecation(showDeprecation);

        compilerConfiguration.setSourceVersion(getSource());

        compilerConfiguration.setTargetVersion(getTarget());

        compilerConfiguration.setReleaseVersion(getRelease());

        compilerConfiguration.setProc(proc);

        File generatedSourcesDirectory = getGeneratedSourcesDirectory();
        compilerConfiguration.setGeneratedSourcesDirectory(
                generatedSourcesDirectory != null ? generatedSourcesDirectory.getAbsoluteFile() : null);

        if (generatedSourcesDirectory != null) {
            if (!generatedSourcesDirectory.exists()) {
                generatedSourcesDirectory.mkdirs();
            }

            String generatedSourcesPath = generatedSourcesDirectory.getAbsolutePath();

            compileSourceRoots.add(generatedSourcesPath);

            if (isTestCompile()) {
                getLog().debug("Adding " + generatedSourcesPath + " to test-compile source roots:\n  "
                        + StringUtils.join(project.getTestCompileSourceRoots().iterator(), "\n  "));

                project.addTestCompileSourceRoot(generatedSourcesPath);

                getLog().debug("New test-compile source roots:\n  "
                        + StringUtils.join(project.getTestCompileSourceRoots().iterator(), "\n  "));
            } else {
                getLog().debug("Adding " + generatedSourcesPath + " to compile source roots:\n  "
                        + StringUtils.join(project.getCompileSourceRoots().iterator(), "\n  "));

                project.addCompileSourceRoot(generatedSourcesPath);

                getLog().debug("New compile source roots:\n  "
                        + StringUtils.join(project.getCompileSourceRoots().iterator(), "\n  "));
            }
        }

        compilerConfiguration.setSourceLocations(compileSourceRoots);

        compilerConfiguration.setAnnotationProcessors(annotationProcessors);

        compilerConfiguration.setProcessorPathEntries(resolveProcessorPathEntries());

        compilerConfiguration.setSourceEncoding(encoding);

        compilerConfiguration.setFork(fork);

        if (fork) {
            if (!(meminitial == null || meminitial.isEmpty())) {
                String value = getMemoryValue(meminitial);

                if (value != null) {
                    compilerConfiguration.setMeminitial(value);
                } else {
                    getLog().info("Invalid value for meminitial '" + meminitial + "'. Ignoring this option.");
                }
            }

            if (!(maxmem == null || maxmem.isEmpty())) {
                String value = getMemoryValue(maxmem);

                if (value != null) {
                    compilerConfiguration.setMaxmem(value);
                } else {
                    getLog().info("Invalid value for maxmem '" + maxmem + "'. Ignoring this option.");
                }
            }
        }

        compilerConfiguration.setExecutable(executable);

        compilerConfiguration.setWorkingDirectory(basedir);

        compilerConfiguration.setCompilerVersion(compilerVersion);

        compilerConfiguration.setBuildDirectory(buildDirectory);

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
                            + System.getProperty("line.separator")
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

        compilerConfiguration.setForceJavacCompilerUse(forceLegacyJavacApi || forceJavacCompilerUse);

        boolean canUpdateTarget;

        IncrementalBuildHelper incrementalBuildHelper = new IncrementalBuildHelper(mojoExecution, session);

        final Set<File> sources;

        IncrementalBuildHelperRequest incrementalBuildHelperRequest = null;

        if (useIncrementalCompilation) {
            getLog().debug("useIncrementalCompilation enabled");
            try {
                canUpdateTarget = compiler.canUpdateTarget(compilerConfiguration);

                sources = getCompileSources(compiler, compilerConfiguration);

                preparePaths(sources);

                incrementalBuildHelperRequest = new IncrementalBuildHelperRequest().inputFiles(sources);

                // Strategies used to detect modifications.
                String immutableOutputFile = (compiler.getCompilerOutputStyle()
                                        .equals(CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES)
                                && !canUpdateTarget)
                        ? "immutable single output file"
                        : null;
                String dependencyChanged = isDependencyChanged() ? "changed dependency" : null;
                String sourceChanged = isSourceChanged(compilerConfiguration, compiler) ? "changed source code" : null;
                String inputFileTreeChanged = hasInputFileTreeChanged(incrementalBuildHelper, sources)
                        ? "added or removed source files"
                        : null;

                // Get the first cause for the rebuild compilation detection.
                String cause = Stream.of(immutableOutputFile, dependencyChanged, sourceChanged, inputFileTreeChanged)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                if (cause != null) {
                    getLog().info("Recompiling the module because of "
                            + MessageUtils.buffer().strong(cause) + ".");
                    compilerConfiguration.setSourceFiles(sources);
                } else {
                    getLog().info("Nothing to compile - all classes are up to date.");
                    return;
                }
            } catch (CompilerException e) {
                throw new MojoExecutionException("Error while computing stale sources.", e);
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
                throw new MojoExecutionException("Error while computing stale sources.", e);
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
                    for (File file : sources) {
                        getLog().debug(file.getPath());
                    }
                }

                preparePaths(sources);
            } catch (CompilerException e) {
                throw new MojoExecutionException("Error while computing stale sources.", e);
            }
        }

        // Dividing pathElements of classPath and modulePath is based on sourceFiles
        compilerConfiguration.setClasspathEntries(getClasspathElements());

        compilerConfiguration.setModulepathEntries(getModulepathElements());

        compilerConfiguration.setIncludes(getIncludes());

        compilerConfiguration.setExcludes(getExcludes());

        Map<String, String> effectiveCompilerArguments = getCompilerArguments();

        String effectiveCompilerArgument = getCompilerArgument();

        if ((effectiveCompilerArguments != null) || (effectiveCompilerArgument != null) || (compilerArgs != null)) {
            if (effectiveCompilerArguments != null) {
                for (Map.Entry<String, String> me : effectiveCompilerArguments.entrySet()) {
                    String key = me.getKey();
                    String value = me.getValue();
                    if (!key.startsWith("-")) {
                        key = "-" + key;
                    }

                    if (key.startsWith("-A") && (value != null && !value.isEmpty())) {
                        compilerConfiguration.addCompilerCustomArgument(key + "=" + value, null);
                    } else {
                        compilerConfiguration.addCompilerCustomArgument(key, value);
                    }
                }
            }
            if (!(effectiveCompilerArgument == null || effectiveCompilerArgument.isEmpty())) {
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

            for (String root : getCompileSourceRoots()) {
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
                if (cl != null && cl.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(cl[0]);
                    for (int i = 1; i < cl.length; i++) {
                        sb.append(" ");
                        sb.append(cl[i]);
                    }
                    getLog().debug("Command line options:");
                    getLog().debug(sb);
                }
            } catch (CompilerException ce) {
                getLog().debug(ce);
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

                StringBuilder patchModule = new StringBuilder(values[0]);
                patchModule.append('=');

                Set<String> patchModules = new LinkedHashSet<>();
                Set<Path> sourceRoots = new HashSet<>(getCompileSourceRoots().size());
                for (String sourceRoot : getCompileSourceRoots()) {
                    sourceRoots.add(Paths.get(sourceRoot));
                }

                String[] files = values[1].split(PS);

                for (String file : files) {
                    Path filePath = Paths.get(file);
                    if (getOutputDirectory().toPath().equals(filePath)) {
                        patchModules.add("_"); // this jar
                    } else if (getOutputDirectory().toPath().startsWith(filePath)) {
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

                StringBuilder sb = new StringBuilder();

                if (!patchModules.isEmpty()) {
                    for (String mod : patchModules) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        // use 'invalid' separator to ensure values are transformed
                        sb.append(mod);
                    }

                    jpmsLines.add("--patch-module");
                    jpmsLines.add(patchModule + sb.toString());
                }
            }
        }

        if (!jpmsLines.isEmpty()) {
            Path jpmsArgs = Paths.get(getOutputDirectory().getAbsolutePath(), "META-INF/jpms.args");
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
            getLog().warn("File encoding has not been set, using platform encoding "
                    + MessageUtils.buffer().strong(Charset.defaultCharset())
                    + ", i.e. build is platform dependent!");
        }

        CompilerResult compilerResult;

        if (useIncrementalCompilation) {
            incrementalBuildHelperRequest.outputDirectory(getOutputDirectory());

            // MCOMPILER-333: Cleanup the generated source files created by annotation processing
            // to avoid issues with `javac` compiler when the source code is rebuild.
            if (getGeneratedSourcesDirectory() != null) {
                try (Stream<Path> walk =
                        Files.walk(getGeneratedSourcesDirectory().toPath())) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                    // MCOMPILER-567: The directory must already exist because javac does not create it.
                    Files.createDirectories(getGeneratedSourcesDirectory().toPath());
                } catch (IOException ex) {
                    getLog().warn("I/O error deleting the annotation processing generated files: " + ex.getMessage());
                }
            }

            incrementalBuildHelper.beforeRebuildExecution(incrementalBuildHelperRequest);

            getLog().debug("incrementalBuildHelper#beforeRebuildExecution");
        }

        try {
            compilerResult = compiler.performCompile(compilerConfiguration);
        } catch (Exception e) {
            // TODO: don't catch Exception
            throw new MojoExecutionException("Fatal error compiling", e);
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
            if (incrementalBuildHelperRequest.getOutputDirectory().exists()) {
                getLog().debug("incrementalBuildHelper#afterRebuildExecution");
                // now scan the same directory again and create a diff
                incrementalBuildHelper.afterRebuildExecution(incrementalBuildHelperRequest);
            } else {
                getLog().debug(
                                "skip incrementalBuildHelper#afterRebuildExecution as the output directory doesn't exist");
            }
        }

        List<CompilerMessage> warnings = new ArrayList<>();
        List<CompilerMessage> errors = new ArrayList<>();
        List<CompilerMessage> others = new ArrayList<>();
        for (CompilerMessage message : compilerResult.getCompilerMessages()) {
            switch (message.getKind()) {
                case ERROR:
                    errors.add(message);
                    break;
                case WARNING:
                case MANDATORY_WARNING:
                    warnings.add(message);
                    break;
                default:
                    others.add(message);
                    break;
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
            CompilerConfiguration compilerConfiguration, SourceMapping sourceMapping, Set<File> sources)
            throws InclusionScanException, IOException {
        for (File source : sources) {
            String path = source.toString();
            if (path.endsWith(File.separator + "package-info.java")) {
                for (String root : getCompileSourceRoots()) {
                    root = root + File.separator;
                    if (path.startsWith(root)) {
                        String rel = path.substring(root.length());
                        Set<File> files = sourceMapping.getTargetFiles(getOutputDirectory(), rel);
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
    private Set<File> getCompileSources(Compiler compiler, CompilerConfiguration compilerConfiguration)
            throws MojoExecutionException, CompilerException {
        String inputFileEnding = compiler.getInputFileEnding(compilerConfiguration);
        if (inputFileEnding == null || inputFileEnding.isEmpty()) {
            // see MCOMPILER-199 GroovyEclipseCompiler doesn't set inputFileEnding
            // so we can presume it's all files from the source directory
            inputFileEnding = ".*";
        }
        SourceInclusionScanner scanner = getSourceInclusionScanner(inputFileEnding);

        SourceMapping mapping = getSourceMapping(compilerConfiguration, compiler);

        scanner.addSourceMapping(mapping);

        Set<File> compileSources = new HashSet<>();

        for (String sourceRoot : getCompileSourceRoots()) {
            File rootFile = new File(sourceRoot);

            if (!rootFile.isDirectory()
                    || rootFile.getAbsoluteFile().equals(compilerConfiguration.getGeneratedSourcesDirectory())) {
                continue;
            }

            try {
                compileSources.addAll(scanner.getIncludedSources(rootFile, null));
            } catch (InclusionScanException e) {
                throw new MojoExecutionException(
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
     * @return {@code true} if at least a single source file is newer than it's class file
     */
    private boolean isSourceChanged(CompilerConfiguration compilerConfiguration, Compiler compiler) {
        Set<File> staleSources = Collections.emptySet();
        try {
            staleSources = computeStaleSources(compilerConfiguration, compiler, getSourceInclusionScanner(staleMillis));
        } catch (MojoExecutionException | CompilerException ex) {
            // we cannot detect Stale Sources, so don't do anything beside logging
            getLog().warn("Cannot detect stale sources.");
            return false;
        }

        if (getLog().isDebugEnabled() || showCompilationChanges) {
            for (File f : staleSources) {
                getLog().info("\tStale source detected: " + f.getAbsolutePath());
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
        return session.getRequest().getDegreeOfConcurrency();
    }

    protected Date getBuildStartTime() {
        return getBuildStartTimeInstant().map(Date::from).orElseGet(Date::new);
    }

    private Optional<Instant> getBuildStartTimeInstant() {
        return Optional.ofNullable(session.getRequest())
                .map(MavenExecutionRequest::getStartTime)
                .map(Date::toInstant)
                .map(i -> i.truncatedTo(ChronoUnit.MILLIS));
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

    protected final Toolchain getToolchain() {
        Toolchain tc = null;

        if (jdkToolchain != null) {
            List<Toolchain> tcs = toolchainManager.getToolchains(session, "jdk", jdkToolchain);
            if (tcs != null && !tcs.isEmpty()) {
                tc = tcs.get(0);
            }
        }

        if (tc == null) {
            tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
        }

        return tc;
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
            throws MojoExecutionException, CompilerException {
        SourceMapping mapping = getSourceMapping(compilerConfiguration, compiler);

        File outputDirectory;
        CompilerOutputStyle outputStyle = compiler.getCompilerOutputStyle();
        if (outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES) {
            outputDirectory = buildDirectory;
        } else {
            outputDirectory = getOutputDirectory();
        }

        scanner.addSourceMapping(mapping);

        Set<File> staleSources = new HashSet<>();

        for (String sourceRoot : getCompileSourceRoots()) {
            File rootFile = new File(sourceRoot);

            if (!rootFile.isDirectory()) {
                continue;
            }

            try {
                staleSources.addAll(scanner.getIncludedSources(rootFile, outputDirectory));
            } catch (InclusionScanException e) {
                throw new MojoExecutionException(
                        "Error scanning source root: \'" + sourceRoot + "\' for stale files to recompile.", e);
            }
        }

        return staleSources;
    }

    private SourceMapping getSourceMapping(CompilerConfiguration compilerConfiguration, Compiler compiler)
            throws CompilerException, MojoExecutionException {
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
            throw new MojoExecutionException("Unknown compiler output style: '" + outputStyle + "'.");
        }
        return mapping;
    }

    /**
     * @todo also in ant plugin. This should be resolved at some point so that it does not need to
     * be calculated continuously - or should the plugins accept empty source roots as is?
     */
    private static List<String> removeEmptyCompileSourceRoots(List<String> compileSourceRootsList) {
        List<String> newCompileSourceRootsList = new ArrayList<>();
        if (compileSourceRootsList != null) {
            // copy as I may be modifying it
            for (String srcDir : compileSourceRootsList) {
                if (!newCompileSourceRootsList.contains(srcDir) && new File(srcDir).exists()) {
                    newCompileSourceRootsList.add(srcDir);
                }
            }
        }
        return newCompileSourceRootsList;
    }

    /**
     * We just compare the timestamps of all local dependency files (inter-module dependency classpath) and the own
     * generated classes and if we got a file which is &gt;= the build-started timestamp, then we caught a file which
     * got changed during this build.
     *
     * @return {@code true} if at least one single dependency has changed.
     */
    protected boolean isDependencyChanged() {
        final Instant buildStartTime = getBuildStartTimeInstant().orElse(null);
        if (buildStartTime == null) {
            // we just cannot determine it, so don't do anything beside logging
            getLog().debug("Cannot determine build start time, skipping incremental build detection.");
            return false;
        }

        if (fileExtensions == null || fileExtensions.isEmpty()) {
            fileExtensions = new HashSet<>(Arrays.asList("class", "jar"));
        }

        List<String> pathElements = new ArrayList<>();
        pathElements.addAll(getClasspathElements());
        pathElements.addAll(getModulepathElements());

        for (String pathElement : pathElements) {
            Path artifactPath = Paths.get(pathElement);

            // Search files only on dependencies (other modules), not on the current project,
            if (Files.isDirectory(artifactPath)
                    && !artifactPath.equals(getOutputDirectory().toPath())) {
                try (Stream<Path> walk = Files.walk(artifactPath)) {
                    if (walk.anyMatch(p -> hasNewFile(p, buildStartTime))) {
                        return true;
                    }
                } catch (IOException ex) {
                    // we just cannot determine it, so don't do anything beside logging
                    getLog().warn("I/O error walking the path: " + ex.getMessage());
                    return false;
                }
            } else if (hasNewFile(artifactPath, buildStartTime)) {
                return true;
            }
        }

        // obviously there was no new file detected.
        return false;
    }

    /**
     * @param file entry to check
     * @param buildStartTime time build start
     * @return if any changes occurred
     */
    private boolean hasNewFile(Path file, Instant buildStartTime) {
        if (Files.isRegularFile(file)
                && fileExtensions.contains(
                        FileUtils.extension(file.getFileName().toString()))) {
            try {
                Instant lastModifiedTime = Files.getLastModifiedTime(file)
                        .toInstant()
                        .minusMillis(staleMillis)
                        .truncatedTo(ChronoUnit.MILLIS);
                boolean hasChanged = lastModifiedTime.isAfter(buildStartTime);
                if (hasChanged && (getLog().isDebugEnabled() || showCompilationChanges)) {
                    getLog().info("\tNew dependency detected: " + file.toAbsolutePath());
                }
                return hasChanged;
            } catch (IOException ex) {
                // we just cannot determine it, so don't do anything beside logging
                getLog().warn("I/O error reading the lastModifiedTime: " + ex.getMessage());
            }
        }

        return false;
    }

    private List<String> resolveProcessorPathEntries() throws MojoExecutionException {
        if (annotationProcessorPaths == null || annotationProcessorPaths.isEmpty()) {
            return null;
        }

        try {
            List<org.eclipse.aether.graph.Dependency> dependencies = convertToDependencies(annotationProcessorPaths);
            List<org.eclipse.aether.graph.Dependency> managedDependencies =
                    getManagedDependenciesForAnnotationProcessorPaths();
            CollectRequest collectRequest =
                    new CollectRequest(dependencies, managedDependencies, project.getRemoteProjectRepositories());
            DependencyRequest dependencyRequest = new DependencyRequest();
            dependencyRequest.setCollectRequest(collectRequest);
            DependencyResult dependencyResult =
                    repositorySystem.resolveDependencies(session.getRepositorySession(), dependencyRequest);

            return dependencyResult.getArtifactResults().stream()
                    .map(resolved -> resolved.getArtifact().getFile().getAbsolutePath())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Resolution of annotationProcessorPath dependencies failed: " + e.getLocalizedMessage(), e);
        }
    }

    private List<org.eclipse.aether.graph.Dependency> convertToDependencies(
            List<DependencyCoordinate> annotationProcessorPaths) throws MojoExecutionException {
        List<org.eclipse.aether.graph.Dependency> dependencies = new ArrayList<>();
        for (DependencyCoordinate annotationProcessorPath : annotationProcessorPaths) {
            ArtifactHandler handler = artifactHandlerManager.getArtifactHandler(annotationProcessorPath.getType());
            String version = getAnnotationProcessorPathVersion(annotationProcessorPath);
            Artifact artifact = new DefaultArtifact(
                    annotationProcessorPath.getGroupId(),
                    annotationProcessorPath.getArtifactId(),
                    annotationProcessorPath.getClassifier(),
                    handler.getExtension(),
                    version);
            Set<Exclusion> exclusions = convertToAetherExclusions(annotationProcessorPath.getExclusions());
            dependencies.add(new org.eclipse.aether.graph.Dependency(artifact, JavaScopes.RUNTIME, false, exclusions));
        }
        return dependencies;
    }

    private String getAnnotationProcessorPathVersion(DependencyCoordinate annotationProcessorPath)
            throws MojoExecutionException {
        String configuredVersion = annotationProcessorPath.getVersion();
        if (configuredVersion != null) {
            return configuredVersion;
        } else {
            List<Dependency> managedDependencies = getProjectManagedDependencies();
            return findManagedVersion(annotationProcessorPath, managedDependencies)
                    .orElseThrow(() -> new MojoExecutionException(String.format(
                            "Cannot find version for annotation processor path '%s'. The version needs to be either"
                                    + " provided directly in the plugin configuration or via dependency management.",
                            annotationProcessorPath)));
        }
    }

    private Optional<String> findManagedVersion(
            DependencyCoordinate dependencyCoordinate, List<Dependency> managedDependencies) {
        return managedDependencies.stream()
                .filter(dep -> Objects.equals(dep.getGroupId(), dependencyCoordinate.getGroupId())
                        && Objects.equals(dep.getArtifactId(), dependencyCoordinate.getArtifactId())
                        && Objects.equals(dep.getClassifier(), dependencyCoordinate.getClassifier())
                        && Objects.equals(dep.getType(), dependencyCoordinate.getType()))
                .findAny()
                .map(org.apache.maven.model.Dependency::getVersion);
    }

    private List<org.eclipse.aether.graph.Dependency> getManagedDependenciesForAnnotationProcessorPaths() {
        if (!annotationProcessorPathsUseDepMgmt) {
            return Collections.emptyList();
        }
        List<Dependency> projectManagedDependencies = getProjectManagedDependencies();
        ArtifactTypeRegistry artifactTypeRegistry =
                session.getRepositorySession().getArtifactTypeRegistry();

        return projectManagedDependencies.stream()
                .map(dep -> RepositoryUtils.toDependency(dep, artifactTypeRegistry))
                .collect(Collectors.toList());
    }

    private List<Dependency> getProjectManagedDependencies() {
        DependencyManagement dependencyManagement = project.getDependencyManagement();
        if (dependencyManagement == null || dependencyManagement.getDependencies() == null) {
            return Collections.emptyList();
        }
        return dependencyManagement.getDependencies();
    }

    private Set<Exclusion> convertToAetherExclusions(Set<DependencyExclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Exclusion> aetherExclusions = new HashSet<>();
        for (DependencyExclusion exclusion : exclusions) {
            Exclusion aetherExclusion = new Exclusion(
                    exclusion.getGroupId(),
                    exclusion.getArtifactId(),
                    exclusion.getClassifier(),
                    exclusion.getExtension());
            aetherExclusions.add(aetherExclusion);
        }
        return aetherExclusions;
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

        if (release != null) {
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

    private boolean hasInputFileTreeChanged(IncrementalBuildHelper ibh, Set<File> inputFiles) {
        Path mojoConfigBase;
        try {
            mojoConfigBase = ibh.getMojoStatusDirectory().toPath();
        } catch (MojoExecutionException e) {
            // we cannot get the mojo status dir, so don't do anything beside logging
            getLog().warn("Error reading mojo status directory.");
            return false;
        }
        Path mojoConfigFile = mojoConfigBase.resolve(INPUT_FILES_LST_FILENAME);

        List<String> oldInputFiles = Collections.emptyList();
        if (Files.isRegularFile(mojoConfigFile)) {
            try {
                oldInputFiles = Files.readAllLines(mojoConfigFile);
            } catch (IOException e) {
                // we cannot read the mojo config file, so don't do anything beside logging
                getLog().warn("Error while reading old mojo status: " + mojoConfigFile);
                return false;
            }
        }

        List<String> newInputFiles =
                inputFiles.stream().sorted().map(File::getAbsolutePath).collect(Collectors.toList());

        try {
            Files.write(mojoConfigFile, newInputFiles);
        } catch (IOException e) {
            // we cannot write the mojo config file, so don't do anything beside logging
            getLog().warn("Error while writing new mojo status: " + mojoConfigFile);
            return false;
        }

        DeltaList<String> inputTreeChanges = new DeltaList<>(oldInputFiles, newInputFiles);
        if (getLog().isDebugEnabled() || showCompilationChanges) {
            for (String fileAdded : inputTreeChanges.getAdded()) {
                getLog().info("\tInput tree files (+): " + fileAdded);
            }
            for (String fileRemoved : inputTreeChanges.getRemoved()) {
                getLog().info("\tInput tree files (-): " + fileRemoved);
            }
        }

        return inputTreeChanges.hasChanged();
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
    private void patchJdkModuleVersion(CompilerResult compilerResult, Set<File> sources) throws MojoExecutionException {
        if (compilerResult.isSuccess() && getModuleDeclaration(sources).isPresent()) {
            Path moduleDescriptor = getOutputDirectory().toPath().resolve("module-info.class");
            if (Files.isRegularFile(moduleDescriptor)) {
                try {
                    final byte[] descriptorOriginal = Files.readAllBytes(moduleDescriptor);
                    final byte[] descriptorMod =
                            ModuleInfoTransformer.transform(descriptorOriginal, getRelease(), getLog());
                    if (descriptorMod != null) {
                        Files.write(moduleDescriptor, descriptorMod);
                    }
                } catch (IOException ex) {
                    throw new MojoExecutionException("Error reading or writing module-info.class", ex);
                }
            }
        }
    }
}
