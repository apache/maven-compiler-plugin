package org.apache.maven.plugin.compiler;

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

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.incremental.IncrementalBuildHelper;
import org.apache.maven.shared.incremental.IncrementalBuildHelperRequest;
import org.apache.maven.shared.utils.ReaderFactory;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerError;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerNotImplementedException;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.CompilerResult;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SingleTargetSourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;

/**
 * TODO: At least one step could be optimized, currently the plugin will do two
 * scans of all the source code if the compiler has to have the entire set of
 * sources. This is currently the case for at least the C# compiler and most
 * likely all the other .NET compilers too.
 *
 * @author others
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 * @version $Id$
 * @since 2.0
 */
public abstract class AbstractCompilerMojo
    extends AbstractMojo
{

    static final String DEFAULT_SOURCE = "1.5";
    
    static final String DEFAULT_TARGET = "1.5";
    
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
    @Parameter( property = "maven.compiler.failOnError", defaultValue = "true" )
    private boolean failOnError = true;
    
    /**
     * Indicates whether the build will continue even if there are compilation warnings.
     *
     * @since 3.6
     */
    @Parameter( property = "maven.compiler.failOnWarning", defaultValue = "false" )
    private boolean failOnWarning;  

    /**
     * Set to <code>true</code> to include debugging information in the compiled class files.
     */
    @Parameter( property = "maven.compiler.debug", defaultValue = "true" )
    private boolean debug = true;

    /**
     * Set to <code>true</code> to generate metadata for reflection on method parameters.
     */
    @Parameter( property = "maven.compiler.parameters", defaultValue = "false" )
    private boolean parameters;

    /**
     * Set to <code>true</code> to show messages about what the compiler is doing.
     */
    @Parameter( property = "maven.compiler.verbose", defaultValue = "false" )
    private boolean verbose;

    /**
     * Sets whether to show source locations where deprecated APIs are used.
     */
    @Parameter( property = "maven.compiler.showDeprecation", defaultValue = "false" )
    private boolean showDeprecation;

    /**
     * Set to <code>true</code> to optimize the compiled code using the compiler's optimization methods.
     */
    @Parameter( property = "maven.compiler.optimize", defaultValue = "false" )
    private boolean optimize;

    /**
     * Set to <code>true</code> to show compilation warnings.
     */
    @Parameter( property = "maven.compiler.showWarnings", defaultValue = "false" )
    private boolean showWarnings;

    /**
     * The -source argument for the Java compiler.
     */
    @Parameter( property = "maven.compiler.source", defaultValue = DEFAULT_SOURCE )
    protected String source;

    /**
     * The -target argument for the Java compiler.
     */
    @Parameter( property = "maven.compiler.target", defaultValue = DEFAULT_TARGET )
    protected String target;

    /**
     * The -release argument for the Java compiler, supported since Java9
     * 
     * @since 3.6
     */
    @Parameter( property = "maven.compiler.release" )
    protected String release;
    
    /**
     * The -encoding argument for the Java compiler.
     *
     * @since 2.1
     */
    @Parameter( property = "encoding", defaultValue = "${project.build.sourceEncoding}" )
    private String encoding;

    /**
     * Sets the granularity in milliseconds of the last modification
     * date for testing whether a source needs recompilation.
     */
    @Parameter( property = "lastModGranularityMs", defaultValue = "0" )
    private int staleMillis;

    /**
     * The compiler id of the compiler to use. See this
     * <a href="non-javac-compilers.html">guide</a> for more information.
     */
    @Parameter( property = "maven.compiler.compilerId", defaultValue = "javac" )
    private String compilerId;

    /**
     * Version of the compiler to use, ex. "1.3", "1.5", if {@link #fork} is set to <code>true</code>.
     */
    @Parameter( property = "maven.compiler.compilerVersion" )
    private String compilerVersion;

    /**
     * Allows running the compiler in a separate process.
     * If <code>false</code> it uses the built in compiler, while if <code>true</code> it will use an executable.
     */
    @Parameter( property = "maven.compiler.fork", defaultValue = "false" )
    private boolean fork;

    /**
     * Initial size, in megabytes, of the memory allocation pool, ex. "64", "64m"
     * if {@link #fork} is set to <code>true</code>.
     *
     * @since 2.0.1
     */
    @Parameter( property = "maven.compiler.meminitial" )
    private String meminitial;

    /**
     * Sets the maximum size, in megabytes, of the memory allocation pool, ex. "128", "128m"
     * if {@link #fork} is set to <code>true</code>.
     *
     * @since 2.0.1
     */
    @Parameter( property = "maven.compiler.maxmem" )
    private String maxmem;

    /**
     * Sets the executable of the compiler to use when {@link #fork} is <code>true</code>.
     */
    @Parameter( property = "maven.compiler.executable" )
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
     * </ul>
     *
     * @since 2.2
     */
    @Parameter
    private String proc;

    /**
     * <p>
     * Names of annotation processors to run. Only applies to JDK 1.6+
     * If not set, the default annotation processors discovery process applies.
     * </p>
     *
     * @since 2.2
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
     * type). Transitive dependencies are added automatically. Example:
     * </p>
     *
     * <pre>
     * &lt;configuration&gt;
     *   &lt;annotationProcessorPaths&gt;
     *     &lt;path&gt;
     *       &lt;groupId&gt;org.sample&lt;/groupId&gt;
     *       &lt;artifactId&gt;sample-annotation-processor&lt;/artifactId&gt;
     *       &lt;version&gt;1.2.3&lt;/version&gt;
     *     &lt;/path&gt;
     *     &lt;!-- ... more ... --&gt;
     *   &lt;/annotationProcessorPaths&gt;
     * &lt;/configuration&gt;
     * </pre>
     *
     * @since 3.5
     */
    @Parameter
    private List<DependencyCoordinate> annotationProcessorPaths;

    /**
     * <p>
     * Sets the arguments to be passed to the compiler (prepending a dash) if {@link #fork} is set to <code>true</code>.
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler varies based on the compiler version.
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
     * Sets the arguments to be passed to the compiler if {@link #fork} is set to <code>true</code>.
     * Example:
     * <pre>
     * &lt;compilerArgs&gt;
     *   &lt;arg&gt;-Xmaxerrs=1000&lt;/arg&gt;
     *   &lt;arg&gt;-Xlint&lt;/arg&gt;
     *   &lt;arg&gt;-J-Duser.language=en_us&lt;/arg&gt;
     * &lt;/compilerArgs&gt;
     * </pre>
     *
     * @since 3.1
     */
    @Parameter
    protected List<String> compilerArgs;

    /**
     * <p>
     * Sets the unformatted single argument string to be passed to the compiler if {@link #fork} is set to
     * <code>true</code>. To pass multiple arguments such as <code>-Xmaxerrs 1000</code> (which are actually two
     * arguments) you have to use {@link #compilerArguments}.
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler varies based on the compiler version.
     * </p>
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
     * If debug is not turned on, this attribute will be ignored.
     *
     * @since 2.1
     */
    @Parameter( property = "maven.compiler.debuglevel" )
    private String debuglevel;

    /**
     *
     */
    @Component
    private ToolchainManager toolchainManager;

    /**
     * <p>
     * Specify the requirements for this jdk toolchain.
     * This overrules the toolchain selected by the maven-toolchain-plugin.
     * </p>
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
    @Parameter( defaultValue = "${basedir}", required = true, readonly = true )
    private File basedir;

    /**
     * The target directory of the compiler if fork is true.
     */
    @Parameter( defaultValue = "${project.build.directory}", required = true, readonly = true )
    private File buildDirectory;

    /**
     * Plexus compiler manager.
     */
    @Component
    private CompilerManager compilerManager;

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    /**
     * The current project instance. This is used for propagating generated-sources paths as compile/testCompile source
     * roots.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
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
    @Parameter( defaultValue = "${reuseCreated}", property = "maven.compiler.compilerReuseStrategy" )
    private String compilerReuseStrategy = "reuseCreated";

    /**
     * @since 2.5
     */
    @Parameter( defaultValue = "false", property = "maven.compiler.skipMultiThreadWarning" )
    private boolean skipMultiThreadWarning;

    /**
     * compiler can now use javax.tools if available in your current jdk, you can disable this feature
     * using -Dmaven.compiler.forceJavacCompilerUse=true or in the plugin configuration
     *
     * @since 3.0
     */
    @Parameter( defaultValue = "false", property = "maven.compiler.forceJavacCompilerUse" )
    private boolean forceJavacCompilerUse;

    /**
     * @since 3.0 needed for storing the status for the incremental build support.
     */
    @Parameter( defaultValue = "${mojoExecution}", readonly = true, required = true )
    private MojoExecution mojoExecution;

    /**
     * file extensions to check timestamp for incremental build
     * <b>default contains only <code>.class</code></b>
     *
     * @since 3.1
     */
    @Parameter
    private List<String> fileExtensions;

    /**
     * to enable/disable incrementation compilation feature
     * @since 3.1
     */
    @Parameter( defaultValue = "true", property = "maven.compiler.useIncrementalCompilation" )
    private boolean useIncrementalCompilation = true;

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

    /**
     * Throws an exception on artifact resolution errors.
     */
    @Component
    private ResolutionErrorHandler resolutionErrorHandler;

    protected abstract SourceInclusionScanner getSourceInclusionScanner( int staleMillis );

    protected abstract SourceInclusionScanner getSourceInclusionScanner( String inputFileEnding );

    protected abstract List<String> getClasspathElements();

    protected abstract List<String> getModulepathElements();

    protected abstract List<String> getCompileSourceRoots();
    
    protected abstract void preparePaths( Set<File> sourceFiles );

    protected abstract File getOutputDirectory();

    protected abstract String getSource();

    protected abstract String getTarget();

    protected abstract String getRelease();

    protected abstract String getCompilerArgument();

    protected abstract Map<String, String> getCompilerArguments();

    protected abstract File getGeneratedSourcesDirectory();

    protected final MavenProject getProject()
    {
        return project;
    }

    @Override
    public void execute()
        throws MojoExecutionException, CompilationFailureException
    {
        // ----------------------------------------------------------------------
        // Look up the compiler. This is done before other code than can
        // cause the mojo to return before the lookup is done possibly resulting
        // in misconfigured POMs still building.
        // ----------------------------------------------------------------------

        Compiler compiler;

        getLog().debug( "Using compiler '" + compilerId + "'." );

        try
        {
            compiler = compilerManager.getCompiler( compilerId );
        }
        catch ( NoSuchCompilerException e )
        {
            throw new MojoExecutionException( "No such compiler '" + e.getCompilerId() + "'." );
        }

        //-----------toolchains start here ----------------------------------
        //use the compilerId as identifier for toolchains as well.
        Toolchain tc = getToolchain();
        if ( tc != null )
        {
            getLog().info( "Toolchain in maven-compiler-plugin: " + tc );
            if ( executable != null )
            {
                getLog().warn( "Toolchains are ignored, 'executable' parameter is set to " + executable );
            }
            else
            {
                fork = true;
                //TODO somehow shaky dependency between compilerId and tool executable.
                executable = tc.findTool( compilerId );
            }
        }
        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        List<String> compileSourceRoots = removeEmptyCompileSourceRoots( getCompileSourceRoots() );

        if ( compileSourceRoots.isEmpty() )
        {
            getLog().info( "No sources to compile" );

            return;
        }

        // ----------------------------------------------------------------------
        // Create the compiler configuration
        // ----------------------------------------------------------------------

        CompilerConfiguration compilerConfiguration = new CompilerConfiguration();

        compilerConfiguration.setOutputLocation( getOutputDirectory().getAbsolutePath() );

        compilerConfiguration.setOptimize( optimize );

        compilerConfiguration.setDebug( debug );

        if ( debug && StringUtils.isNotEmpty( debuglevel ) )
        {
            String[] split = StringUtils.split( debuglevel, "," );
            for ( String aSplit : split )
            {
                if ( !( aSplit.equalsIgnoreCase( "none" ) || aSplit.equalsIgnoreCase( "lines" )
                    || aSplit.equalsIgnoreCase( "vars" ) || aSplit.equalsIgnoreCase( "source" ) ) )
                {
                    throw new IllegalArgumentException( "The specified debug level: '" + aSplit + "' is unsupported. "
                        + "Legal values are 'none', 'lines', 'vars', and 'source'." );
                }
            }
            compilerConfiguration.setDebugLevel( debuglevel );
        }

        compilerConfiguration.setParameters( parameters );

        compilerConfiguration.setVerbose( verbose );

        compilerConfiguration.setShowWarnings( showWarnings );

        compilerConfiguration.setFailOnWarning( failOnWarning );

        compilerConfiguration.setShowDeprecation( showDeprecation );

        compilerConfiguration.setSourceVersion( getSource() );

        compilerConfiguration.setTargetVersion( getTarget() );
        
        compilerConfiguration.setReleaseVersion( getRelease() );

        compilerConfiguration.setProc( proc );

        File generatedSourcesDirectory = getGeneratedSourcesDirectory();
        compilerConfiguration.setGeneratedSourcesDirectory( generatedSourcesDirectory != null
                        ? generatedSourcesDirectory.getAbsoluteFile() : null );

        if ( generatedSourcesDirectory != null )
        {
            String generatedSourcesPath = generatedSourcesDirectory.getAbsolutePath();

            compileSourceRoots.add( generatedSourcesPath );

            if ( isTestCompile() )
            {
                getLog().debug( "Adding " + generatedSourcesPath + " to test-compile source roots:\n  "
                                    + StringUtils.join( project.getTestCompileSourceRoots()
                                                               .iterator(), "\n  " ) );

                project.addTestCompileSourceRoot( generatedSourcesPath );

                getLog().debug( "New test-compile source roots:\n  "
                                    + StringUtils.join( project.getTestCompileSourceRoots()
                                                               .iterator(), "\n  " ) );
            }
            else
            {
                getLog().debug( "Adding " + generatedSourcesPath + " to compile source roots:\n  "
                                    + StringUtils.join( project.getCompileSourceRoots()
                                                               .iterator(), "\n  " ) );

                project.addCompileSourceRoot( generatedSourcesPath );

                getLog().debug( "New compile source roots:\n  " + StringUtils.join( project.getCompileSourceRoots()
                                                                                           .iterator(), "\n  " ) );
            }
        }

        compilerConfiguration.setSourceLocations( compileSourceRoots );

        compilerConfiguration.setAnnotationProcessors( annotationProcessors );

        compilerConfiguration.setProcessorPathEntries( resolveProcessorPathEntries() );

        compilerConfiguration.setSourceEncoding( encoding );

        compilerConfiguration.setFork( fork );

        if ( fork )
        {
            if ( !StringUtils.isEmpty( meminitial ) )
            {
                String value = getMemoryValue( meminitial );

                if ( value != null )
                {
                    compilerConfiguration.setMeminitial( value );
                }
                else
                {
                    getLog().info( "Invalid value for meminitial '" + meminitial + "'. Ignoring this option." );
                }
            }

            if ( !StringUtils.isEmpty( maxmem ) )
            {
                String value = getMemoryValue( maxmem );

                if ( value != null )
                {
                    compilerConfiguration.setMaxmem( value );
                }
                else
                {
                    getLog().info( "Invalid value for maxmem '" + maxmem + "'. Ignoring this option." );
                }
            }
        }

        compilerConfiguration.setExecutable( executable );

        compilerConfiguration.setWorkingDirectory( basedir );

        compilerConfiguration.setCompilerVersion( compilerVersion );

        compilerConfiguration.setBuildDirectory( buildDirectory );

        compilerConfiguration.setOutputFileName( outputFileName );

        if ( CompilerConfiguration.CompilerReuseStrategy.AlwaysNew.getStrategy().equals( this.compilerReuseStrategy ) )
        {
            compilerConfiguration.setCompilerReuseStrategy( CompilerConfiguration.CompilerReuseStrategy.AlwaysNew );
        }
        else if ( CompilerConfiguration.CompilerReuseStrategy.ReuseSame.getStrategy().equals(
            this.compilerReuseStrategy ) )
        {
            if ( getRequestThreadCount() > 1 )
            {
                if ( !skipMultiThreadWarning )
                {
                    getLog().warn( "You are in a multi-thread build and compilerReuseStrategy is set to reuseSame."
                                       + " This can cause issues in some environments (os/jdk)!"
                                       + " Consider using reuseCreated strategy."
                                       + System.getProperty( "line.separator" )
                                       + "If your env is fine with reuseSame, you can skip this warning with the "
                                       + "configuration field skipMultiThreadWarning "
                                       + "or -Dmaven.compiler.skipMultiThreadWarning=true" );
                }
            }
            compilerConfiguration.setCompilerReuseStrategy( CompilerConfiguration.CompilerReuseStrategy.ReuseSame );
        }
        else
        {

            compilerConfiguration.setCompilerReuseStrategy( CompilerConfiguration.CompilerReuseStrategy.ReuseCreated );
        }

        getLog().debug( "CompilerReuseStrategy: " + compilerConfiguration.getCompilerReuseStrategy().getStrategy() );

        compilerConfiguration.setForceJavacCompilerUse( forceJavacCompilerUse );

        boolean canUpdateTarget;

        IncrementalBuildHelper incrementalBuildHelper = new IncrementalBuildHelper( mojoExecution, session );

        Set<File> sources;

        IncrementalBuildHelperRequest incrementalBuildHelperRequest = null;

        if ( useIncrementalCompilation )
        {
            getLog().debug( "useIncrementalCompilation enabled" );
            try
            {
                canUpdateTarget = compiler.canUpdateTarget( compilerConfiguration );

                sources = getCompileSources( compiler, compilerConfiguration );
                
                preparePaths( sources );

                incrementalBuildHelperRequest = new IncrementalBuildHelperRequest().inputFiles( sources );

                // CHECKSTYLE_OFF: LineLength
                if ( ( compiler.getCompilerOutputStyle().equals( CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES ) && !canUpdateTarget )
                    || isDependencyChanged()
                    || isSourceChanged( compilerConfiguration, compiler )
                    || incrementalBuildHelper.inputFileTreeChanged( incrementalBuildHelperRequest ) )
                    // CHECKSTYLE_ON: LineLength
                {
                    getLog().info( "Changes detected - recompiling the module!" );

                    compilerConfiguration.setSourceFiles( sources );
                }
                else
                {
                    getLog().info( "Nothing to compile - all classes are up to date" );

                    return;
                }
            }
            catch ( CompilerException e )
            {
                throw new MojoExecutionException( "Error while computing stale sources.", e );
            }
        }
        else
        {
            getLog().debug( "useIncrementalCompilation disabled" );
            Set<File> staleSources;
            try
            {
                staleSources =
                    computeStaleSources( compilerConfiguration, compiler, getSourceInclusionScanner( staleMillis ) );

                canUpdateTarget = compiler.canUpdateTarget( compilerConfiguration );

                if ( compiler.getCompilerOutputStyle().equals( CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES )
                    && !canUpdateTarget )
                {
                    getLog().info( "RESCANNING!" );
                    // TODO: This second scan for source files is sub-optimal
                    String inputFileEnding = compiler.getInputFileEnding( compilerConfiguration );

                    sources = computeStaleSources( compilerConfiguration, compiler,
                                                             getSourceInclusionScanner( inputFileEnding ) );

                    compilerConfiguration.setSourceFiles( sources );
                }
                else
                {
                    compilerConfiguration.setSourceFiles( staleSources );
                }
                
                preparePaths( compilerConfiguration.getSourceFiles() );
            }
            catch ( CompilerException e )
            {
                throw new MojoExecutionException( "Error while computing stale sources.", e );
            }

            if ( staleSources.isEmpty() )
            {
                getLog().info( "Nothing to compile - all classes are up to date" );

                return;
            }
        }
        
        // Dividing pathElements of classPath and modulePath is based on sourceFiles
        compilerConfiguration.setClasspathEntries( getClasspathElements() );

        compilerConfiguration.setModulepathEntries( getModulepathElements() );
        
        Map<String, String> effectiveCompilerArguments = getCompilerArguments();

        String effectiveCompilerArgument = getCompilerArgument();

        if ( ( effectiveCompilerArguments != null ) || ( effectiveCompilerArgument != null )
                        || ( compilerArgs != null ) )
        {
            if ( effectiveCompilerArguments != null )
            {
                for ( Map.Entry<String, String> me : effectiveCompilerArguments.entrySet() )
                {
                    String key = me.getKey();
                    String value = me.getValue();
                    if ( !key.startsWith( "-" ) )
                    {
                        key = "-" + key;
                    }

                    if ( key.startsWith( "-A" ) && StringUtils.isNotEmpty( value ) )
                    {
                        compilerConfiguration.addCompilerCustomArgument( key + "=" + value, null );
                    }
                    else
                    {
                        compilerConfiguration.addCompilerCustomArgument( key, value );
                    }
                }
            }
            if ( !StringUtils.isEmpty( effectiveCompilerArgument ) )
            {
                compilerConfiguration.addCompilerCustomArgument( effectiveCompilerArgument, null );
            }
            if ( compilerArgs != null )
            {
                for ( String arg : compilerArgs )
                {
                    compilerConfiguration.addCompilerCustomArgument( arg, null );
                }
            }
        }

        // ----------------------------------------------------------------------
        // Dump configuration
        // ----------------------------------------------------------------------
        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "Classpath:" );

            for ( String s : getClasspathElements() )
            {
                getLog().debug( " " + s );
            }

            if ( !getModulepathElements().isEmpty() )
            {
                getLog().debug( "Modulepath:" );
                for ( String s : getModulepathElements() )
                {
                    getLog().debug( " " + s );
                }
            }

            getLog().debug( "Source roots:" );

            for ( String root : getCompileSourceRoots() )
            {
                getLog().debug( " " + root );
            }

            try
            {
                if ( fork )
                {
                    if ( compilerConfiguration.getExecutable() != null )
                    {
                        getLog().debug( "Excutable: " );
                        getLog().debug( " " + compilerConfiguration.getExecutable() );
                    }
                }

                String[] cl = compiler.createCommandLine( compilerConfiguration );
                if ( getLog().isDebugEnabled() && cl != null && cl.length > 0 )
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append( cl[0] );
                    for ( int i = 1; i < cl.length; i++ )
                    {
                        sb.append( " " );
                        sb.append( cl[i] );
                    }
                    getLog().debug( "Command line options:" );
                    getLog().debug( sb );
                }
            }
            catch ( CompilerException ce )
            {
                getLog().debug( ce );
            }
        }

        // ----------------------------------------------------------------------
        // Compile!
        // ----------------------------------------------------------------------

        if ( StringUtils.isEmpty( compilerConfiguration.getSourceEncoding() ) )
        {
            getLog().warn( "File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
                               + ", i.e. build is platform dependent!" );
        }

        CompilerResult compilerResult;


        if ( useIncrementalCompilation )
        {
            incrementalBuildHelperRequest.outputDirectory( getOutputDirectory() );

            incrementalBuildHelper.beforeRebuildExecution( incrementalBuildHelperRequest );

            getLog().debug( "incrementalBuildHelper#beforeRebuildExecution" );
        }

        try
        {
            try
            {
                compilerResult = compiler.performCompile( compilerConfiguration );
            }
            catch ( CompilerNotImplementedException cnie )
            {
                List<CompilerError> messages = compiler.compile( compilerConfiguration );
                compilerResult = convertToCompilerResult( messages );
            }
        }
        catch ( Exception e )
        {
            // TODO: don't catch Exception
            throw new MojoExecutionException( "Fatal error compiling", e );
        }

        if ( useIncrementalCompilation )
        {
            if ( incrementalBuildHelperRequest.getOutputDirectory().exists() )
            {
                getLog().debug( "incrementalBuildHelper#afterRebuildExecution" );
                // now scan the same directory again and create a diff
                incrementalBuildHelper.afterRebuildExecution( incrementalBuildHelperRequest );
            }
            else
            {
                getLog().debug(
                    "skip incrementalBuildHelper#afterRebuildExecution as the output directory doesn't exist" );
            }
        }

        List<CompilerMessage> warnings = new ArrayList<CompilerMessage>();
        List<CompilerMessage> errors = new ArrayList<CompilerMessage>();
        List<CompilerMessage> others = new ArrayList<CompilerMessage>();
        for ( CompilerMessage message : compilerResult.getCompilerMessages() )
        {
            if ( message.getKind() == CompilerMessage.Kind.ERROR )
            {
                errors.add( message );
            }
            else if ( message.getKind() == CompilerMessage.Kind.WARNING
                || message.getKind() == CompilerMessage.Kind.MANDATORY_WARNING )
            {
                warnings.add( message );
            }
            else
            {
                others.add( message );
            }
        }

        if ( failOnError && !compilerResult.isSuccess() )
        {
            for ( CompilerMessage message : others )
            {
                assert message.getKind() != CompilerMessage.Kind.ERROR
                    && message.getKind() != CompilerMessage.Kind.WARNING
                    && message.getKind() != CompilerMessage.Kind.MANDATORY_WARNING;
                getLog().info( message.toString() );
            }
            if ( !warnings.isEmpty() )
            {
                getLog().info( "-------------------------------------------------------------" );
                getLog().warn( "COMPILATION WARNING : " );
                getLog().info( "-------------------------------------------------------------" );
                for ( CompilerMessage warning : warnings )
                {
                    getLog().warn( warning.toString() );
                }
                getLog().info( warnings.size() + ( ( warnings.size() > 1 ) ? " warnings " : " warning" ) );
                getLog().info( "-------------------------------------------------------------" );
            }

            if ( !errors.isEmpty() )
            {
                getLog().info( "-------------------------------------------------------------" );
                getLog().error( "COMPILATION ERROR : " );
                getLog().info( "-------------------------------------------------------------" );
                for ( CompilerMessage error : errors )
                {
                    getLog().error( error.toString() );
                }
                getLog().info( errors.size() + ( ( errors.size() > 1 ) ? " errors " : " error" ) );
                getLog().info( "-------------------------------------------------------------" );
            }

            if ( !errors.isEmpty() )
            {
                throw new CompilationFailureException( errors );
            }
            else
            {
                throw new CompilationFailureException( warnings );
            }
        }
        else
        {
            for ( CompilerMessage message : compilerResult.getCompilerMessages() )
            {
                switch ( message.getKind() )
                {
                    case NOTE:
                    case OTHER:
                        getLog().info( message.toString() );
                        break;

                    case ERROR:
                        getLog().error( message.toString() );
                        break;

                    case MANDATORY_WARNING:
                    case WARNING:
                    default:
                        getLog().warn( message.toString() );
                        break;
                }
            }
        }
    }

    protected boolean isTestCompile()
    {
        return false;
    }

    protected CompilerResult convertToCompilerResult( List<CompilerError> compilerErrors )
    {
        if ( compilerErrors == null )
        {
            return new CompilerResult();
        }
        List<CompilerMessage> messages = new ArrayList<CompilerMessage>( compilerErrors.size() );
        boolean success = true;
        for ( CompilerError compilerError : compilerErrors )
        {
            messages.add(
                new CompilerMessage( compilerError.getFile(), compilerError.getKind(), compilerError.getStartLine(),
                                     compilerError.getStartColumn(), compilerError.getEndLine(),
                                     compilerError.getEndColumn(), compilerError.getMessage() ) );
            if ( compilerError.isError() )
            {
                success = false;
            }
        }

        return new CompilerResult( success, messages );
    }

    /**
     * @return all source files for the compiler
     */
    private Set<File> getCompileSources( Compiler compiler, CompilerConfiguration compilerConfiguration )
        throws MojoExecutionException, CompilerException
    {
        String inputFileEnding = compiler.getInputFileEnding( compilerConfiguration );
        if ( StringUtils.isEmpty( inputFileEnding ) )
        {
            // see MCOMPILER-199 GroovyEclipseCompiler doesn't set inputFileEnding
            // so we can presume it's all files from the source directory
            inputFileEnding = ".*";
        }
        SourceInclusionScanner scanner = getSourceInclusionScanner( inputFileEnding );

        SourceMapping mapping = getSourceMapping( compilerConfiguration, compiler );

        scanner.addSourceMapping( mapping );

        Set<File> compileSources = new HashSet<File>();

        for ( String sourceRoot : getCompileSourceRoots() )
        {
            File rootFile = new File( sourceRoot );

            if ( !rootFile.isDirectory()
                || rootFile.getAbsoluteFile().equals( compilerConfiguration.getGeneratedSourcesDirectory() ) )
            {
                continue;
            }

            try
            {
                compileSources.addAll( scanner.getIncludedSources( rootFile, null ) );
            }
            catch ( InclusionScanException e )
            {
                throw new MojoExecutionException(
                    "Error scanning source root: \'" + sourceRoot + "\' for stale files to recompile.", e );
            }
        }

        return compileSources;
    }

    /**
     * @param compilerConfiguration
     * @param compiler
     * @return <code>true</code> if at least a single source file is newer than it's class file
     */
    private boolean isSourceChanged( CompilerConfiguration compilerConfiguration, Compiler compiler )
        throws CompilerException, MojoExecutionException
    {
        Set<File> staleSources =
            computeStaleSources( compilerConfiguration, compiler, getSourceInclusionScanner( staleMillis ) );

        if ( getLog().isDebugEnabled() )
        {
            for ( File f : staleSources )
            {
                getLog().debug( "Stale source detected: " + f.getAbsolutePath() );
            }
        }
        return staleSources != null && staleSources.size() > 0;
    }


    /**
     * try to get thread count if a Maven 3 build, using reflection as the plugin must not be maven3 api dependent
     *
     * @return number of thread for this build or 1 if not multi-thread build
     */
    protected int getRequestThreadCount()
    {
        try
        {
            Method getRequestMethod = session.getClass().getMethod( "getRequest" );
            Object mavenExecutionRequest = getRequestMethod.invoke( this.session );
            Method getThreadCountMethod = mavenExecutionRequest.getClass().getMethod( "getThreadCount" );
            String threadCount = (String) getThreadCountMethod.invoke( mavenExecutionRequest );
            return Integer.valueOf( threadCount );
        }
        catch ( Exception e )
        {
            getLog().debug( "unable to get threadCount for the current build: " + e.getMessage() );
        }
        return 1;
    }

    protected Date getBuildStartTime()
    {
        Date buildStartTime = null;
        try
        {
            Method getRequestMethod = session.getClass().getMethod( "getRequest" );
            Object mavenExecutionRequest = getRequestMethod.invoke( session );
            Method getStartTimeMethod = mavenExecutionRequest.getClass().getMethod( "getStartTime" );
            buildStartTime = (Date) getStartTimeMethod.invoke( mavenExecutionRequest );
        }
        catch ( Exception e )
        {
            getLog().debug( "unable to get start time for the current build: " + e.getMessage() );
        }

        if ( buildStartTime == null )
        {
            return new Date();
        }

        return buildStartTime;
    }


    private String getMemoryValue( String setting )
    {
        String value = null;

        // Allow '128' or '128m'
        if ( isDigits( setting ) )
        {
            value = setting + "m";
        }
        else if ( ( isDigits( setting.substring( 0, setting.length() - 1 ) ) )
            && ( setting.toLowerCase().endsWith( "m" ) ) )
        {
            value = setting;
        }
        return value;
    }

    //TODO remove the part with ToolchainManager lookup once we depend on
    //3.0.9 (have it as prerequisite). Define as regular component field then.
    private Toolchain getToolchain()
    {
        Toolchain tc = null;
        
        if ( jdkToolchain != null )
        {
            // Maven 3.3.1 has plugin execution scoped Toolchain Support
            try
            {
                Method getToolchainsMethod =
                    toolchainManager.getClass().getMethod( "getToolchains", MavenSession.class, String.class,
                                                           Map.class );

                @SuppressWarnings( "unchecked" )
                List<Toolchain> tcs =
                    (List<Toolchain>) getToolchainsMethod.invoke( toolchainManager, session, "jdk",
                                                                  jdkToolchain );

                if ( tcs != null && tcs.size() > 0 )
                {
                    tc = tcs.get( 0 );
                }
            }
            catch ( NoSuchMethodException e )
            {
                // ignore
            }
            catch ( SecurityException e )
            {
                // ignore
            }
            catch ( IllegalAccessException e )
            {
                // ignore
            }
            catch ( IllegalArgumentException e )
            {
                // ignore
            }
            catch ( InvocationTargetException e )
            {
                // ignore
            }
        }
        
        if ( tc == null )
        {
            tc = toolchainManager.getToolchainFromBuildContext( "jdk", session );
        }
        
        return tc;
    }

    private boolean isDigits( String string )
    {
        for ( int i = 0; i < string.length(); i++ )
        {
            if ( !Character.isDigit( string.charAt( i ) ) )
            {
                return false;
            }
        }
        return true;
    }

    private Set<File> computeStaleSources( CompilerConfiguration compilerConfiguration, Compiler compiler,
                                           SourceInclusionScanner scanner )
        throws MojoExecutionException, CompilerException
    {
        SourceMapping mapping = getSourceMapping( compilerConfiguration, compiler );

        File outputDirectory;
        CompilerOutputStyle outputStyle = compiler.getCompilerOutputStyle();
        if ( outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES )
        {
            outputDirectory = buildDirectory;
        }
        else
        {
            outputDirectory = getOutputDirectory();
        }

        scanner.addSourceMapping( mapping );

        Set<File> staleSources = new HashSet<File>();

        for ( String sourceRoot : getCompileSourceRoots() )
        {
            File rootFile = new File( sourceRoot );

            if ( !rootFile.isDirectory() )
            {
                continue;
            }

            try
            {
                staleSources.addAll( scanner.getIncludedSources( rootFile, outputDirectory ) );
            }
            catch ( InclusionScanException e )
            {
                throw new MojoExecutionException(
                    "Error scanning source root: \'" + sourceRoot + "\' for stale files to recompile.", e );
            }
        }

        return staleSources;
    }

    private SourceMapping getSourceMapping( CompilerConfiguration compilerConfiguration, Compiler compiler )
        throws CompilerException, MojoExecutionException
    {
        CompilerOutputStyle outputStyle = compiler.getCompilerOutputStyle();

        SourceMapping mapping;
        if ( outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE )
        {
            mapping = new SuffixMapping( compiler.getInputFileEnding( compilerConfiguration ),
                                         compiler.getOutputFileEnding( compilerConfiguration ) );
        }
        else if ( outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES )
        {
            mapping = new SingleTargetSourceMapping( compiler.getInputFileEnding( compilerConfiguration ),
                                                     compiler.getOutputFile( compilerConfiguration ) );

        }
        else
        {
            throw new MojoExecutionException( "Unknown compiler output style: '" + outputStyle + "'." );
        }
        return mapping;
    }

    /**
     * @todo also in ant plugin. This should be resolved at some point so that it does not need to
     * be calculated continuously - or should the plugins accept empty source roots as is?
     */
    private static List<String> removeEmptyCompileSourceRoots( List<String> compileSourceRootsList )
    {
        List<String> newCompileSourceRootsList = new ArrayList<String>();
        if ( compileSourceRootsList != null )
        {
            // copy as I may be modifying it
            for ( String srcDir : compileSourceRootsList )
            {
                if ( !newCompileSourceRootsList.contains( srcDir ) && new File( srcDir ).exists() )
                {
                    newCompileSourceRootsList.add( srcDir );
                }
            }
        }
        return newCompileSourceRootsList;
    }

    /**
     * We just compare the timestamps of all local dependency files (inter-module dependency classpath) and the own
     * generated classes and if we got a file which is &gt;= the buid-started timestamp, then we catched a file which
     * got changed during this build.
     *
     * @return <code>true</code> if at least one single dependency has changed.
     */
    protected boolean isDependencyChanged()
    {
        if ( session == null )
        {
            // we just cannot determine it, so don't do anything beside logging
            getLog().info( "Cannot determine build start date, skipping incremental build detection." );
            return false;
        }

        if ( fileExtensions == null || fileExtensions.isEmpty() )
        {
            fileExtensions = new ArrayList<String>();
            fileExtensions.add( ".class" );
        }

        Date buildStartTime = getBuildStartTime();

        List<String> pathElements = new ArrayList<String>();
        pathElements.addAll( getClasspathElements() );
        pathElements.addAll( getModulepathElements() );
        
        for ( String pathElement : pathElements )
        {
            // ProjectArtifacts are artifacts which are available in the local project
            // that's the only ones we are interested in now.
            File artifactPath = new File( pathElement );
            if ( artifactPath.isDirectory() )
            {
                if ( hasNewFile( artifactPath, buildStartTime ) )
                {
                    getLog().debug( "New dependency detected: " + artifactPath.getAbsolutePath() );
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
    private boolean hasNewFile( File classPathEntry, Date buildStartTime )
    {
        if ( !classPathEntry.exists() )
        {
            return false;
        }

        if ( classPathEntry.isFile() )
        {
            return classPathEntry.lastModified() >= buildStartTime.getTime()
                && fileExtensions.contains( FileUtils.getExtension( classPathEntry.getName() ) );
        }

        File[] children = classPathEntry.listFiles();

        for ( File child : children )
        {
            if ( hasNewFile( child, buildStartTime ) )
            {
                return true;
            }
        }

        return false;
    }

    private List<String> resolveProcessorPathEntries()
        throws MojoExecutionException
    {
        if ( annotationProcessorPaths == null || annotationProcessorPaths.isEmpty() )
        {
            return null;
        }

        try
        {
            Set<Artifact> requiredArtifacts = new LinkedHashSet<Artifact>();

            for ( DependencyCoordinate coord : annotationProcessorPaths )
            {
                ArtifactHandler handler = artifactHandlerManager.getArtifactHandler( coord.getType() );

                Artifact artifact = new DefaultArtifact(
                     coord.getGroupId(),
                     coord.getArtifactId(),
                     VersionRange.createFromVersionSpec( coord.getVersion() ),
                     Artifact.SCOPE_RUNTIME,
                     coord.getType(),
                     coord.getClassifier(),
                     handler,
                     false );

                requiredArtifacts.add( artifact );
            }

            ArtifactResolutionRequest request = new ArtifactResolutionRequest()
                            .setArtifact( requiredArtifacts.iterator().next() )
                            .setResolveRoot( true )
                            .setResolveTransitively( true )
                            .setArtifactDependencies( requiredArtifacts )
                            .setLocalRepository( session.getLocalRepository() )
                            .setRemoteRepositories( project.getRemoteArtifactRepositories() );

            ArtifactResolutionResult resolutionResult = repositorySystem.resolve( request );

            resolutionErrorHandler.throwErrors( request, resolutionResult );

            List<String> elements = new ArrayList<String>( resolutionResult.getArtifacts().size() );

            for ( Object resolved : resolutionResult.getArtifacts() )
            {
                elements.add( ( (Artifact) resolved ).getFile().getAbsolutePath() );
            }

            return elements;
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Resolution of annotationProcessorPath dependencies failed: "
                + e.getLocalizedMessage(), e );
        }
    }
}
