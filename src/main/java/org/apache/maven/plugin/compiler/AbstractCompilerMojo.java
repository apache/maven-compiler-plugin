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
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.OptionChecker;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.Tool;
import javax.tools.ToolProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamTokenizer;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.StringJoiner;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.PathType;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
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
     * The executable to use by default if nine is specified.
     */
    private static final String DEFAULT_EXECUTABLE = "javac";

    /**
     * The locale for diagnostics, or {@code null} for the platform default.
     *
     * @see #encoding
     */
    private static final Locale LOCALE = null;

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
    @Parameter(property = "moduleVersion", defaultValue = "${project.version}")
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
     * {@return the character set used for decoding bytes, or null for the platform default}.
     * No warning is emitted in the latter case because as of Java 18, the default is UTF-8,
     * i.e. the encoding is no longer platform-dependent.
     */
    private Charset charset() {
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
     * The {@code --release} argument for the Java compiler.
     * If omitted, then the compiler will generate bytecodes for the Java version running the compiler.
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-release">javac --release</a>
     * @since 3.6
     */
    @Parameter(property = "maven.compiler.release")
    protected String release;

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
     * Whether annotation processing is performed or not.
     * If not set, both compilation and annotation processing are performed at the same time.
     * If set, the value will be appended to the {@code -proc:} compiler option.
     * Standard values are:
     * <ul>
     *   <li>{@code none} – no annotation processing is performed.</li>
     *   <li>{@code only} – only annotation processing is done, no compilation.</li>
     *   <li>{@code full} – annotation processing and compilation are done.</li>
     * </ul>
     *
     * Prior Java 21, {@code full} was the default.
     * Starting with JDK 21, this option must be set explicitly.
     *
     * @see #annotationProcessors
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
     * processors only in those classpath elements. If omitted, the default classpath is used to detect annotation
     * processors. The detection itself depends on the configuration of {@link #annotationProcessors}.
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
     * @deprecated Replaced by ordinary dependencies with {@code <type>} element
     * set to {@code proc}, {@code classpath-proc} or {@code modular-proc}.
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
     */
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
     */
    @Parameter(property = "maven.compiler.debug", defaultValue = "true")
    protected boolean debug = true;

    /**
     * Keyword list to be appended to the {@code -g} command-line switch.
     * Legal values are a comma-separated list of the following keywords:
     * {@code lines}, {@code vars}, {@code source} and {@code all}.
     * If debug level is not specified, then the {@code -g} option will <em>not</em> by added,
     * which means that the default debugging information will be generated
     * (typically {@code lines} and {@code source} but not {@code vars}).
     * If {@link #debug} is turned off, this attribute will be ignored.
     *
     * @see #debug
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-g-custom">javac -G:[lines,vars,source]</a>
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
     *
     * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#option-verbose">javac -verbose</a>
     */
    @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
    protected boolean verbose;

    /**
     * Whether to provide more details about why a module is rebuilt.
     * This is used only if {@link #incrementalCompilation} is {@code "inputTreeChanges"}.
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
     * Values can be {@code dependencies}, {@code sources}, {@code classes}, {@code additions},
     * {@code modules} or {@code none}.
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
     * <p><b>{@code additions}:</b>
     * recompile all source files when the addition of a new file is detected.
     * This aspect should be used together with {@code sources} or {@code classes}.
     * When used with {@code classes}, it provides a way to detect class renaming
     * (this is not needed with {@code sources}).</p>
     *
     * <p><b>{@code modules}:</b>
     * recompile modules and let the compiler decides which individual files to recompile.
     * The compiler plugin does not enumerate the source files to recompile (actually, it does not scan at all the
     * source directories). Instead, it only specifies the module to recompile using the {@code --module} option.
     * The Java compiler will scan the source directories itself and compile only those source files that are newer
     * than the corresponding files in the output directory.</p>
     *
     * <p><b>{@code none}:</b>
     * the compiler plugin unconditionally specifies all sources to the Java compiler.
     * This option is mutually exclusive with all other incremental compilation options.</p>
     *
     * <h4>Limitations</h4>
     * In all cases, the current compiler-plugin does not detect structural changes other than file addition or removal.
     * For example, the plugin does not detect whether a method has been removed in a class.
     *
     * @see #staleMillis
     * @see #fileExtensions
     * @see #showCompilationChanges
     * @see #createMissingPackageInfoClass
     * @since 4.0.0
     */
    @Parameter(defaultValue = "options,dependencies,sources")
    protected String incrementalCompilation;

    /**
     * Whether to enable/disable incremental compilation feature.
     *
     * @since 3.1
     *
     * @deprecated Replaced by {@link #incrementalCompilation}.
     * A value of {@code true} in this old property is equivalent to {@code "dependencies,sources,additions"}
     * in the new property, and a value of {@code false} is equivalent to {@code "classes"}.
     */
    @Deprecated(since = "4.0.0")
    @Parameter(property = "maven.compiler.useIncrementalCompilation")
    protected Boolean useIncrementalCompilation;

    /**
     * File extensions to check timestamp for incremental build.
     * Default contains only {@code class} and {@code jar}.
     *
     * TODO: Rename with a name making clearer that this parameter is about incremental build.
     *
     * @see #incrementalCompilation
     * @since 3.1
     */
    @Parameter
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
     * {@code true} if this MOJO is for compiling tests, or {@code false} if compiling the main code.
     */
    final boolean isTestCompile;

    /**
     * Creates a new MOJO.
     *
     * @param isTestCompile {@code true} for compiling tests, or {@code false} for compiling the main code
     */
    protected AbstractCompilerMojo(boolean isTestCompile) {
        this.isTestCompile = isTestCompile;
    }

    /**
     * {@return the root directories of Java source files to compile}. If the sources are organized according the
     * <i>Module Source Hierarchy</i>, then the list shall enumerate the root source directory for each module.
     */
    @Nonnull
    protected abstract List<Path> getCompileSourceRoots();

    /**
     * {@return the inclusion filters for the compiler, or an empty list for all Java source files}.
     * The filter patterns are described in {@link java.nio.file.FileSystem#getPathMatcher(String)}.
     * If no syntax is specified, the default syntax is "glob".
     */
    protected abstract Set<String> getIncludes();

    /**
     * {@return the exclusion filters for the compiler, or an empty list if none}.
     * The filter patterns are described in {@link java.nio.file.FileSystem#getPathMatcher(String)}.
     * If no syntax is specified, the default syntax is "glob".
     */
    protected abstract Set<String> getExcludes();

    /**
     * {@return the exclusion filters for the incremental calculation}.
     * Updated source files, if excluded by this filter, will not cause the project to be rebuilt.
     *
     * @see SourceFile#ignoreModification
     */
    protected abstract Set<String> getIncrementalExcludes();

    /**
     * {@return the destination directory (or class output directory) for class files}.
     * This directory will be given to the {@code -d} Java compiler option.
     */
    @Nonnull
    protected abstract Path getOutputDirectory();

    /**
     * {@return the {@code --source} argument for the Java compiler}.
     * The default implementation returns the {@link #source} value.
     */
    @Nullable
    protected String getSource() {
        return source;
    }

    /**
     * {@return the {@code --target} argument for the Java compiler}.
     * The default implementation returns the {@link #target} value.
     */
    @Nullable
    protected String getTarget() {
        return target;
    }

    /**
     * {@return the {@code --release} argument for the Java compiler}.
     * The default implementation returns the {@link #release} value.
     */
    @Nullable
    protected String getRelease() {
        return release;
    }

    /**
     * {@return the path where to place generated source files created by annotation processing}.
     */
    @Nullable
    protected abstract Path getGeneratedSourcesDirectory();

    /**
     * {@return whether the sources contain at least one {@code module-info.java} file}.
     * Note that the sources may contain more than one {@code module-info.java} file
     * if compiling a project with Module Source Hierarchy.
     *
     * <p>The test compiler overrides this method for checking the existence of the
     * the {@code module-info.class} file in the main output directory instead.</p>
     *
     * @param roots root directories of the sources to compile
     * @throws IOException if this method needed to read a module descriptor and failed
     */
    boolean hasModuleDeclaration(final List<SourceDirectory> roots) throws IOException {
        for (SourceDirectory root : roots) {
            if (root.getModuleInfo().isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds dependencies others than the ones declared in POM file.
     * The typical case is the compilation of tests, which depends on the main compilation outputs.
     * The default implementation does nothing.
     *
     * @param addTo where to add dependencies
     * @param hasModuleDeclaration whether the main sources have or should have a {@code module-info} file
     * @throws IOException if this method needs to walk through directories and that operation failed
     */
    protected void addImplicitDependencies(Map<PathType, List<Path>> addTo, boolean hasModuleDeclaration)
            throws IOException {
        // Nothing to add in a standard build of main classes.
    }

    /**
     * Adds options for declaring the source directories. The way to declare those directories depends on whether
     * we are compiling the main classes (in which case the {@code --source-path} or {@code --module-source-path}
     * options may be used) or the test classes (in which case the {@code --patch-module} option may be used).
     *
     * @param addTo the collection of source paths to augment
     * @param compileSourceRoots the source paths to eventually adds to the {@code toAdd} map
     * @throws IOException if this method needs to read a module descriptor and this operation failed
     */
    void addSourceDirectories(Map<PathType, List<Path>> addTo, List<SourceDirectory> compileSourceRoots)
            throws IOException {
        // No need to specify --source-path at this time, as it is for additional sources.
    }

    /**
     * Generates options for handling the given dependencies.
     * This method should do nothing when compiling the main classes, because the {@code module-info.java} file
     * should contain all the required configuration. However, this method may need to add some {@code -add-reads}
     * options when compiling the test classes.
     *
     * @param dependencies the project dependencies
     * @param addTo where to add the options
     * @throws IOException if the module information of a dependency cannot be read
     */
    protected void addModuleOptions(DependencyResolverResult dependencies, Options addTo) throws IOException {}

    /**
     * {@return the file where to dump the command-line when debug logging is enabled or when the compilation failed}.
     * For example, if the value is {@code "javac"}, then the Java compiler can be launched
     * from the command-line by typing {@code javac @target/javac.args}.
     * The debug file will contain the compiler options together with the list of source files to compile.
     *
     * <p>Note: debug logging should not be confused with the {@link #debug} flag.</p>
     */
    @Nullable
    protected abstract String getDebugFileName();

    /**
     * {@return the debug file name with its path, or null if none}.
     */
    final Path getDebugFilePath() {
        String filename = getDebugFileName();
        if (filename == null || filename.isBlank()) {
            return null;
        }
        // Do not use `this.getOutputDirectory()` because it may be deeper in `classes/META-INF/versions/`.
        return Path.of(project.getBuild().getOutputDirectory()).resolveSibling(filename);
    }

    /**
     * Runs the Java compiler.
     *
     * @throws MojoException if the compiler cannot be run
     */
    @Override
    public void execute() throws MojoException {
        JavaCompiler compiler = compiler();
        Options compilerConfiguration = acceptParameters(compiler);
        try {
            compile(compiler, compilerConfiguration);
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
            // Do not log stack trace for `CompilationFailureException` because they are not unexpected.
            logger.error(mb.toString(), e instanceof CompilationFailureException ? null : e);
            if (tipForCommandLineCompilation != null) {
                logger.info(tipForCommandLineCompilation);
                tipForCommandLineCompilation = null;
            }
            throw e;
        } catch (IOException e) {
            logger.error("I/O error while compiling the project.", e);
            throw new CompilationFailureException("I/O error while compiling the project.", e);
        }
    }

    /**
     * {@return the compiler to use for compiling the code}.
     * If {@link #fork} is {@code true}, the returned compiler will be a wrapper for the command line.
     * Otherwise it will be the compiler identified by {@link #compilerId} if a value was supplied,
     * or the standard compiler provided with the Java platform otherwise.
     *
     * @throws MojoException if no compiler was found
     */
    private JavaCompiler compiler() throws MojoException {
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
     * Parses the parameters declared in the MOJO.
     *
     * @param  compiler  the tools to use for verifying the validity of options
     * @return the options after validation
     */
    protected Options acceptParameters(final OptionChecker compiler) {
        /*
         * Options to provide to the compiler, excluding all kinds of path (source files, destination directory,
         * class-path, module-path, etc.). Some options are validated by Maven in addition of being validated by
         * the compiler. In those cases, the validation by the compiler is done before the validation by Maven.
         * For example, Maven will check for illegal values in the "-g" option only if the compiler rejected
         * the fully formatted option (e.g. "-g:vars,lines") that we provided to it.
         */
        boolean targetOrReleaseSet;
        final var compilerConfiguration = new Options(compiler, logger);
        compilerConfiguration.addIfNonBlank("--source", getSource());
        targetOrReleaseSet = compilerConfiguration.addIfNonBlank("--target", getTarget());
        targetOrReleaseSet |= compilerConfiguration.addIfNonBlank("--release", getRelease());
        if (!targetOrReleaseSet && !isTestCompile) {
            MessageBuilder mb = messageBuilderFactory
                    .builder()
                    .a("No explicit value set for --release or --target. "
                            + "To ensure the same result in different environments, please add")
                    .newline()
                    .newline();
            writePlugin(mb, "release", String.valueOf(Runtime.version().feature()));
            logger.warn(mb.build());
        }
        compilerConfiguration.addIfTrue("--enable-preview", enablePreview);
        compilerConfiguration.addComaSeparated("-proc", proc, List.of("none", "only", "full"), null);
        if (annotationProcessors != null) {
            var list = new StringJoiner(",");
            for (String p : annotationProcessors) {
                list.add(p);
            }
            compilerConfiguration.addIfNonBlank("-processor", list.toString());
        }
        compilerConfiguration.addComaSeparated("-implicit", implicit, List.of("none", "class"), null);
        compilerConfiguration.addIfTrue("-parameters", parameters);
        compilerConfiguration.addIfTrue("-Xpkginfo:always", createMissingPackageInfoClass);
        if (debug) {
            compilerConfiguration.addComaSeparated(
                    "-g",
                    debuglevel,
                    List.of("lines", "vars", "source", "all", "none"),
                    (options) -> Arrays.asList(options).contains("all") ? new String[0] : options);
        } else {
            compilerConfiguration.addIfTrue("-g:none", true);
        }
        compilerConfiguration.addIfNonBlank("--module-version", moduleVersion);
        compilerConfiguration.addIfTrue("-deprecation", showDeprecation);
        compilerConfiguration.addIfTrue("-nowarn", !showWarnings);
        compilerConfiguration.addIfTrue("-Werror", failOnWarning);
        compilerConfiguration.addIfTrue("-verbose", verbose);
        if (fork) {
            compilerConfiguration.addMemoryValue("-J-Xms", "meminitial", meminitial, SUPPORT_LEGACY);
            compilerConfiguration.addMemoryValue("-J-Xmx", "maxmem", maxmem, SUPPORT_LEGACY);
        }
        return compilerConfiguration;
    }

    /**
     * Subdivides a compilation unit into one or more compilation tasks. A compilation unit may, for example,
     * compile the source files for a specific Java release in a multi-release project. Normally, such unit maps
     * to exactly one compilation task. However, it is sometime useful to split a compilation unit into smaller tasks,
     * usually as a workaround for deprecated practices such as overwriting the main {@code module-info} in the tests.
     * In the latter case, we need to compile the test {@code module-info} separately, before the other test classes.
     */
    CompilationTaskSources[] toCompilationTasks(final SourcesForRelease unit) {
        return new CompilationTaskSources[] {new CompilationTaskSources(unit.files)};
    }

    /**
     * Runs the compiler.
     *
     * @param compiler the compiler
     * @param compilerConfiguration options to provide to the compiler
     * @throws IOException if an input file cannot be read
     * @throws MojoException if the compilation failed
     */
    @SuppressWarnings({"checkstyle:MethodLength", "checkstyle:AvoidNestedBlocks"})
    private void compile(final JavaCompiler compiler, final Options compilerConfiguration) throws IOException {
        final EnumSet<IncrementalBuild.Aspect> incAspects;
        if (useIncrementalCompilation != null) {
            incAspects = useIncrementalCompilation
                    ? EnumSet.of(
                            IncrementalBuild.Aspect.SOURCES,
                            IncrementalBuild.Aspect.ADDITIONS,
                            IncrementalBuild.Aspect.DEPENDENCIES)
                    : EnumSet.of(IncrementalBuild.Aspect.CLASSES);
        } else {
            incAspects = IncrementalBuild.Aspect.parse(incrementalCompilation);
        }
        /*
         * Get the root directories of the Java source files to compile, excluding empty directories.
         * The list needs to be modifiable for allowing the addition of generated source directories.
         * Then get the list of all source files to compile.
         *
         * Note that we perform this step after processing compiler arguments, because this block may
         * skip the build if there is no source code to compile. We want arguments to be verified first
         * in order to warn about possible configuration problems.
         */
        List<SourceFile> sourceFiles = List.of();
        final Path outputDirectory = Files.createDirectories(getOutputDirectory());
        final List<SourceDirectory> compileSourceRoots =
                SourceDirectory.fromPaths(getCompileSourceRoots(), outputDirectory);
        final boolean hasModuleDeclaration;
        if (incAspects.contains(IncrementalBuild.Aspect.MODULES)) {
            for (SourceDirectory root : compileSourceRoots) {
                if (root.moduleName == null) {
                    throw new CompilationFailureException("The <incrementalCompilation> value can be \"modules\" "
                            + "only if all source directories are Java modules.");
                }
            }
            if (!(getIncludes().isEmpty()
                    && getExcludes().isEmpty()
                    && getIncrementalExcludes().isEmpty())) {
                throw new CompilationFailureException("Include and exclude filters cannot be specified "
                        + "when <incrementalCompilation> is set to \"modules\".");
            }
            hasModuleDeclaration = true;
        } else {
            var filter = new PathFilter(getIncludes(), getExcludes(), getIncrementalExcludes());
            sourceFiles = filter.walkSourceFiles(compileSourceRoots);
            if (sourceFiles.isEmpty()) {
                String message = "No sources to compile.";
                try {
                    Files.delete(outputDirectory);
                } catch (DirectoryNotEmptyException e) {
                    message += " However, the output directory is not empty.";
                }
                logger.info(message);
                return;
            }
            switch (project.getPackaging().type().id()) {
                case Type.CLASSPATH_JAR:
                    hasModuleDeclaration = false;
                    break;
                case Type.MODULAR_JAR:
                    hasModuleDeclaration = true;
                    break;
                default:
                    hasModuleDeclaration = hasModuleDeclaration(compileSourceRoots);
                    break;
            }
        }
        final Set<Path> generatedSourceDirectories = addGeneratedSourceDirectory(getGeneratedSourcesDirectory());
        /*
         * Get the dependencies. If the module-path contains any file-based dependency
         * and this MOJO is compiling the main code, then a warning will be logged.
         *
         * NOTE: this method assumes that the map and the list values are modifiable.
         * This is true with org.apache.maven.internal.impl.DefaultDependencyResolverResult,
         * but may not be true in the general case. To be safe, we should perform a deep copy.
         * But it would be unnecessary copies in most cases.
         */
        final Map<PathType, List<Path>> dependencies = resolveDependencies(compilerConfiguration, hasModuleDeclaration);
        resolveProcessorPathEntries(dependencies);
        addImplicitDependencies(dependencies, hasModuleDeclaration);
        /*
         * Verify if a dependency changed since the build started, or if a source file changed since the last build.
         * If there is no change, we can skip the build. If a dependency or the source tree has changed, we may
         * conservatively clean before rebuild.
         */
        { // For reducing the scope of the Boolean flags.
            final boolean checkSources = incAspects.contains(IncrementalBuild.Aspect.SOURCES);
            final boolean checkClasses = incAspects.contains(IncrementalBuild.Aspect.CLASSES);
            final boolean checkDepends = incAspects.contains(IncrementalBuild.Aspect.DEPENDENCIES);
            final boolean checkOptions = incAspects.contains(IncrementalBuild.Aspect.OPTIONS);
            final boolean rebuildOnAdd = incAspects.contains(IncrementalBuild.Aspect.ADDITIONS);
            if (checkSources | checkClasses | checkDepends | checkOptions) {
                final var incrementalBuild = new IncrementalBuild(this, sourceFiles);
                String causeOfRebuild = null;
                if (checkSources) {
                    // Should be first, because this method deletes output files of removed sources.
                    causeOfRebuild = incrementalBuild.inputFileTreeChanges(staleMillis, rebuildOnAdd);
                }
                if (checkClasses && causeOfRebuild == null) {
                    causeOfRebuild = incrementalBuild.markNewOrModifiedSources(staleMillis, rebuildOnAdd);
                }
                if (checkDepends && causeOfRebuild == null) {
                    if (fileExtensions == null || fileExtensions.isEmpty()) {
                        fileExtensions = List.of("class", "jar");
                    }
                    causeOfRebuild = incrementalBuild.dependencyChanges(dependencies.values(), fileExtensions);
                }
                int optionsHash = 0; // Hash code collision may happen, this is a "best effort" only.
                if (checkOptions) {
                    optionsHash = compilerConfiguration.options.hashCode();
                    if (causeOfRebuild == null) {
                        causeOfRebuild = incrementalBuild.optionChanges(optionsHash);
                    }
                }
                if (causeOfRebuild != null) {
                    logger.info(causeOfRebuild);
                } else {
                    sourceFiles = incrementalBuild.getModifiedSources();
                    if (IncrementalBuild.isEmptyOrIgnorable(sourceFiles)) {
                        logger.info("Nothing to compile - all classes are up to date.");
                        return;
                    }
                }
                if (checkSources | checkDepends | checkOptions) {
                    incrementalBuild.writeCache(optionsHash, checkSources);
                }
            }
        }
        if (logger.isDebugEnabled()) {
            int n = sourceFiles.size();
            @SuppressWarnings("checkstyle:MagicNumber")
            final var sb =
                    new StringBuilder(n * 40).append("Compiling ").append(n).append(" source files:");
            for (SourceFile file : sourceFiles) {
                sb.append(System.lineSeparator()).append("    ").append(file);
            }
            logger.debug(sb);
        }
        /*
         * If we are compiling the test classes of a modular project, add the `--patch-modules` options.
         * Note that those options are handled like dependencies, because they will need to be set using
         * the `javax.tools.StandardLocation` API.
         */
        if (hasModuleDeclaration) {
            addSourceDirectories(dependencies, compileSourceRoots);
        }
        /*
         * Create a `JavaFileManager`, configure all paths (dependencies and sources), then run the compiler.
         * The Java file manager has a cache, so it needs to be disposed after the compilation is completed.
         * The same `JavaFileManager` may be reused for many compilation units (e.g. multi-releases) before
         * disposal in order to reuse its cache.
         */
        boolean success = true;
        Exception failureCause = null;
        final var unresolvedPaths = new ArrayList<Path>();
        final var compilerOutput = new StringWriter();
        final var listener = new DiagnosticLogger(logger, messageBuilderFactory, LOCALE);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(listener, LOCALE, charset())) {
            /*
             * Dispatch all dependencies on the kind of paths determined by `DependencyResolver`:
             * class-path, module-path, annotation processor class-path/module-path, etc.
             * This configuration will be unchanged for all compilation units.
             */
            List<String> patchedOptions = compilerConfiguration.options; // Workaround for JDK-TBD.
            for (Map.Entry<PathType, List<Path>> entry : dependencies.entrySet()) {
                List<Path> paths = entry.getValue();
                PathType key = entry.getKey(); // TODO: replace by pattern matching in Java 21.
                if (key instanceof JavaPathType type) {
                    Optional<JavaFileManager.Location> location = type.location();
                    if (location.isPresent()) { // Cannot use `Optional.ifPresent(…)` because of checked IOException.
                        fileManager.setLocationFromPaths(location.get(), paths);
                        continue;
                    }
                } else if (key instanceof JavaPathType.Modular type) {
                    Optional<JavaFileManager.Location> location = type.rawType().location();
                    if (location.isPresent()) {
                        try {
                            fileManager.setLocationForModule(location.get(), type.moduleName(), paths);
                        } catch (UnsupportedOperationException e) { // Workaround forJDK-TBD.
                            if (patchedOptions == compilerConfiguration.options) {
                                patchedOptions = new ArrayList<>(patchedOptions);
                            }
                            patchedOptions.addAll(Arrays.asList(type.option(paths)));
                        }
                        continue;
                    }
                }
                unresolvedPaths.addAll(paths);
            }
            if (!unresolvedPaths.isEmpty()) {
                var sb = new StringBuilder("Cannot determine where to place the following artifacts:");
                for (Path p : unresolvedPaths) {
                    sb.append(System.lineSeparator()).append(" - ").append(p);
                }
                logger.warn(sb);
            }
            /*
             * Configure all paths to source files. Each compilation unit has its own set of source.
             * More than one compilation unit may exist in the case of a multi-releases project.
             * Units are compiled in the order of the release version, with base compiled first.
             */
            if (!generatedSourceDirectories.isEmpty()) {
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, generatedSourceDirectories);
            }
            compile:
            for (SourcesForRelease unit : SourcesForRelease.groupByReleaseAndModule(sourceFiles)) {
                for (Map.Entry<String, Set<Path>> root : unit.roots.entrySet()) {
                    String moduleName = root.getKey();
                    if (moduleName.isBlank()) {
                        fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, root.getValue());
                    } else {
                        fileManager.setLocationForModule(
                                StandardLocation.MODULE_SOURCE_PATH, moduleName, root.getValue());
                    }
                }
                /*
                 * TODO: for all compilations after the base one, add the base to class-path or module-path.
                 * TODO: prepend META-INF/version/## to output directory if needed.
                 */
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Set.of(outputDirectory));
                /*
                 * Compile the source files now. The following loop should be executed exactly once.
                 * It may be executed twice when compiling test classes overwriting the `module-info`,
                 * in which case the `module-info` needs to be compiled separately from other classes.
                 * However, this is a deprecated practice.
                 */
                JavaCompiler.CompilationTask task;
                for (CompilationTaskSources c : toCompilationTasks(unit)) {
                    Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(c.files);
                    task = compiler.getTask(compilerOutput, fileManager, listener, patchedOptions, null, sources);
                    patchedOptions = compilerConfiguration.options; // Patched options shall be used only once.
                    success = c.compile(task);
                    if (!success) {
                        break compile;
                    }
                }
            }
            /*
             * Post-compilation.
             */
            listener.logSummary();
        } catch (UncheckedIOException e) {
            success = false;
            failureCause = e.getCause();
        } catch (Exception e) {
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
            if (success) {
                logger.warn(additionalMessage);
            } else {
                logger.error(additionalMessage);
            }
        }
        /*
         * In case of failure, or if debugging is enabled, dump the options to a file.
         * By default, the file will have the ".args" extension.
         */
        if (!success || logger.isDebugEnabled()) {
            try {
                writeDebugFile(compilerConfiguration.options, dependencies, sourceFiles);
                if (success && tipForCommandLineCompilation != null) {
                    logger.debug(tipForCommandLineCompilation);
                    tipForCommandLineCompilation = null;
                }
            } catch (IOException e) {
                if (failureCause != null) {
                    failureCause.addSuppressed(e);
                }
            }
        }
        if (!success && failOnError) {
            var message = new StringBuilder(100)
                    .append("Cannot compile ")
                    .append(project.getId())
                    .append(' ')
                    .append(isTestCompile ? "test" : "main")
                    .append(" classes.");
            listener.firstError(failureCause).ifPresent((c) -> message.append(System.lineSeparator())
                    .append("The first error is: ")
                    .append(c));
            throw new CompilationFailureException(message.toString(), failureCause);
        }
        /*
         * Workaround for MCOMPILER-542, needed only if a modular project is compiled with a JDK older than Java 22.
         * Note: a previous version used as an heuristic way to detect if Reproducible Build was enabled. This check
         * has been removed because Reproducible Build are enabled by default in Maven now.
         */
        if (!isVersionEqualOrNewer(compiler, "RELEASE_22")) {
            Path moduleDescriptor = getOutputDirectory().resolve(MODULE_INFO + CLASS_FILE_SUFFIX);
            if (Files.isRegularFile(moduleDescriptor)) {
                try {
                    byte[] oridinal = Files.readAllBytes(moduleDescriptor);
                    byte[] modified = ByteCodeTransformer.patchJdkModuleVersion(oridinal, getRelease(), logger);
                    if (modified != null) {
                        Files.write(moduleDescriptor, modified);
                    }
                } catch (IOException ex) {
                    throw new MojoException("Error reading or writing " + MODULE_INFO + CLASS_FILE_SUFFIX, ex);
                }
            }
        }
    }

    /**
     * Returns whether the given tool (usually the compiler) supports the given source version or newer versions.
     * The specified source version shall be the name of one of the {@link SourceVersion} enumeration values.
     * Note that a return value of {@code true} does not mean that the tool support that version,
     * as it may be too old. This method is rather for checking whether a tool need to be patched.
     */
    private static boolean isVersionEqualOrNewer(Tool tool, String sourceVersion) {
        final SourceVersion requested;
        try {
            requested = SourceVersion.valueOf(sourceVersion);
        } catch (IllegalArgumentException e) {
            // The current tool is from a JDK older than the one for the requested source release.
            return false;
        }
        return tool.getSourceVersions().stream().anyMatch((v) -> v.compareTo(requested) >= 0);
    }

    /**
     * {@return the tool chain specified by the user in plugin parameters}.
     */
    private Optional<Toolchain> getToolchain() {
        if (jdkToolchain != null) {
            List<Toolchain> tcs = toolchainManager.getToolchains(session, "jdk", jdkToolchain);
            if (tcs != null && !tcs.isEmpty()) {
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
     * {@return all dependencies organized by the path types where to place them}. If the module-path contains
     * any file-based dependency and this MOJO is compiling the main code, then a warning will be logged.
     *
     * @param compilerConfiguration where to add {@code --add-reads} options when compiling test classes
     * @param hasModuleDeclaration whether to allow placement of dependencies on the module-path.
     */
    private Map<PathType, List<Path>> resolveDependencies(Options compilerConfiguration, boolean hasModuleDeclaration)
            throws IOException {
        DependencyResolver resolver = session.getService(DependencyResolver.class);
        if (resolver == null) { // Null value happen during tests, depending on the mock used.
            return new LinkedHashMap<>(); // The caller needs a modifiable map.
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
                .pathScope(isTestCompile ? PathScope.TEST_COMPILE : PathScope.MAIN_COMPILE)
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
        if (!isTestCompile) {
            String warning = dependencies.warningForFilenameBasedAutomodules().orElse(null);
            if (warning != null) { // Do not use Optional.ifPresent(…) for avoiding confusing source class name.
                logger.warn(warning);
            }
        }
        /*
         * Add `--add-reads` options when compiling the test classes.
         * Nothing should be changed when compiling the main classes.
         */
        if (hasModuleDeclaration) {
            addModuleOptions(dependencies, compilerConfiguration);
        }
        // TODO: to be safe, we should perform a deep clone here.
        return dependencies.getDispatchedPaths();
    }

    /**
     * Adds paths to the annotation processor dependencies. Paths are added to the list associated
     * to the {@link JavaPathType#PROCESSOR_CLASSES} entry of given map, which should be modifiable.
     *
     * <h4>Implementation note</h4>
     * We rely on the fact that {@link org.apache.maven.internal.impl.DefaultDependencyResolverResult} creates
     * modifiable instances of map and lists. This is a fragile assumption, but this method is deprecated anyway
     * and may be removed in a future version.
     *
     * @param addTo the modifiable map and lists where to append more paths to annotation processor dependencies
     * @throws MojoException if an error occurred while resolving the dependencies
     *
     * @deprecated Replaced by ordinary dependencies with {@code <type>} element
     * set to {@code proc}, {@code classpath-proc} or {@code modular-proc}.
     */
    @Deprecated(since = "4.0.0")
    private void resolveProcessorPathEntries(Map<PathType, List<Path>> addTo) throws MojoException {
        List<DependencyCoordinate> dependencies = annotationProcessorPaths;
        if (dependencies != null && !dependencies.isEmpty()) {
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
     * Ensures that the directory for generated sources exists, and adds it to the list of source directories
     * known to the project manager. This is used for adding the output of annotation processor.
     * The given directory should be the result of {@link #getGeneratedSourcesDirectory()}.
     *
     * @param generatedSourcesDirectory the directory to add, or {@code null} if none
     * @return the added directory in a singleton set, or an empty set if none
     * @throws IOException if the directory cannot be created
     */
    private Set<Path> addGeneratedSourceDirectory(Path generatedSourcesDirectory) throws IOException {
        if (generatedSourcesDirectory == null) {
            return Set.of();
        }
        /*
         * Do not create an empty directory if this plugin is not going to generate new source files.
         * However, if a directory already exists, use it because maybe its content was generated by
         * another plugin executed before the compiler plugin.
         *
         * TODO: "none" become the default starting with Java 23.
         */
        if ("none".equalsIgnoreCase(proc) && Files.notExists(generatedSourcesDirectory)) {
            return Set.of();
        } else {
            // `createDirectories(Path)` does nothing if the directory already exists.
            generatedSourcesDirectory = Files.createDirectories(generatedSourcesDirectory);
        }
        ProjectScope scope = isTestCompile ? ProjectScope.TEST : ProjectScope.MAIN;
        projectManager.addCompileSourceRoot(project, scope, generatedSourcesDirectory.toAbsolutePath());
        if (logger.isDebugEnabled()) {
            var sb = new StringBuilder("Adding \"")
                    .append(generatedSourcesDirectory)
                    .append("\" to ")
                    .append(scope.id())
                    .append("-compile source roots. New roots are:");
            for (Path p : projectManager.getCompileSourceRoots(project, scope)) {
                sb.append(System.lineSeparator()).append("    ").append(p);
            }
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
        if (mavenCompilerPluginVersion != null && !mavenCompilerPluginVersion.isBlank()) {
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
     * @param options the compiler options
     * @param dependencies the dependencies
     * @param sourceFiles all files to compile
     * @throws IOException if an error occurred while writing the debug file
     */
    private void writeDebugFile(
            List<String> options, Map<PathType, List<Path>> dependencies, List<SourceFile> sourceFiles)
            throws IOException {
        final Path path = getDebugFilePath();
        if (path == null) {
            logger.warn("The <debugFileName> parameter should not be empty.");
            return;
        }
        final var commandLine = new StringBuilder("For trying to compile from the command-line, use:")
                .append(System.lineSeparator())
                .append("    ")
                .append(executable != null ? executable : compilerId);
        boolean hasOptions = false;
        try (BufferedWriter out = Files.newBufferedWriter(path)) {
            for (String option : options) {
                if (option.isBlank()) {
                    continue;
                }
                if (option.startsWith("-J")) {
                    commandLine.append(' ').append(option);
                    continue;
                }
                if (hasOptions) {
                    if (option.charAt(0) == '-') {
                        out.newLine();
                    } else {
                        out.write(' ');
                    }
                }
                boolean needsQuote = option.indexOf(' ') >= 0;
                if (needsQuote) {
                    out.write('"');
                }
                out.write(option);
                if (needsQuote) {
                    out.write('"');
                }
                hasOptions = true;
            }
            if (hasOptions) {
                out.newLine();
            }
            for (Map.Entry<PathType, List<Path>> entry : dependencies.entrySet()) {
                String separator = "";
                for (String element : entry.getKey().option(entry.getValue())) {
                    out.write(separator);
                    out.write(element);
                    separator = " ";
                }
                out.newLine();
            }
            out.write("-d \"");
            out.write(getOutputDirectory().toString());
            out.write('"');
            out.newLine();
            for (SourceFile sf : sourceFiles) {
                out.write('"');
                out.write(sf.file.toString());
                out.write('"');
                out.newLine();
            }
        }
        tipForCommandLineCompilation = commandLine.append(" @").append(path).toString();
    }
}
