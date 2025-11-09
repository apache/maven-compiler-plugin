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
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.OptionChecker;
import javax.tools.ToolProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamTokenizer;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.StringJoiner;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.Language;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.PathType;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.Type;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.MavenException;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.ToolchainManager;

import static org.apache.maven.plugin.compiler.SourceDirectory.CLASS_FILE_SUFFIX;
import static org.apache.maven.plugin.compiler.SourceDirectory.MODULE_INFO;

/**
 * Base class of Mojos compiling Java source code.
 * This plugin uses the {@link JavaCompiler} interface from JDK 6+.
 * Each instance shall be used only once, then discarded.
 *
 * <h2>Thread-safety</h2>
 * This class is not thread-safe. If this class is used in a multi-thread context,
 * users are responsible for synchronizing all accesses to this <abbr>MOJO</abbr> instance.
 * However, the executor returned by {@link #createExecutor(DiagnosticListener)} can safely
 * launch the compilation in a background thread.
 *
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugstøl</a>
 * @author Martin Desruisseaux
 * @since 2.0
 */
public abstract class AbstractCompilerMojo implements Mojo {
    /**
     * Whether to support legacy (and often deprecated) behavior.
     * This is currently hard-coded to {@code true} for compatibility reason.
     * TODO: consider making configurable.
     */
    static final boolean SUPPORT_LEGACY = true;

    /**
     * Name of a {@link SourceVersion} enumeration value for a version above 17 (the current Maven target).
     * The {@code SourceVersion} value cannot be referenced directly because it does not exist in Java 17.
     * Used for detecting if {@code module-info.class} needs to be patched for reproducible builds.
     */
    private static final String RELEASE_22 = "RELEASE_22";

    /**
     * Name of a {@link SourceVersion} enumeration value for a version above 17 (the current Maven target).
     * The {@code SourceVersion} value cannot be referenced directly because it does not exist in Java 17.
     * Used for determining the default value of the {@code -proc} compiler option.
     */
    private static final String RELEASE_23 = "RELEASE_23";

    /**
     * The executable to use by default if nine is specified.
     */
    private static final String DEFAULT_EXECUTABLE = "javac";

    // ----------------------------------------------------------------------
    // Configurables
    // ----------------------------------------------------------------------

    /**
     * The {@code --module-version} argument for the Java compiler.
     * This is ignored if not applicable, e.g., in non-modular projects.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-module-version">javac --module-version</a>
     * @since 4.0.0
     */
    @Parameter(property = "maven.compiler.moduleVersion", defaultValue = "${project.version}")
    protected String moduleVersion;

    /**
     * The {@code -encoding} argument for the Java compiler.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-encoding">javac -encoding</a>
     * @since 2.1
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
    protected String encoding;

    /**
     * {@return the character set used for decoding bytes, or null for the platform default}
     * No warning is emitted in the latter case because as of Java 18, the default is UTF-8,
     * i.e. the encoding is no longer platform-dependent.
     */
    final Charset charset() {
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (UnsupportedCharsetException e) {
                throw new CompilationFailureException("Invalid 'encoding' option: " + encoding, e);
            }
        }
        return null;
    }

    /**
     * The {@code --source} argument for the Java compiler.
     * <p><b>Notes:</b></p>
     * <ul>
     *   <li>Since 3.8.0 the default value has changed from 1.5 to 1.6.</li>
     *   <li>Since 3.9.0 the default value has changed from 1.6 to 1.7.</li>
     *   <li>Since 3.11.0 the default value has changed from 1.7 to 1.8.</li>
     *   <li>Since 4.0.0-beta-2 the default value has been removed.
     *       As of Java 9, the {@link #release} parameter is preferred.</li>
     * </ul>
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-source">javac --source</a>
     */
    @Parameter(property = "maven.compiler.source")
    protected String source;

    /**
     * The {@code --target} argument for the Java compiler.
     * <p><b>Notes:</b></p>
     * <ul>
     *   <li>Since 3.8.0 the default value has changed from 1.5 to 1.6.</li>
     *   <li>Since 3.9.0 the default value has changed from 1.6 to 1.7.</li>
     *   <li>Since 3.11.0 the default value has changed from 1.7 to 1.8.</li>
     *   <li>Since 4.0.0-beta-2 the default value has been removed.
     *       As of Java 9, the {@link #release} parameter is preferred.</li>
     * </ul>
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-target">javac --target</a>
     */
    @Parameter(property = "maven.compiler.target")
    protected String target;

    /**
     * The {@code --release} argument for the Java compiler when the sources do not declare this version.
     * The suggested way to declare the target Java release is to specify it with the sources like below:
     *
     * <pre>{@code
     * <build>
     *   <sources>
     *     <source>
     *       <directory>src/main/java</directory>
     *       <targetVersion>17</targetVersion>
     *     </source>
     *   </sources>
     * </build>}</pre>
     *
     * If such {@code <targetVersion>} element is found, it has precedence over this {@code release} property.
     * If a source does not declare a target Java version, then the value of this {@code release} property is
     * used as a fallback.
     * If omitted, the compiler will generate bytecodes for the Java version running the compiler.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-release">javac --release</a>
     * @since 3.6
     */
    @Parameter(property = "maven.compiler.release")
    protected String release;

    /**
     * Whether {@link #target} or {@link #release} has a non-blank value.
     * Used for logging a warning if no target Java version was specified.
     */
    private boolean targetOrReleaseSet;

    /**
     * The highest version supported by the compiler, or {@code null} if not yet determined.
     *
     * @see #isVersionEqualOrNewer(String)
     */
    private SourceVersion supportedVersion;

    /**
     * Whether to enable preview language features of the java compiler.
     * If {@code true}, then the {@code --enable-preview} option will be added to compiler arguments.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-enable-preview">javac --enable-preview</a>
     * @since 3.10.1
     */
    @Parameter(property = "maven.compiler.enablePreview", defaultValue = "false")
    protected boolean enablePreview;

    /**
     * The root directories containing the source files to be compiled. If {@code null} or empty,
     * the directories will be obtained from the {@code <Source>} elements declared in the project.
     * If non-empty, the project {@code <Source>} elements are ignored. This configuration option
     * should be used only when there is a need to override the project configuration.
     *
     * @deprecated Replaced by the project-wide {@code <sources>} element.
     */
    @Parameter
    @Deprecated(since = "4.0.0")
    protected List<String> compileSourceRoots;

    /**
     * Additional arguments to be passed verbatim to the Java compiler. This parameter can be used when
     * the Maven compiler plugin does not provide a parameter for a Java compiler option. It may happen,
     * for example, for new or preview Java features which are not yet handled by this compiler plugin.
     *
     * <p>If an option has a value, the option and the value shall be specified in two separated {@code <arg>}
     * elements. For example, the {@code -Xmaxerrs 1000} option (for setting the maximal number of errors to
     * 1000) can be specified as below (together with other options):</p>
     *
     * <pre>{@code
     * <compilerArgs>
     *   <arg>-Xlint</arg>
     *   <arg>-Xmaxerrs</arg>
     *   <arg>1000</arg>
     *   <arg>J-Duser.language=en_us</arg>
     * </compilerArgs>}</pre>
     *
     * Note that {@code -J} options should be specified only if {@link #fork} is set to {@code true}.
     * Other options can be specified regardless the {@link #fork} value.
     * The compiler plugin does not verify whether the arguments given through this parameter are valid.
     * For this reason, the other parameters provided by the compiler plugin should be preferred when
     * they exist, because the plugin checks whether the corresponding options are supported.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-J">javac -J</a>
     * @since 3.1
     */
    @Parameter
    protected List<String> compilerArgs;

    /**
     * The single argument string to be passed to the compiler. To pass multiple arguments such as
     * {@code -Xmaxerrs 1000} (which are actually two arguments), {@link #compilerArgs} is preferred.
     *
     * <p>Note that {@code -J} options should be specified only if {@link #fork} is set to {@code true}.</p>
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-J">javac -J</a>
     *
     * @deprecated Use {@link #compilerArgs} instead.
     */
    @Parameter
    @Deprecated(since = "4.0.0")
    protected String compilerArgument;

    /**
     * Configures if annotation processing and/or compilation are performed by the compiler.
     * If set, the value will be appended to the {@code -proc:} compiler option.
     *
     * Possible values are:
     * <ul>
     *   <li>{@code none} – no annotation processing is performed, only compilation is done.</li>
     *   <li>{@code only} – only annotation processing is done, no compilation.</li>
     *   <li>{@code full} – annotation processing followed by compilation is done.</li>
     * </ul>
     *
     * The default value depends on the JDK used for the build.
     * Prior to Java 23, the default was {@code full},
     * so annotation processing and compilation were executed without explicit configuration.
     *
     * For security reasons, starting with Java 23 no annotation processing is done if neither
     * any {@code -processor}, {@code -processor path} or {@code -processor module} are set,
     * or either {@code only} or {@code full} is set.
     * So literally the default is {@code none}.
     * It is recommended to always list the annotation processors you want to execute
     * instead of using the {@code proc} configuration,
     * to ensure that only desired processors are executed and not any "hidden" (and maybe malicious).
     *
     * @see #annotationProcessors
     * @see <a href="https://inside.java/2024/06/18/quality-heads-up/">Inside Java 2024-06-18 Quality Heads up</a>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-proc">javac -proc</a>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#annotation-processing">javac Annotation Processing</a>
     * @since 2.2
     */
    @Parameter(property = "maven.compiler.proc")
    protected String proc;
    // Reminder: if above list of legal values is modified, update also addComaSeparated("-proc", …)

    /**
     * Class names of annotation processors to run.
     * If not set, the default annotation processors discovery process applies.
     * If set, the value will be appended to the {@code -processor} compiler option.
     *
     * @see #proc
     * @since 2.2
     */
    @Parameter
    protected String[] annotationProcessors;

    /**
     * Classpath elements to supply as annotation processor path. If specified, the compiler will detect annotation
     * processors only in those classpath elements. If omitted (and {@code proc} is set to {@code only} or {@code full}), the default classpath is used to detect annotation
     * processors. The detection itself depends on the configuration of {@link #annotationProcessors}.
     * Since JDK 23 by default no annotation processing is performed as long as no processors is listed for security reasons.
     * Therefore, you should always list the desired processors using this configuration element or {@code annotationProcessorPaths}.
     *
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
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-processor-path">javac -processorpath</a>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#annotation-processing">javac Annotation Processing</a>
     * @since 3.5
     *
     * @deprecated Replaced by ordinary dependencies with {@code <type>} element set to
     * {@code processor}, {@code classpath-processor} or {@code modular-processor}.
     */
    @Parameter
    @Deprecated(since = "4.0.0")
    protected List<DependencyCoordinate> annotationProcessorPaths;

    /**
     * Whether to use the Maven dependency management section when resolving transitive dependencies of annotation
     * processor paths.
     * <p>
     * This flag does not enable / disable the ability to resolve the version of annotation processor paths
     * from dependency management section. It only influences the resolution of transitive dependencies of those
     * top-level paths.
     * </p>
     *
     * @since 3.12.0
     *
     * @deprecated This flag is ignored.
     * Replaced by ordinary dependencies with {@code <type>} element set to
     * {@code processor}, {@code classpath-processor} or {@code modular-processor}.
     */
    @Deprecated(since = "4.0.0")
    @Parameter(defaultValue = "false")
    protected boolean annotationProcessorPathsUseDepMgmt;

    /**
     * Whether to generate {@code package-info.class} even when empty.
     * By default, package info source files that only contain javadoc and no annotation
     * on the package can lead to no class file being generated by the compiler.
     * It may cause a file miss on build systems that check for file existence in order to decide what to recompile.
     *
     * <p>If {@code true}, the {@code -Xpkginfo:always} compiler option is added if the compiler supports that
     * extra option. If the extra option is not supported, then a warning is logged and no option is added to
     * the compiler arguments.</p>
     *
     * @see #incrementalCompilation
     * @since 3.10
     */
    @Parameter(property = "maven.compiler.createMissingPackageInfoClass", defaultValue = "false")
    protected boolean createMissingPackageInfoClass;

    /**
     * Whether to generate class files for implicitly referenced files.
     * If set, the value will be appended to the {@code -implicit:} compiler option.
     * Standard values are:
     * <ul>
     *   <li>{@code class} – automatically generates class files.</li>
     *   <li>{@code none}  – suppresses class file generation.</li>
     * </ul>
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-implicit">javac -implicit</a>
     * @since 3.10.2
     */
    @Parameter(property = "maven.compiler.implicit")
    protected String implicit;
    // Reminder: if above list of legal values is modified, update also addComaSeparated("-implicit", …)

    /**
     * Whether to generate metadata for reflection on method parameters.
     * If {@code true}, the {@code -parameters} option will be added to compiler arguments.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-parameters">javac -parameters</a>
     * @since 3.6.2
     */
    @Parameter(property = "maven.compiler.parameters", defaultValue = "false")
    protected boolean parameters;

    /**
     * Whether to include debugging information in the compiled class files.
     * The amount of debugging information to include is specified by the {@link #debuglevel} parameter.
     * If this {@code debug} flag is {@code true}, then the {@code -g} option may be added to compiler arguments
     * with a value determined by the {@link #debuglevel} argument. If this {@code debug} flag is {@code false},
     * then the {@code -g:none} option will be added to the compiler arguments.
     *
     * @see #debuglevel
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-g">javac -g</a>
     *
     * @deprecated Setting this flag to {@code false} is replaced by {@code <debuglevel>none</debuglevel>}.
     */
    @Deprecated(since = "4.0.0")
    @Parameter(property = "maven.compiler.debug", defaultValue = "true")
    protected boolean debug = true;

    /**
     * Kinds of debugging information to include in the compiled class files.
     * Legal values are {@code lines}, {@code vars}, {@code source}, {@code all} and {@code none}.
     * Values other than {@code all} and {@code none} can be combined in a comma-separated list.
     *
     * <p>If debug level is not specified, then the {@code -g} option will <em>not</em> be added,
     * which means that the default debugging information will be generated
     * (typically {@code lines} and {@code source} but not {@code vars}).</p>
     *
     * <p>If debug level is {@code all}, then only the {@code -g} option is added,
     * which means that all debugging information will be generated.
     * If debug level is anything else, then the comma-separated list of keywords
     * is appended to the {@code -g} command-line switch.</p>
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-g-custom">javac -g:[lines,vars,source]</a>
     * @since 2.1
     */
    @Parameter(property = "maven.compiler.debuglevel")
    protected String debuglevel;
    // Reminder: if above list of legal values is modified, update also addComaSeparated("-g", …)

    /**
     * Whether to optimize the compiled code using the compiler's optimization methods.
     * @deprecated This property is ignored.
     */
    @Deprecated(forRemoval = true)
    @Parameter(property = "maven.compiler.optimize")
    protected Boolean optimize;

    /**
     * Whether to show messages about what the compiler is doing.
     * If {@code true}, then the {@code -verbose} option will be added to compiler arguments.
     * In addition, files such as {@code target/javac.args} will be generated even on successful compilation.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-verbose">javac -verbose</a>
     */
    @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
    protected boolean verbose;

    /**
     * Whether to provide more details about why a module is rebuilt.
     * This is used only if {@link #incrementalCompilation} is set to something else than {@code "none"}.
     *
     * @see #incrementalCompilation
     */
    @Parameter(property = "maven.compiler.showCompilationChanges", defaultValue = "false")
    protected boolean showCompilationChanges;

    /**
     * Whether to show source locations where deprecated APIs are used.
     * If {@code true}, then the {@code -deprecation} option will be added to compiler arguments.
     * That option is itself a shorthand for {@code -Xlint:deprecation}.
     *
     * @see #showWarnings
     * @see #failOnWarning
     */
    @Parameter(property = "maven.compiler.showDeprecation", defaultValue = "false")
    protected boolean showDeprecation;

    /**
     * Whether to show compilation warnings.
     * If {@code false}, then the {@code -nowarn} option will be added to compiler arguments.
     * That option is itself a shorthand for {@code -Xlint:none}.
     *
     * @see #showDeprecation
     * @see #failOnWarning
     */
    @Parameter(property = "maven.compiler.showWarnings", defaultValue = "true")
    protected boolean showWarnings = true;

    /**
     * Whether the build will stop if there are compilation warnings.
     * If {@code true}, then the {@code -Werror} option will be added to compiler arguments.
     *
     * @see #showWarnings
     * @see #showDeprecation
     * @since 3.6
     */
    @Parameter(property = "maven.compiler.failOnWarning", defaultValue = "false")
    protected boolean failOnWarning;

    /**
     * Whether the build will stop if there are compilation errors.
     *
     * @see #failOnWarning
     * @since 2.0.2
     */
    @Parameter(property = "maven.compiler.failOnError", defaultValue = "true")
    protected boolean failOnError = true;

    /**
     * Sets the name of the output file when compiling a set of sources to a single file.
     *
     * <p>expression="${project.build.finalName}"</p>
     *
     * @deprecated Bundling many class files into a single file should be done by other plugins.
     */
    @Parameter
    @Deprecated(since = "4.0.0", forRemoval = true)
    protected String outputFileName;

    /**
     * Timestamp for reproducible output archive entries. It can be either formatted as ISO 8601
     * {@code yyyy-MM-dd'T'HH:mm:ssXXX} or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 3.12.0
     *
     * @deprecated Not used by the compiler plugin since it does not generate archive.
     */
    @Deprecated(since = "4.0.0", forRemoval = true)
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    protected String outputTimestamp;

    /**
     * The algorithm to use for selecting which files to compile.
     * Values can be {@code dependencies}, {@code sources}, {@code classes}, {@code rebuild-on-change},
     * {@code rebuild-on-add}, {@code modules} or {@code none}.
     *
     * <p><b>{@code options}:</b>
     * recompile all source files if the compiler options changed.
     * Changes are detected on a <i>best-effort</i> basis only.</p>
     *
     * <p><b>{@code dependencies}:</b>
     * recompile all source files if at least one dependency (JAR file) changed since the last build.
     * This check is based on the last modification times of JAR files.</p>
     *
     * <p><b>{@code sources}:</b>
     * recompile source files modified since the last build.
     * In addition, if a source file has been deleted, then all source files are recompiled.
     * This check is based on the modification times of source files
     * rather than the modification times of the {@code *.class} files.</p>
     *
     * <p><b>{@code classes}:</b>
     * recompile source files ({@code *.java}) associated to no output file ({@code *.class})
     * or associated to an output file older than the source. This algorithm does not check
     * if a source file has been removed, potentially leaving non-recompiled classes with
     * references to classes that no longer exist.</p>
     *
     * <p>The {@code sources} and {@code classes} values are partially redundant,
     * doing the same work in different ways. It is usually not necessary to specify those two values.</p>
     *
     * <p><b>{@code modules}:</b>
     * recompile modules and let the compiler decides which individual files to recompile.
     * The compiler plugin does not enumerate the source files to recompile (actually, it does not scan at all the
     * source directories). Instead, it only specifies the module to recompile using the {@code --module} option.
     * The Java compiler will scan the source directories itself and compile only those source files that are newer
     * than the corresponding files in the output directory.</p>
     *
     * <p><b>{@code rebuild-on-add}:</b>
     * modifier for recompiling all source files when the addition of a new file is detected.
     * This flag is effective only when used together with {@code sources} or {@code classes}.
     * When used with {@code classes}, it provides a way to detect class renaming
     * (this is not needed with {@code sources} for detecting renaming).</p>
     *
     * <p><b>{@code rebuild-on-change}:</b>
     * modifier for recompiling all source files when a change is detected in at least one source file.
     * This flag is effective only when used together with {@code sources} or {@code classes}.
     * It does not rebuild when a new source file is added without change in other files,
     * unless {@code rebuild-on-add} is also specified.</p>
     *
     * <p><b>{@code none}:</b>
     * the compiler plugin unconditionally specifies all sources to the Java compiler.
     * This option is mutually exclusive with all other incremental compilation options.</p>
     *
     * <h4>Limitations</h4>
     * In all cases, the current compiler-plugin does not detect structural changes other than file addition or removal.
     * For example, the plugin does not detect whether a method has been removed in a class.
     *
     * <h4>Default value</h4>
     * The default value depends on the context.
     * If there is no annotation processor, then the default is {@code "options,dependencies,sources"}.
     * It means that a full rebuild will be done if the compiler options or the dependencies changed,
     * or if a source file has been deleted. Otherwise, only the modified source files will be recompiled.
     *
     * <p>If an annotation processor is present (e.g., {@link #proc} set to a value other than {@code "none"}),
     * then the default value is same as above with the addition of {@code "rebuild-on-add,rebuild-on-change"}.
     * It means that a full rebuild will be done if any kind of change is detected.</p>
     *
     * @see #staleMillis
     * @see #fileExtensions
     * @see #showCompilationChanges
     * @see #createMissingPackageInfoClass
     * @since 4.0.0
     */
    @Parameter(property = "maven.compiler.incrementalCompilation")
    protected String incrementalCompilation;

    /**
     * Whether to enable/disable incremental compilation feature.
     *
     * @since 3.1
     *
     * @deprecated Replaced by {@link #incrementalCompilation}.
     * A value of {@code true} in this old property is equivalent to {@code "dependencies,sources,rebuild-on-add"}
     * in the new property, and a value of {@code false} is equivalent to {@code "classes"}.
     */
    @Deprecated(since = "4.0.0")
    @Parameter(property = "maven.compiler.useIncrementalCompilation")
    protected Boolean useIncrementalCompilation;

    /**
     * Returns the configuration of the incremental compilation.
     * If the argument is null or blank, then this method applies
     * the default values documented in {@link #incrementalCompilation} javadoc.
     *
     * @throws MojoException if a value is not recognized, or if mutually exclusive values are specified
     */
    final EnumSet<IncrementalBuild.Aspect> incrementalCompilationConfiguration() {
        if (isAbsent(incrementalCompilation)) {
            if (useIncrementalCompilation != null) {
                return useIncrementalCompilation
                        ? EnumSet.of(
                                IncrementalBuild.Aspect.DEPENDENCIES,
                                IncrementalBuild.Aspect.SOURCES,
                                IncrementalBuild.Aspect.REBUILD_ON_ADD)
                        : EnumSet.of(IncrementalBuild.Aspect.CLASSES);
            }
            return EnumSet.of(
                    IncrementalBuild.Aspect.OPTIONS,
                    IncrementalBuild.Aspect.DEPENDENCIES,
                    IncrementalBuild.Aspect.SOURCES);
        }
        return IncrementalBuild.Aspect.parse(incrementalCompilation);
    }

    /**
     * Amends the configuration of incremental compilation for the presence of annotation processors.
     *
     * @param aspects the configuration to amend if an annotation processor is found
     * @param dependencyTypes the type of dependencies, for checking if any of them is a processor path
     */
    final void amendincrementalCompilation(EnumSet<IncrementalBuild.Aspect> aspects, Set<PathType> dependencyTypes) {
        if (isAbsent(incrementalCompilation) && hasAnnotationProcessor(dependencyTypes)) {
            aspects.add(IncrementalBuild.Aspect.REBUILD_ON_ADD);
            aspects.add(IncrementalBuild.Aspect.REBUILD_ON_CHANGE);
        }
    }

    /**
     * File extensions to check timestamp for incremental build.
     * Default contains only {@code class} and {@code jar}.
     *
     * TODO: Rename with a name making clearer that this parameter is about incremental build.
     *
     * @see #incrementalCompilation
     * @since 3.1
     */
    @Parameter(defaultValue = "class,jar")
    protected List<String> fileExtensions;

    /**
     * The granularity in milliseconds of the last modification
     * date for testing whether a source needs recompilation.
     *
     * @see #incrementalCompilation
     */
    @Parameter(property = "lastModGranularityMs", defaultValue = "0")
    protected int staleMillis;

    /**
     * Allows running the compiler in a separate process.
     * If {@code false}, the plugin uses the built-in compiler, while if {@code true} it will use an executable.
     *
     * @see #executable
     * @see #compilerId
     * @see #meminitial
     * @see #maxmem
     */
    @Parameter(property = "maven.compiler.fork", defaultValue = "false")
    protected boolean fork;

    /**
     * Requirements for this JDK toolchain for using a different {@code javac} than the one of the JDK used by Maven.
     * This overrules the toolchain selected by the
     * <a href="https://maven.apache.org/plugins/maven-toolchains-plugin/">maven-toolchain-plugin</a>.
     * See <a href="https://maven.apache.org/guides/mini/guide-using-toolchains.html"> Guide to Toolchains</a>
     * for more info.
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
     *
     * @see #fork
     * @see #executable
     * @since 3.6
     */
    @Parameter
    protected Map<String, String> jdkToolchain;

    /**
     * Identifier of the compiler to use. This identifier shall match the identifier of a compiler known
     * to the {@linkplain #jdkToolchain JDK tool chain}, or the {@linkplain JavaCompiler#name() name} of
     * a {@link JavaCompiler} instance registered as a service findable by {@link ServiceLoader}.
     * See this <a href="non-javac-compilers.html">guide</a> for more information.
     * If unspecified, then the {@linkplain ToolProvider#getSystemJavaCompiler() system Java compiler} is used.
     * The identifier of the system Java compiler is usually {@code javac}.
     *
     * @see #fork
     * @see #executable
     * @see JavaCompiler#name()
     */
    @Parameter(property = "maven.compiler.compilerId")
    protected String compilerId;

    /**
     * Version of the compiler to use if {@link #fork} is set to {@code true}.
     * Examples! "1.3", "1.5".
     *
     * @deprecated This parameter is no longer used by the underlying compilers.
     *
     * @see #fork
     */
    @Deprecated(since = "4.0.0", forRemoval = true)
    @Parameter(property = "maven.compiler.compilerVersion")
    protected String compilerVersion;

    /**
     * Whether to use the legacy {@code com.sun.tools.javac} API instead of {@code javax.tools} API.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/api/java.compiler/javax/tools/package-summary.html">New API</a>
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/api/jdk.compiler/com/sun/tools/javac/package-summary.html">Legacy API</a>
     * @since 3.13
     *
     * @deprecated Ignored because the compiler plugin now always use the {@code javax.tools} API.
     */
    @Deprecated(since = "4.0.0", forRemoval = true)
    @Parameter(property = "maven.compiler.forceLegacyJavacApi")
    protected Boolean forceLegacyJavacApi;

    /**
     * Whether to use legacy compiler API.
     *
     * @since 3.0
     *
     * @deprecated Ignored because {@code java.lang.Compiler} has been deprecated and removed from the JDK.
     */
    @Deprecated(since = "4.0.0", forRemoval = true)
    @Parameter(property = "maven.compiler.forceJavacCompilerUse")
    protected Boolean forceJavacCompilerUse;

    /**
     * Strategy to re use {@code javacc} class created. Legal values are:
     * <ul>
     *   <li>{@code reuseCreated} (default) – will reuse already created but in case of multi-threaded builds,
     *       each thread will have its own instance.</li>
     *   <li>{@code reuseSame} – the same Javacc class will be used for each compilation even
     *       for multi-threaded build.</li>
     *   <li>{@code alwaysNew} – a new Javacc class will be created for each compilation.</li>
     * </ul>
     * Note this parameter value depends on the OS/JDK you are using, but the default value should work on most of env.
     *
     * @since 2.5
     *
     * @deprecated Not supported anymore. The reuse of {@link JavaFileManager} instance is plugin implementation details.
     */
    @Deprecated(since = "4.0.0", forRemoval = true)
    @Parameter(property = "maven.compiler.compilerReuseStrategy")
    protected String compilerReuseStrategy;

    /**
     * @since 2.5
     *
     * @deprecated Deprecated as a consequence of {@link #compilerReuseStrategy} deprecation.
     */
    @Deprecated(since = "4.0.0", forRemoval = true)
    @Parameter(property = "maven.compiler.skipMultiThreadWarning")
    protected Boolean skipMultiThreadWarning;

    /**
     * Executable of the compiler to use when {@link #fork} is {@code true}.
     * If this parameter is specified, then the {@link #jdkToolchain} is ignored.
     *
     * @see #jdkToolchain
     * @see #fork
     * @see #compilerId
     */
    @Parameter(property = "maven.compiler.executable")
    protected String executable;

    /**
     * Initial size, in megabytes, of the memory allocation pool if {@link #fork} is set to {@code true}.
     * Examples: "64", "64M". Suffixes "k" (for kilobytes) and "G" (for gigabytes) are also accepted.
     * If no suffix is provided, "M" is assumed.
     *
     * @see #fork
     * @since 2.0.1
     */
    @Parameter(property = "maven.compiler.meminitial")
    protected String meminitial;

    /**
     * Maximum size, in megabytes, of the memory allocation pool if {@link #fork} is set to {@code true}.
     * Examples: "128", "128M". Suffixes "k" (for kilobytes) and "G" (for gigabytes) are also accepted.
     * If no suffix is provided, "M" is assumed.
     *
     * @see #fork
     * @since 2.0.1
     */
    @Parameter(property = "maven.compiler.maxmem")
    protected String maxmem;

    // ----------------------------------------------------------------------
    // Read-only parameters
    // ----------------------------------------------------------------------

    /**
     * The directory to run the compiler from if fork is true.
     */
    @Parameter(defaultValue = "${project.basedir}", required = true, readonly = true)
    protected Path basedir;

    /**
     * Path to a file where to cache information about the last incremental build.
     * This is used when "incremental" builds are enabled for detecting additions
     * or removals of source files, or changes in plugin configuration.
     * This file should be in the output directory and can be deleted at any time
     */
    @Parameter(
            defaultValue =
                    "${project.build.directory}/maven-status/${mojo.plugin.descriptor.artifactId}/${mojo.executionId}.cache",
            required = true,
            readonly = true)
    protected Path mojoStatusPath;

    /**
     * The current build session instance.
     */
    @Inject
    protected Session session;

    /**
     * The current project instance.
     */
    @Inject
    protected Project project;

    @Inject
    protected ProjectManager projectManager;

    @Inject
    protected ArtifactManager artifactManager;

    @Inject
    protected ToolchainManager toolchainManager;

    @Inject
    protected MessageBuilderFactory messageBuilderFactory;

    /**
     * The logger for reporting information or warnings to the user.
     * Currently, this is also used for console output.
     *
     * <h4>Thread safety</h4>
     * This logger should be thread-safe if the {@link ToolExecutor} is executed in a background thread.
     */
    @Inject
    protected Log logger;

    /**
     * Cached value for writing replacement proposal when a deprecated option is used.
     * This is set to a non-null value when first needed. An empty string means that
     * this information couldn't be fetched.
     *
     * @see #writePlugin(MessageBuilder, String, String)
     */
    private String mavenCompilerPluginVersion;

    /**
     * A tip about how to launch the Java compiler from the command-line.
     * The command-line may have {@code -J} options before the argument file.
     * This is non-null if the compilation failed or if Maven is executed in debug mode.
     */
    private String tipForCommandLineCompilation;

    /**
     * {@code MAIN_COMPILE} if this MOJO is for compiling the main code,
     * or {@code TEST_COMPILE} if compiling the tests.
     */
    final PathScope compileScope;

    /**
     * Creates a new MOJO.
     *
     * @param compileScope {@code MAIN_COMPILE} or {@code TEST_COMPILE}
     */
    protected AbstractCompilerMojo(PathScope compileScope) {
        this.compileScope = compileScope;
    }

    /**
     * {@return the inclusion filters for the compiler, or an empty list for all Java source files}
     * The filter patterns are described in {@link java.nio.file.FileSystem#getPathMatcher(String)}.
     * If no syntax is specified, the default syntax is a derivative of "glob" compatible with the
     * behavior of Maven 3.
     */
    protected abstract Set<String> getIncludes();

    /**
     * {@return the exclusion filters for the compiler, or an empty list if none}
     * The filter patterns are described in {@link java.nio.file.FileSystem#getPathMatcher(String)}.
     * If no syntax is specified, the default syntax is a derivative of "glob" compatible with the
     * behavior of Maven 3.
     */
    protected abstract Set<String> getExcludes();

    /**
     * {@return the exclusion filters for the incremental calculation}
     * Updated source files, if excluded by this filter, will not cause the project to be rebuilt.
     *
     * @see SourceFile#ignoreModification
     */
    protected abstract Set<String> getIncrementalExcludes();

    /**
     * {@return whether all includes/excludes matchers specified in the plugin configuration are empty}
     * This method checks only the plugin configuration. It does not check the {@code <source>} elements.
     */
    final boolean hasNoFileMatchers() {
        return getIncludes().isEmpty()
                && getExcludes().isEmpty()
                && getIncrementalExcludes().isEmpty();
    }

    /**
     * {@return the destination directory (or class output directory) for class files}
     * This directory will be given to the {@code -d} Java compiler option.
     */
    @Nonnull
    protected abstract Path getOutputDirectory();

    /**
     * {@return the {@code --source} argument for the Java compiler}
     * The default implementation returns the {@link #source} value.
     */
    @Nullable
    protected String getSource() {
        return source;
    }

    /**
     * {@return the {@code --target} argument for the Java compiler}
     * The default implementation returns the {@link #target} value.
     */
    @Nullable
    protected String getTarget() {
        return target;
    }

    /**
     * {@return the {@code --release} argument for the Java compiler}
     * The default implementation returns the {@link #release} value.
     */
    @Nullable
    protected String getRelease() {
        return release;
    }

    /**
     * {@return the root directories of Java source code for the given scope}
     * This method ignores the deprecated {@link #compileSourceRoots} element.
     *
     * @param scope whether to get the directories for main code or for the test code
     */
    final Stream<SourceRoot> getSourceRoots(ProjectScope scope) {
        return projectManager.getEnabledSourceRoots(project, scope, Language.JAVA_FAMILY);
    }

    /**
     * {@return the root directories of the Java source files to compile, excluding empty directories}
     * The list needs to be modifiable for allowing the addition of generated source directories.
     * This is determined from the {@link #compileSourceRoots} plugin configuration if non-empty,
     * or from {@code <source>} elements otherwise.
     *
     * @param outputDirectory the directory where to store the compilation results
     * @throws IOException if this method needs to walk through directories and that operation failed
     */
    final List<SourceDirectory> getSourceDirectories(final Path outputDirectory) throws IOException {
        if (isAbsent(compileSourceRoots)) {
            Stream<SourceRoot> roots = getSourceRoots(compileScope.projectScope());
            return SourceDirectory.fromProject(roots, getRelease(), outputDirectory);
        } else {
            return SourceDirectory.fromPluginConfiguration(
                    compileSourceRoots, moduleOfPreviousExecution(), getRelease(), outputDirectory);
        }
    }

    /**
     * {@return the path where to place generated source files created by annotation processing}
     */
    @Nullable
    protected abstract Path getGeneratedSourcesDirectory();

    /**
     * Returns the module which is being patched in a multi-release project, or {@code null} if none.
     * This is used when the {@link CompilerMojo#multiReleaseOutput} deprecated flag is {@code true}.
     * This module name is handled in a special way because, contrarily to the case where the project
     * uses the recommended {@code <sources>} elements (in which case all target releases are compiled
     * in a single Maven Compiler Plugin execution), the Maven Compiler Plugin does not know what have
     * been compiled for the other releases, because each target release is compiled with an execution
     * of {@link CompilerMojo} separated from other executions.
     *
     * @return the module name in a previous execution of the compiler plugin, or {@code null} if none
     * @throws IOException if this method needs to walk through directories and that operation failed
     *
     * @see CompilerMojo#addImplicitDependencies(ToolExecutor)
     *
     * @deprecated For compatibility with the previous way to build multi-release JAR file.
     *             May be removed after we drop support of the old way to do multi-release.
     */
    @Deprecated(since = "4.0.0")
    String moduleOfPreviousExecution() throws IOException {
        return null;
    }

    /**
     * {@return whether the sources contain at least one {@code module-info.java} file}
     * Note that the sources may contain more than one {@code module-info.java} file
     * if compiling a project with Module Source Hierarchy.
     *
     * <p>If the user explicitly specified a modular or classpath project, then the
     * {@code module-info.java} is assumed to exist or not without verification.</p>
     *
     * <p>The test compiler overrides this method for checking the existence of the
     * the {@code module-info.class} file in the main output directory instead.</p>
     *
     * @param roots root directories of the sources to compile
     * @throws IOException if this method needed to read a module descriptor and failed
     */
    boolean hasModuleDeclaration(final List<SourceDirectory> roots) throws IOException {
        return switch (project.getPackaging().type().id()) {
            case Type.CLASSPATH_JAR -> false;
            case Type.MODULAR_JAR -> true;
            default -> {
                for (SourceDirectory root : roots) {
                    if (root.getModuleInfo().isPresent()) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    /**
     * {@return the file where to dump the command-line when debug logging is enabled or when the compilation failed}
     * For example, if the value is {@code "javac"}, then the Java compiler can be launched
     * from the command-line by typing {@code javac @target/javac.args}.
     * The debug file will contain the compiler options together with the list of source files to compile.
     *
     * <p>Note: debug logging should not be confused with the {@link #debug} flag.</p>
     *
     * @see CompilerMojo#debugFileName
     * @see TestCompilerMojo#debugFileName
     */
    @Nullable
    protected abstract String getDebugFileName();

    /**
     * {@return the debug file name with its path, or null if none}
     * This method does not check if the debug file will be written, as the compilation result is not yet known.
     */
    final Path getDebugFilePath() {
        String filename = getDebugFileName();
        if (isAbsent(filename)) {
            return null;
        }
        // Do not use `this.getOutputDirectory()` because it may be deeper in `classes/META-INF/versions/`.
        return Path.of(project.getBuild().getOutputDirectory()).resolveSibling(filename);
    }

    /**
     * Returns whether the debug file should be written after a successful build.
     * By default, debug files are written only if the build failed.
     * However, some options can change this behavior.
     */
    final boolean shouldWriteDebugFile() {
        return verbose || logger.isDebugEnabled();
    }

    /**
     * Runs the Java compiler. This method performs the following steps:
     *
     * <ol>
     *   <li>Get a Java compiler by a call to {@link #compiler()}.</li>
     *   <li>Get the options to give to the compiler by a call to {@link #parseParameters(OptionChecker)}.</li>
     *   <li>Get an executor with {@link #createExecutor(DiagnosticListener)} with the default listener.</li>
     *   <li>{@linkplain ToolExecutor#applyIncrementalBuild Apply the incremental build} if enabled.</li>
     *   <li>{@linkplain ToolExecutor#compile Execute the compilation}.</li>
     *   <li>Shows messages in the {@linkplain #logger}.</li>
     * </ol>
     *
     * @throws MojoException if the compiler cannot be run
     */
    @Override
    public void execute() throws MojoException {
        JavaCompiler compiler = compiler();
        for (SourceVersion version : compiler.getSourceVersions()) {
            if (supportedVersion == null || version.compareTo(supportedVersion) >= 0) {
                supportedVersion = version;
            }
        }
        Options configuration = parseParameters(compiler);
        try {
            compile(compiler, configuration);
        } catch (RuntimeException e) {
            String message = e.getLocalizedMessage();
            if (message == null) {
                message = e.getClass().getSimpleName();
            } else if (e instanceof MojoException) {
                int s = message.indexOf(System.lineSeparator());
                if (s >= 0) {
                    message = message.substring(0, s); // Log only the first line.
                }
            }
            MessageBuilder mb = messageBuilderFactory
                    .builder()
                    .strong("COMPILATION ERROR: ")
                    .a(message);
            logger.error(mb.toString(), verbose ? e : null);
            if (tipForCommandLineCompilation != null) {
                logger.info(tipForCommandLineCompilation);
                tipForCommandLineCompilation = null;
            }
            if (failOnError) {
                throw e;
            }
        } catch (IOException e) {
            logger.error("I/O error while compiling the project.", e);
            throw new CompilationFailureException("I/O error while compiling the project.", e);
        }
    }

    /**
     * Creates a new task by taking a snapshot of the current configuration of this <abbr>MOJO</abbr>.
     * This method creates the {@linkplain ToolExecutor#outputDirectory output directory} if it does not already exist.
     *
     * <h4>Multi-threading</h4>
     * This method and the returned objects are not thread-safe.
     * However, this method takes a snapshot of the configuration of this <abbr>MOJO</abbr>.
     * Changes in this <abbr>MOJO</abbr> after this method call will not affect the returned executor.
     * Therefore, the executor can safely be executed in a background thread,
     * provided that the {@link #logger} is thread-safe.
     *
     * @param listener where to send compilation warnings, or {@code null} for the Maven logger
     * @return the task to execute for compiling the project using the configuration in this <abbr>MOJO</abbr>
     * @throws MojoException if this method identifies an invalid parameter in this <abbr>MOJO</abbr>
     * @throws IOException if an error occurred while creating the output directory or scanning the source directories
     * @throws MavenException if an error occurred while fetching dependencies
     */
    public ToolExecutor createExecutor(DiagnosticListener<? super JavaFileObject> listener) throws IOException {
        var executor = new ToolExecutor(this, listener);
        if (!(targetOrReleaseSet || executor.isReleaseSpecifiedForAll())) {
            MessageBuilder mb = messageBuilderFactory
                    .builder()
                    .a("No explicit value set for --release or --target. "
                            + "To ensure the same result in different environments, please add")
                    .newline()
                    .newline();
            writePlugin(mb, "release", String.valueOf(Runtime.version().feature()));
            logger.warn(mb.build());
        }
        return executor;
    }

    /**
     * {@return the compiler to use for compiling the code}
     * If {@link #fork} is {@code true}, the returned compiler will be a wrapper for a command line.
     * Otherwise, it will be the compiler identified by {@link #compilerId} if a value was supplied,
     * or the standard compiler provided with the Java platform otherwise.
     *
     * @throws MojoException if no compiler was found
     */
    public JavaCompiler compiler() throws MojoException {
        /*
         * Use the `compilerId` as identifier for toolchains.
         * I.e, we assume that `compilerId` is also the name of the executable binary.
         */
        getToolchain().ifPresent((tc) -> {
            logger.info("Toolchain in maven-compiler-plugin is \"" + tc + "\".");
            if (executable != null) {
                logger.warn(
                        "Toolchains are ignored because the 'executable' parameter is set to \"" + executable + "\".");
            } else {
                fork = true;
                if (compilerId == null) {
                    compilerId = DEFAULT_EXECUTABLE;
                }
                // TODO somehow shaky dependency between compilerId and tool executable.
                executable = tc.findTool(compilerId);
            }
        });
        if (fork) {
            if (executable == null) {
                executable = DEFAULT_EXECUTABLE;
            }
            return new ForkedCompiler(this);
        }
        /*
         * Search a `javax.tools.JavaCompiler` having a name matching the specified `compilerId`.
         * This is done before other code that can cause the mojo to return before the lookup is
         * done, possibly resulting in misconfigured POMs still building. If no `compilerId` was
         * specified, then the Java compiler bundled with the JDK is used (it may be absent).
         */
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Using " + (compilerId != null ? ("compiler \"" + compilerId + '"') : "system compiler") + '.');
        }
        if (compilerId == null) {
            compilerId = DEFAULT_EXECUTABLE;
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler != null) {
                return compiler;
            }
        } else {
            for (JavaCompiler t : ServiceLoader.load(JavaCompiler.class)) {
                if (compilerId.equals(t.name())) {
                    return t;
                }
            }
        }
        throw new CompilationFailureException("No such \"" + compilerId + "\" compiler.");
    }

    /**
     * Parses the parameters declared in the <abbr>MOJO</abbr>.
     * The {@link #release} parameter is excluded because it is handled in a special way
     * in order to support the compilation of multi-version projects.
     *
     * @param  compiler  the tools to use for verifying the validity of options
     * @return the options after validation
     */
    public Options parseParameters(final OptionChecker compiler) {
        /*
         * Options to provide to the compiler, excluding all kinds of path (source files, destination directory,
         * class-path, module-path, etc.). Some options are validated by Maven in addition of being validated by
         * the compiler. In those cases, the validation by the compiler is done before the validation by Maven.
         * For example, Maven will check for illegal values in the "-g" option only if the compiler rejected
         * the fully formatted option (e.g. "-g:vars,lines") that we provided to it.
         */
        final var configuration = new Options(compiler, logger);
        configuration.addIfNonBlank("--source", getSource());
        targetOrReleaseSet = configuration.addIfNonBlank("--target", getTarget());
        targetOrReleaseSet |= configuration.setRelease(getRelease());
        configuration.addIfTrue("--enable-preview", enablePreview);
        configuration.addComaSeparated("-proc", proc, List.of("none", "only", "full"), null);
        if (annotationProcessors != null) {
            var list = new StringJoiner(",");
            for (String p : annotationProcessors) {
                list.add(p);
            }
            configuration.addIfNonBlank("-processor", list.toString());
        }
        configuration.addComaSeparated("-implicit", implicit, List.of("none", "class"), null);
        configuration.addIfTrue("-parameters", parameters);
        configuration.addIfTrue("-Xpkginfo:always", createMissingPackageInfoClass);
        if (debug) {
            configuration.addComaSeparated(
                    "-g",
                    debuglevel,
                    List.of("lines", "vars", "source", "all", "none"),
                    (options) -> Arrays.asList(options).contains("all") ? new String[0] : options);
        } else {
            configuration.addIfTrue("-g:none", true);
        }
        configuration.addIfNonBlank("--module-version", moduleVersion);
        configuration.addIfTrue("-deprecation", showDeprecation);
        configuration.addIfTrue("-nowarn", !showWarnings);
        configuration.addIfTrue("-Werror", failOnWarning);
        configuration.addIfTrue("-verbose", verbose);
        if (fork) {
            configuration.addMemoryValue("-J-Xms", "meminitial", meminitial, SUPPORT_LEGACY);
            configuration.addMemoryValue("-J-Xmx", "maxmem", maxmem, SUPPORT_LEGACY);
        }
        return configuration;
    }

    /**
     * Runs the compiler, then shows the result in the Maven logger.
     *
     * @param compiler the compiler
     * @param configuration options to provide to the compiler
     * @throws IOException if an input file cannot be read
     * @throws MojoException if the compilation failed
     */
    @SuppressWarnings("UseSpecificCatch")
    private void compile(final JavaCompiler compiler, final Options configuration) throws IOException {
        final ToolExecutor executor = createExecutor(null);
        if (!executor.applyIncrementalBuild(this, configuration)) {
            return;
        }
        Throwable failureCause = null;
        final var compilerOutput = new StringWriter();
        boolean success;
        try {
            success = executor.compile(compiler, configuration, compilerOutput);
        } catch (Exception | NoClassDefFoundError e) {
            // `NoClassDefFoundError` may happen if a dependency of an annotation processor is missing.
            success = false;
            failureCause = e;
        }
        /*
         * The compilation errors or warnings should have already been reported by `DiagnosticLogger`.
         * However, the compiler may have other messages not associated to a particular source file.
         * For example, `ForkedCompiler` uses this writer if the compilation has been interrupted.
         */
        String additionalMessage = compilerOutput.toString();
        if (!additionalMessage.isBlank()) {
            if (success || failureCause != null) { // Keep the error level for the exception message.
                logger.warn(additionalMessage);
            } else {
                logger.error(additionalMessage);
            }
        }
        if (failureCause != null) {
            String message = failureCause.getMessage();
            if (message != null) {
                logger.error(message);
            } else {
                logger.error(failureCause);
            }
        }
        /*
         * In case of failure, or if debugging is enabled, dump the options to a file.
         * By default, the file will have the ".args" extension.
         */
        if (!success || shouldWriteDebugFile()) {
            IOException suppressed = null;
            try {
                writeDebugFile(executor, configuration, success);
                if (success && tipForCommandLineCompilation != null) {
                    logger.debug(tipForCommandLineCompilation);
                    tipForCommandLineCompilation = null;
                }
            } catch (IOException e) {
                suppressed = e;
            }
            if (!success) {
                var message = new StringBuilder(100)
                        .append("Cannot compile ")
                        .append(project.getId())
                        .append(' ')
                        .append(compileScope.projectScope().id())
                        .append(" classes.");
                if (executor.listener instanceof DiagnosticLogger diagnostic) {
                    diagnostic.firstError(failureCause).ifPresent((c) -> message.append(System.lineSeparator())
                            .append("The first error is: ")
                            .append(c));
                }
                var failure = new CompilationFailureException(message.toString(), failureCause);
                if (suppressed != null) {
                    failure.addSuppressed(suppressed);
                }
                throw failure;
            }
            if (suppressed != null) {
                throw suppressed;
            }
        }
        /*
         * Workaround for MCOMPILER-542, needed only if a modular project is compiled with a JDK older than Java 22.
         * Note: a previous version used as an heuristic way to detect if Reproducible Build was enabled. This check
         * has been removed because Reproducible Build are enabled by default in Maven now.
         */
        if (!isVersionEqualOrNewer(RELEASE_22)) {
            Path moduleDescriptor = executor.outputDirectory.resolve(MODULE_INFO + CLASS_FILE_SUFFIX);
            if (Files.isRegularFile(moduleDescriptor)) {
                byte[] oridinal = Files.readAllBytes(moduleDescriptor);
                byte[] modified = ByteCodeTransformer.patchJdkModuleVersion(oridinal, getRelease(), logger);
                if (modified != null) {
                    Files.write(moduleDescriptor, modified);
                }
            }
        }
    }

    /**
     * Returns whether the compiler supports the given source version or newer versions.
     * The specified source version shall be the name of one of the {@link SourceVersion} enumeration values.
     * Note that a return value of {@code true} does not mean that the compiler supports that exact version,
     * as it may supports only newer versions.
     */
    private boolean isVersionEqualOrNewer(String sourceVersion) {
        final SourceVersion requested;
        try {
            requested = SourceVersion.valueOf(sourceVersion);
        } catch (IllegalArgumentException e) {
            // The current tool is from a JDK older than the one for the requested source release.
            return false;
        }
        if (supportedVersion == null) {
            supportedVersion = SourceVersion.latestSupported();
        }
        return supportedVersion.compareTo(requested) >= 0;
    }

    /**
     * Returns whether the given string is null or empty, ignoring spaces.
     * This is a convenience for a frequent check, and also for clarity.
     */
    private static boolean isAbsent(String c) {
        return (c == null) || c.isBlank();
    }

    /**
     * Returns whether the given array is null or empty.
     * Defined as a complement of {@link #isAbsent(Collection)}.
     */
    private static boolean isAbsent(Object[] c) {
        return (c == null) || c.length == 0;
    }

    /**
     * Returns whether the given collection is null or empty.
     * This is a convenience for a frequent check, and also for clarity.
     */
    static boolean isAbsent(Collection<?> c) {
        return (c == null) || c.isEmpty();
    }

    /**
     * {@return the tool chain specified by the user in plugin parameters}
     */
    private Optional<Toolchain> getToolchain() {
        if (jdkToolchain != null) {
            List<Toolchain> tcs = toolchainManager.getToolchains(session, "jdk", jdkToolchain);
            if (!isAbsent(tcs)) {
                return Optional.of(tcs.get(0));
            }
        }
        return toolchainManager.getToolchainFromBuildContext(session, "jdk");
    }

    /**
     * Returns the module name as declared in the given {@code module-info.java} source file.
     * This approach is less reliable than reading the compiled {@code module-info.class} file,
     * but is sometime needed when the compiled file is not yet available.
     *
     * @param source the source file to parse (may be null or not exist)
     * @return the module name, or {@code null} if not found
     */
    final String parseModuleInfoName(Path source) throws IOException {
        if (source != null && Files.exists(source)) {
            Charset charset = charset();
            try (BufferedReader in =
                    (charset != null) ? Files.newBufferedReader(source, charset) : Files.newBufferedReader(source)) {
                var tokenizer = new StreamTokenizer(in);
                tokenizer.slashSlashComments(true);
                tokenizer.slashStarComments(true);
                int t;
                while ((t = tokenizer.nextToken()) != StreamTokenizer.TT_EOF) {
                    if (t == StreamTokenizer.TT_WORD && "module".equals(tokenizer.sval)) {
                        do {
                            t = tokenizer.nextToken();
                        } while (t == StreamTokenizer.TT_EOL);
                        if (t == StreamTokenizer.TT_WORD) {
                            return tokenizer.sval;
                        }
                        break; // Found a "module" keyword followed by something that we didn't recognized.
                    }
                }
            }
        }
        return null;
    }

    /**
     * {@return all dependencies grouped by the path types where to place them}
     * If the module-path contains any filename-based dependency and this <abbr>MOJO</abbr>
     * is compiling the main code, then a warning will be logged.
     *
     * @param hasModuleDeclaration whether to allow placement of dependencies on the module-path.
     * @throws IOException if an I/O error occurred while fetching dependencies
     * @throws MavenException if an error occurred while fetching dependencies for a reason other than I/O.
     */
    final DependencyResolverResult resolveDependencies(boolean hasModuleDeclaration) throws IOException {
        DependencyResolver resolver = session.getService(DependencyResolver.class);
        if (resolver == null) { // Null value happen during tests, depending on the mock used.
            return null;
        }
        var allowedTypes = EnumSet.of(JavaPathType.CLASSES, JavaPathType.PROCESSOR_CLASSES);
        if (hasModuleDeclaration) {
            allowedTypes.add(JavaPathType.MODULES);
            allowedTypes.add(JavaPathType.PROCESSOR_MODULES);
        }
        DependencyResolverResult dependencies = resolver.resolve(DependencyResolverRequest.builder()
                .session(session)
                .project(project)
                .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                .pathScope(compileScope)
                .pathTypeFilter(allowedTypes)
                .build());
        /*
         * Report errors or warnings. If possible, we rethrow the first exception directly without
         * wrapping in a `MojoException` for making the stack-trace a little bit easier to analyze.
         */
        Exception exception = null;
        for (Exception cause : dependencies.getExceptions()) {
            if (exception != null) {
                exception.addSuppressed(cause);
            } else if (cause instanceof UncheckedIOException e) {
                exception = e.getCause();
            } else if (cause instanceof RuntimeException || cause instanceof IOException) {
                exception = cause;
            } else {
                exception = new CompilationFailureException("Cannot collect the compile-time dependencies.", cause);
            }
        }
        if (exception != null) {
            if (exception instanceof IOException e) {
                throw e;
            } else {
                throw (RuntimeException) exception; // A ClassCastException here would be a bug in above loop.
            }
        }
        if (ProjectScope.MAIN.equals(compileScope.projectScope())) {
            String warning = dependencies.warningForFilenameBasedAutomodules().orElse(null);
            if (warning != null) { // Do not use Optional.ifPresent(…) for avoiding confusing source class name.
                logger.warn(warning);
            }
        }
        return dependencies;
    }

    /**
     * Adds paths to the annotation processor dependencies. Paths are added to the list associated
     * to the {@link JavaPathType#PROCESSOR_CLASSES} entry of given map, which should be modifiable.
     *
     * @param addTo the modifiable map and lists where to append more paths to annotation processor dependencies
     * @throws MojoException if an error occurred while resolving the dependencies
     *
     * @deprecated Replaced by ordinary dependencies with {@code <type>} element set to
     * {@code processor}, {@code classpath-processor} or {@code modular-processor}.
     */
    @Deprecated(since = "4.0.0")
    @SuppressWarnings("UseSpecificCatch")
    final void resolveProcessorPathEntries(Map<PathType, List<Path>> addTo) throws MojoException {
        List<DependencyCoordinate> dependencies = annotationProcessorPaths;
        if (!isAbsent(dependencies)) {
            try {
                List<org.apache.maven.api.DependencyCoordinates> coords = dependencies.stream()
                        .map((coord) -> coord.toCoordinate(project, session))
                        .toList();
                Session sessionWithRepo =
                        session.withRemoteRepositories(projectManager.getRemoteProjectRepositories(project));
                addTo.merge(
                        JavaPathType.PROCESSOR_CLASSES,
                        sessionWithRepo
                                .getService(DependencyResolver.class)
                                .resolve(DependencyResolverRequest.builder()
                                        .session(sessionWithRepo)
                                        .dependencies(coords)
                                        .managedDependencies(project.getManagedDependencies())
                                        .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                                        .pathScope(PathScope.MAIN_RUNTIME)
                                        .build())
                                .getPaths(),
                        (oldPaths, newPaths) -> {
                            oldPaths.addAll(newPaths);
                            return oldPaths;
                        });
            } catch (MojoException e) {
                throw e;
            } catch (Exception e) {
                throw new CompilationFailureException(
                        "Resolution of annotationProcessorPath dependencies failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * {@return whether an annotation processor seems to be present}
     *
     * @param dependencyTypes the type of dependencies, for checking if any of them is a processor path
     *
     * @see #incrementalCompilation
     */
    private boolean hasAnnotationProcessor(final Set<PathType> dependencyTypes) {
        if (isAbsent(proc)) {
            /*
             * If the `proc` parameter was not specified, its default value depends on the Java version.
             * It was "full" prior Java 23 and become "none if no other processor option" since Java 23.
             */
            if (isVersionEqualOrNewer(RELEASE_23)) {
                if (isAbsent(annotationProcessors) && isAbsent(annotationProcessorPaths)) {
                    return dependencyTypes.contains(JavaPathType.PROCESSOR_CLASSES)
                            || dependencyTypes.contains(JavaPathType.PROCESSOR_MODULES);
                }
            }
        } else if (proc.equalsIgnoreCase("none")) {
            return false;
        }
        return true;
    }

    /**
     * Ensures that the directory for generated sources exists, and adds it to the list of source directories
     * known to the project manager. This is used for adding the output of annotation processor.
     * The returned set is either empty or a singleton.
     *
     * @param dependencyTypes the type of dependencies, for checking if any of them is a processor path
     * @return the added directory in a singleton set, or an empty set if none
     * @throws IOException if the directory cannot be created
     */
    final Set<Path> addGeneratedSourceDirectory(final Set<PathType> dependencyTypes) throws IOException {
        Path generatedSourcesDirectory = getGeneratedSourcesDirectory();
        if (generatedSourcesDirectory == null) {
            return Set.of();
        }
        /*
         * Do not create an empty directory if this plugin is not going to generate new source files.
         * However, if a directory already exists, use it because maybe its content was generated by
         * another plugin executed before the compiler plugin.
         */
        if (hasAnnotationProcessor(dependencyTypes)) {
            // `createDirectories(Path)` does nothing if the directory already exists.
            generatedSourcesDirectory = Files.createDirectories(generatedSourcesDirectory);
        } else if (Files.notExists(generatedSourcesDirectory)) {
            return Set.of();
        }
        ProjectScope scope = compileScope.projectScope();
        projectManager.addSourceRoot(project, scope, Language.JAVA_FAMILY, generatedSourcesDirectory.toAbsolutePath());
        if (logger.isDebugEnabled()) {
            var sb = new StringBuilder("Adding \"")
                    .append(generatedSourcesDirectory)
                    .append("\" to ")
                    .append(scope.id())
                    .append("-compile source roots. New roots are:");
            projectManager
                    .getEnabledSourceRoots(project, scope, Language.JAVA_FAMILY)
                    .forEach((p) -> {
                        sb.append(System.lineSeparator()).append("    ").append(p.directory());
                    });
            logger.debug(sb.toString());
        }
        return Set.of(generatedSourcesDirectory);
    }

    /**
     * Formats the {@code <plugin>} block of code for configuring this plugin with the given option.
     *
     * @param mb the message builder where to format the block of code
     * @param option name of the XML sub-element of {@code <configuration>} for the option
     * @param value the option value, or {@code null} if none
     */
    private void writePlugin(MessageBuilder mb, String option, String value) {
        if (mavenCompilerPluginVersion == null) {
            try (InputStream is = AbstractCompilerMojo.class.getResourceAsStream("/" + JarFile.MANIFEST_NAME)) {
                if (is != null) {
                    mavenCompilerPluginVersion =
                            new Manifest(is).getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                }
            } catch (IOException e) {
                // noop
            }
            if (mavenCompilerPluginVersion == null) {
                mavenCompilerPluginVersion = "";
            }
        }
        mb.a("    <plugin>").newline();
        mb.a("      <groupId>org.apache.maven.plugins</groupId>").newline();
        mb.a("      <artifactId>maven-compiler-plugin</artifactId>").newline();
        if (!isAbsent(mavenCompilerPluginVersion)) {
            mb.a("      <version>")
                    .a(mavenCompilerPluginVersion)
                    .a("</version>")
                    .newline();
        }
        mb.a("      <configuration>").newline();
        mb.a("        <").a(option).a('>').a(value).a("</").a(option).a('>').newline();
        mb.a("      </configuration>").newline();
        mb.a("    </plugin>").newline();
    }

    /**
     * Dumps the compiler options together with the list of source files into a debug file.
     * This is invoked in case of compilation failure, or if debug is enabled.
     *
     * <h4>Syntax</h4>
     * The arguments within a file can be separated by spaces or new line characters.
     * If a file name contains embedded spaces, then the whole file name must be between double quotation marks.
     * The -J options are not supported.
     *
     * @param executor the executor that compiled the classes
     * @param configuration options provided to the compiler
     * @param showBaseVersion whether the tip shown to user suggests the base Java release instead of the last one
     * @throws IOException if an error occurred while writing the debug file
     */
    private void writeDebugFile(final ToolExecutor executor, final Options configuration, final boolean showBaseVersion)
            throws IOException {
        final Path debugFilePath = getDebugFilePath();
        if (debugFilePath == null) {
            logger.warn("The <debugFileName> parameter should not be empty.");
            return;
        }
        final var commandLine = new StringBuilder("For trying to compile from the command-line, use:");
        Path dir = basedir;
        if (dir != null) { // Should never be null, but it has been observed with some Maven versions.
            try {
                dir = Path.of(System.getProperty("user.dir")).relativize(dir);
            } catch (IllegalArgumentException e) {
                // Ignore, keep the absolute path.
            }
            String chdir = dir.toString();
            if (!chdir.isEmpty()) {
                boolean isWindows = (File.separatorChar == '\\');
                commandLine
                        .append(System.lineSeparator())
                        .append("    ")
                        .append(isWindows ? "chdir " : "cd ")
                        .append(chdir);
            }
        }
        commandLine.append(System.lineSeparator()).append("    ").append(executable != null ? executable : compilerId);
        Path pathForRelease = debugFilePath;
        /*
         * The following loop will iterate over all groups of source files compiled for the same Java release,
         * starting with the base release. If the project is not a multi-release project, it iterates only once.
         * If the compilation failed, the loop will stop after the first Java release for which an error occurred.
         */
        final int count = executor.sourcesForDebugFile.size();
        final int indexToShow = showBaseVersion ? 0 : count - 1;
        for (int i = 0; i < count; i++) {
            final SourcesForRelease sources = executor.sourcesForDebugFile.get(i);
            if (i != 0) {
                String version = sources.outputForRelease.getFileName().toString();
                String filename = debugFilePath.getFileName().toString();
                int s = filename.lastIndexOf('.');
                if (s >= 0) {
                    filename = filename.substring(0, s) + '-' + version + filename.substring(s);
                } else {
                    filename = filename + '-' + version;
                }
                pathForRelease = debugFilePath.resolveSibling(filename);
            }
            /*
             * Write the `javac.args` or `javac-<version>.args` file where `<version>` is the targeted Java release.
             * The `-J` options need to be on the command line rather than in the file, and therefore can be written
             * only once.
             */
            try (BufferedWriter out = Files.newBufferedWriter(pathForRelease)) {
                configuration.setRelease(sources.getReleaseString());
                configuration.format((i == indexToShow) ? commandLine : null, out);
                for (Map.Entry<PathType, List<Path>> entry : sources.dependencySnapshot.entrySet()) {
                    writeOption(out, entry.getKey(), entry.getValue());
                }
                for (Map.Entry<String, Set<Path>> root : sources.roots.entrySet()) {
                    String moduleName = root.getKey();
                    writeOption(out, SourcePathType.valueOf(moduleName), root.getValue());
                }
                out.write("-d \"");
                out.write(relativize(sources.outputForRelease).toString());
                out.write('"');
                out.newLine();
                for (final Path file : sources.files) {
                    out.write('"');
                    out.write(relativize(file).toString());
                    out.write('"');
                    out.newLine();
                }
            }
        }
        Path path = relativize(showBaseVersion ? debugFilePath : pathForRelease);
        tipForCommandLineCompilation = commandLine.append(" @").append(path).toString();
    }

    /**
     * Writes the paths for the given Java compiler option.
     *
     * @param out where to write
     * @param type the type of path to write as a compiler option
     * @param files the paths associated to the specified option
     * @throws IOException in an error occurred while writing to the output
     */
    private void writeOption(BufferedWriter out, PathType type, Collection<Path> files) throws IOException {
        if (!files.isEmpty()) {
            files = files.stream().map(this::relativize).toList();
            String separator = "";
            for (String element : type.option(files)) {
                out.write(separator);
                out.write(element);
                separator = " ";
            }
            out.newLine();
        }
    }

    /**
     * Makes the given file relative to the base directory if the path is inside the project directory tree.
     * The check for the project directory tree (starting from the root of all sub-projects) is for avoiding
     * to relativize the paths to JAR files in the Maven local repository for example.
     *
     * @param  file  the path to make relative to the base directory
     * @return the given path, potentially relative to the base directory
     */
    private Path relativize(Path file) {
        final Path dir = basedir;
        if (dir != null) { // Should never be null, but it has been observed with some Maven versions.
            Path root = project.getRootDirectory();
            if (root != null && file.startsWith(root)) {
                try {
                    file = dir.relativize(file);
                } catch (IllegalArgumentException e) {
                    // Ignore, keep the absolute path.
                }
            }
        }
        return file;
    }
}
