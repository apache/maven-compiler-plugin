package org.apache.maven.plugin;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerError;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SingleTargetSourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;

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
    // ----------------------------------------------------------------------
    // Configurables
    // ----------------------------------------------------------------------

    /**
     * Indicates whether the build will continue even if there are compilation errors; defaults to true.
     *
     * @parameter expression="${maven.compiler.failOnError}" default-value="true"
     * @since 2.0.2
     */
    private boolean failOnError = true;

    /**
     * Set to true to include debugging information in the compiled class files.
     *
     * @parameter expression="${maven.compiler.debug}" default-value="true"
     */
    private boolean debug = true;

    /**
     * Set to true to show messages about what the compiler is doing.
     *
     * @parameter expression="${maven.compiler.verbose}" default-value="false"
     */
    private boolean verbose;

    /**
     * Sets whether to show source locations where deprecated APIs are used.
     *
     * @parameter expression="${maven.compiler.showDeprecation}" default-value="false"
     */
    private boolean showDeprecation;

    /**
     * Set to true to optimize the compiled code using the compiler's optimization methods.
     *
     * @parameter expression="${maven.compiler.optimize}" default-value="false"
     */
    private boolean optimize;

    /**
     * Set to true to show compilation warnings.
     *
     * @parameter expression="${maven.compiler.showWarnings}" default-value="false"
     */
    private boolean showWarnings;

    /**
     * The -source argument for the Java compiler.
     *
     * @parameter expression="${maven.compiler.source}" default-value="1.5"
     */
    protected String source;

    /**
     * The -target argument for the Java compiler.
     *
     * @parameter expression="${maven.compiler.target}" default-value="1.5"
     */
    protected String target;

    /**
     * The -encoding argument for the Java compiler.
     *
     * @parameter expression="${encoding}" default-value="${project.build.sourceEncoding}"
     */
    private String encoding;

    /**
     * Sets the granularity in milliseconds of the last modification
     * date for testing whether a source needs recompilation.
     *
     * @parameter expression="${lastModGranularityMs}" default-value="0"
     */
    private int staleMillis;

    /**
     * The compiler id of the compiler to use. See this
     * <a href="non-javac-compilers.html">guide</a> for more information.
     *
     * @parameter expression="${maven.compiler.compilerId}" default-value="javac"
     */
    private String compilerId;

    /**
     * Version of the compiler to use, ex. "1.3", "1.5", if fork is set to true.
     *
     * @parameter expression="${maven.compiler.compilerVersion}"
     */
    private String compilerVersion;

    /**
     * Allows running the compiler in a separate process.
     * If "false" it uses the built in compiler, while if "true" it will use an executable.
     *
     * @parameter expression="${maven.compiler.fork}" default-value="false"
     */
    private boolean fork;

    /**
     * Initial size, in megabytes, of the memory allocation pool, ex. "64", "64m"
     * if fork is set to true.
     *
     * @parameter expression="${maven.compiler.meminitial}"
     * @since 2.0.1
     */
    private String meminitial;

    /**
     * Sets the maximum size, in megabytes, of the memory allocation pool, ex. "128", "128m"
     * if fork is set to true.
     *
     * @parameter expression="${maven.compiler.maxmem}"
     * @since 2.0.1
     */
    private String maxmem;

    /**
     * Sets the executable of the compiler to use when fork is true.
     *
     * @parameter expression="${maven.compiler.executable}"
     */
    private String executable;

    /**
     * <p>
     * Sets whether annotation processing is performed or not. Only applies to JDK 1.6+
     * If not set, both compilation and annotation processing are performed at the same time.
     * </p>
     * <p>
     * Allowed values are:
     *    none - no annotation processing is performed.
     *    only - only annotation processing is done, no compilation.
     * </p>
     *
     * @parameter
     * @since 2.2
     */
    private String proc;

    /**
     * <p>
     *  Names of annotation processors to run. Only applies to JDK 1.6+
     * If not set, the default annotation processors discovery process applies.
     * </p>
     *
     * @parameter
     * @since 2.2
     */
    private String[] annotationProcessors;

    /**
     * <p>
     * Sets the arguments to be passed to the compiler (prepending a dash) if fork is set to true.
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler
     * varies based on the compiler version.
     * </p>
     *
     * @parameter
     * @since 2.0.1
     */
    protected Map<String, String> compilerArguments;

    /**
     * <p>
     * Sets the unformatted argument string to be passed to the compiler if fork is set to true.
     * </p>
     * <p>
     * This is because the list of valid arguments passed to a Java compiler
     * varies based on the compiler version.
     * </p>
     *
     * @parameter
     */
    protected String compilerArgument;

    /**
     * Sets the name of the output file when compiling a set of
     * sources to a single file.
     *
     * @parameter expression="${project.build.finalName}"
     */
    private String outputFileName;
    
    /**
     * Keyword list to be appended to the -g  command-line switch. Legal values are none or a comma-separated list of the following keywords: lines, vars, and source.
     * If debuglevel is not specified, by default, nothing will be appended to -g. If debug is not turned on, this attribute will be ignored.
     *
     * @parameter expression="${maven.compiler.debuglevel}"
     * @since 2.1
     */
    private String debuglevel;    

    /** @component */
    private ToolchainManager toolchainManager;
    
    // ----------------------------------------------------------------------
    // Read-only parameters
    // ----------------------------------------------------------------------

    /**
     * The directory to run the compiler from if fork is true.
     *
     * @parameter default-value="${basedir}"
     * @required
     * @readonly
     */
    private File basedir;

    /**
     * The target directory of the compiler if fork is true.
     *
     * @parameter default-value="${project.build.directory}"
     * @required
     * @readonly
     */
    private File buildDirectory;

    /**
     * Plexus compiler manager.
     *
     * @component
     */
    private CompilerManager compilerManager;
    
    /**
     * The current build session instance. This is used for
     * toolchain manager API calls.
     *
     * @parameter default-value="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    protected abstract SourceInclusionScanner getSourceInclusionScanner( int staleMillis );

    protected abstract SourceInclusionScanner getSourceInclusionScanner( String inputFileEnding );

    protected abstract List<String> getClasspathElements();

    protected abstract List<String> getCompileSourceRoots();

    protected abstract File getOutputDirectory();
    
    protected abstract String getSource();
    
    protected abstract String getTarget();
    
    protected abstract String getCompilerArgument();
    
    protected abstract Map<String, String> getCompilerArguments();

    protected abstract File getGeneratedSourcesDirectory();

    @SuppressWarnings( "unchecked" )
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
            getLog().info( "Toolchain in compiler-plugin: " + tc );
            if ( executable  != null ) 
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

        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "Source directories: " + compileSourceRoots.toString().replace( ',', '\n' ) );
            getLog().debug( "Classpath: " + getClasspathElements().toString().replace( ',', '\n' ) );
            getLog().debug( "Output directory: " + getOutputDirectory() );
        }

        // ----------------------------------------------------------------------
        // Create the compiler configuration
        // ----------------------------------------------------------------------

        CompilerConfiguration compilerConfiguration = new CompilerConfiguration();

        compilerConfiguration.setOutputLocation( getOutputDirectory().getAbsolutePath() );

        compilerConfiguration.setClasspathEntries( getClasspathElements() );

        compilerConfiguration.setSourceLocations( compileSourceRoots );

        compilerConfiguration.setOptimize( optimize );

        compilerConfiguration.setDebug( debug );

        if ( debug && StringUtils.isNotEmpty( debuglevel ) )
        {
            String[] split = StringUtils.split( debuglevel, "," );
            for ( int i = 0; i < split.length; i++ )
            {
                if ( !( split[i].equalsIgnoreCase( "none" ) || split[i].equalsIgnoreCase( "lines" )
                    || split[i].equalsIgnoreCase( "vars" ) || split[i].equalsIgnoreCase( "source" ) ) )
                {
                    throw new IllegalArgumentException( "The specified debug level: '" + split[i]
                        + "' is unsupported. " + "Legal values are 'none', 'lines', 'vars', and 'source'." );
                }
            }
            compilerConfiguration.setDebugLevel( debuglevel );
        }        
        
        compilerConfiguration.setVerbose( verbose );

        compilerConfiguration.setShowWarnings( showWarnings );

        compilerConfiguration.setShowDeprecation( showDeprecation );

        compilerConfiguration.setSourceVersion( getSource() );

        compilerConfiguration.setTargetVersion( getTarget() );

        compilerConfiguration.setProc( proc );

        compilerConfiguration.setGeneratedSourcesDirectory( getGeneratedSourcesDirectory() );

        compilerConfiguration.setAnnotationProcessors( annotationProcessors );

        compilerConfiguration.setSourceEncoding( encoding );
        
        Map<String, String> effectiveCompilerArguments = getCompilerArguments();

        String effectiveCompilerArgument = getCompilerArgument();

        if ( ( effectiveCompilerArguments != null ) || ( effectiveCompilerArgument != null ) )
        {
            LinkedHashMap<String, String> cplrArgsCopy = new LinkedHashMap<String, String>();
            if ( effectiveCompilerArguments != null )
            {
                for ( Map.Entry<String, String> me : effectiveCompilerArguments.entrySet() )
                {
                    String key = (String) me.getKey();
                    String value = (String) me.getValue();
                    if ( !key.startsWith( "-" ) )
                    {
                        key = "-" + key;
                    }
                    cplrArgsCopy.put( key, value );
                }
            }
            if ( !StringUtils.isEmpty( effectiveCompilerArgument ) )
            {
                cplrArgsCopy.put( effectiveCompilerArgument, null );
            }
            compilerConfiguration.setCustomCompilerArguments( cplrArgsCopy );
        }

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

        // TODO: have an option to always compile (without need to clean)
        Set<File> staleSources;

        boolean canUpdateTarget;

        try
        {
            staleSources =
                computeStaleSources( compilerConfiguration, compiler, getSourceInclusionScanner( staleMillis ) );

            canUpdateTarget = compiler.canUpdateTarget( compilerConfiguration );

            if ( compiler.getCompilerOutputStyle().equals( CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES ) &&
                !canUpdateTarget )
            {
                getLog().info( "RESCANNING!" );
                // TODO: This second scan for source files is sub-optimal
                String inputFileEnding = compiler.getInputFileEnding( compilerConfiguration );

                Set<File> sources = computeStaleSources( compilerConfiguration, compiler,
                                                   getSourceInclusionScanner( inputFileEnding ) );

                compilerConfiguration.setSourceFiles( sources );
            }
            else
            {
                compilerConfiguration.setSourceFiles( staleSources );
            }
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
                if ( cl != null && cl.length > 0 )
                {
                    StringBuffer sb = new StringBuffer();
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
            getLog().warn(
                           "File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
                               + ", i.e. build is platform dependent!" );
        }

        List<CompilerError> messages;

        try
        {
            messages = compiler.compile( compilerConfiguration );
        }
        catch ( Exception e )
        {
            // TODO: don't catch Exception
            throw new MojoExecutionException( "Fatal error compiling", e );
        }

        List<CompilerError> warnings = new ArrayList<CompilerError>();
        List<CompilerError> errors = new ArrayList<CompilerError>();
        if ( messages != null )
        {
            for ( CompilerError message : messages )
            {
                if ( message.isError() )
                {
                    errors.add( message );
                }
                else
                {
                    warnings.add( message );
                }
            }
        }

        if ( failOnError && !errors.isEmpty() )
        {
            if ( !warnings.isEmpty() )
            {
                getLog().info( "-------------------------------------------------------------" );
                getLog().warn( "COMPILATION WARNING : " );
                getLog().info( "-------------------------------------------------------------" );
                for ( CompilerError warning : warnings )
                {
                    getLog().warn( warning.toString() );
                }
                getLog().info( warnings.size() + ( ( warnings.size() > 1 ) ? " warnings " : "warning" ) );
                getLog().info( "-------------------------------------------------------------" );
            }
            
            getLog().info( "-------------------------------------------------------------" );
            getLog().error( "COMPILATION ERROR : " );
            getLog().info( "-------------------------------------------------------------" );
            
            for ( CompilerError error : errors )
            {
                    getLog().error( error.toString() );
            }
            getLog().info( errors.size() + ( ( errors.size() > 1 ) ? " errors " : "error" ) );
            getLog().info( "-------------------------------------------------------------" );
            
            throw new CompilationFailureException( errors );
        }
        else
        {
            for ( CompilerError message : messages )
            {
                getLog().warn( message.toString() );
            }
        }
    }

    private String getMemoryValue( String setting )
    {
        String value = null;

        // Allow '128' or '128m'
        if ( isDigits( setting ) )
        {
            value = setting + "m";
        }
        else
        {
            if ( ( isDigits( setting.substring( 0, setting.length() - 1 ) ) ) &&
                ( setting.toLowerCase().endsWith( "m" ) ) )
            {
                value = setting;
            }
        }
        return value;
    }

    //TODO remove the part with ToolchainManager lookup once we depend on
    //3.0.9 (have it as prerequisite). Define as regular component field then.
    private Toolchain getToolchain()
    {
        Toolchain tc = null;
        if ( toolchainManager != null )
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

    @SuppressWarnings( "unchecked" )
    private Set<File> computeStaleSources( CompilerConfiguration compilerConfiguration, Compiler compiler,
                                     SourceInclusionScanner scanner )
        throws MojoExecutionException, CompilerException
    {
        CompilerOutputStyle outputStyle = compiler.getCompilerOutputStyle();

        SourceMapping mapping;

        File outputDirectory;

        if ( outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE )
        {
            mapping = new SuffixMapping( compiler.getInputFileEnding( compilerConfiguration ), compiler
                .getOutputFileEnding( compilerConfiguration ) );

            outputDirectory = getOutputDirectory();
        }
        else if ( outputStyle == CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES )
        {
            mapping = new SingleTargetSourceMapping( compiler.getInputFileEnding( compilerConfiguration ), compiler
                .getOutputFile( compilerConfiguration ) );

            outputDirectory = buildDirectory;
        }
        else
        {
            throw new MojoExecutionException( "Unknown compiler output style: '" + outputStyle + "'." );
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
                    "Error scanning source root: \'" + sourceRoot + "\' " + "for stale files to recompile.", e );
            }
        }

        return staleSources;
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
}
