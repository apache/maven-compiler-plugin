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

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
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
 * Compiles application test sources.
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @since 2.0
 */
@Mojo( name = "testCompile", defaultPhase = LifecyclePhase.TEST_COMPILE, threadSafe = true,
                requiresDependencyResolution = ResolutionScope.TEST )
public class TestCompilerMojo
    extends AbstractCompilerMojo
{
    /**
     * Set this to 'true' to bypass compilation of test sources.
     * Its use is NOT RECOMMENDED, but quite convenient on occasion.
     */
    @Parameter ( property = "maven.test.skip" )
    private boolean skip;

    /**
     * The source directories containing the test-source to be compiled.
     */
    @Parameter ( defaultValue = "${project.testCompileSourceRoots}", readonly = true, required = true )
    private List<String> compileSourceRoots;

    /**
     * The directory where compiled test classes go.
     */
    @Parameter ( defaultValue = "${project.build.testOutputDirectory}", required = true, readonly = true )
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
     * The -source argument for the test Java compiler.
     *
     * @since 2.1
     */
    @Parameter ( property = "maven.compiler.testSource" )
    private String testSource;

    /**
     * The -target argument for the test Java compiler.
     *
     * @since 2.1
     */
    @Parameter ( property = "maven.compiler.testTarget" )
    private String testTarget;

    /**
     * the -release argument for the test Java compiler
     * 
     * @since 3.6
     */
    @Parameter ( property = "maven.compiler.testRelease" )
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
    @Parameter ( defaultValue = "${project.build.directory}/generated-test-sources/test-annotations" )
    private File generatedTestSourcesDirectory;

    @Parameter( defaultValue = "${project.compileClasspathElements}", readonly = true )
    private List<String> compilePath;

    @Parameter( defaultValue = "${project.testClasspathElements}", readonly = true )
    private List<String> testPath;

    final LocationManager locationManager = new LocationManager();

    private Map<String, JavaModuleDescriptor> pathElements;
    
    private Collection<String> classpathElements;

    private Collection<String> modulepathElements;

    public void execute()
        throws MojoExecutionException, CompilationFailureException
    {
        if ( skip )
        {
            getLog().info( "Not compiling test sources" );
            return;
        }
        super.execute();
    }

    protected List<String> getCompileSourceRoots()
    {
        return compileSourceRoots;
    }

    @Override
    protected Map<String, JavaModuleDescriptor> getPathElements()
    {
        return pathElements;
    }

    protected List<String> getClasspathElements()
    {
        return new ArrayList<>( classpathElements );
    }

    @Override
    protected List<String> getModulepathElements()
    {
        return new ArrayList<>( modulepathElements );
    }

    protected File getOutputDirectory()
    {
        return outputDirectory;
    }

    @Override
    protected void preparePaths( Set<File> sourceFiles )
    {
        File mainOutputDirectory = new File( getProject().getBuild().getOutputDirectory() );

        File mainModuleDescriptorClassFile = new File( mainOutputDirectory, "module-info.class" );
        JavaModuleDescriptor mainModuleDescriptor = null;

        File testModuleDescriptorJavaFile = new File( "module-info.java" );
        JavaModuleDescriptor testModuleDescriptor = null;

        // Go through the source files to respect includes/excludes
        for ( File sourceFile : sourceFiles )
        {
            // @todo verify if it is the root of a sourcedirectory?
            if ( "module-info.java".equals( sourceFile.getName() ) ) 
            {
                testModuleDescriptorJavaFile = sourceFile;
                break;
            }
        }

        // Get additional information from the main module descriptor, if available
        if ( mainModuleDescriptorClassFile.exists() )
        {
            ResolvePathsResult<String> result;

            try
            {
                ResolvePathsRequest<String> request =
                        ResolvePathsRequest.ofStrings( testPath )
                                .setMainModuleDescriptor( mainModuleDescriptorClassFile.getAbsolutePath() );

                Toolchain toolchain = getToolchain();
                if ( toolchain instanceof DefaultJavaToolChain )
                {
                    request.setJdkHome( ( (DefaultJavaToolChain) toolchain ).getJavaHome() );
                }

                result = locationManager.resolvePaths( request );
                
                for ( Entry<String, Exception> pathException : result.getPathExceptions().entrySet() )
                {
                    Throwable cause = pathException.getValue().getCause();
                    while ( cause.getCause() != null )
                    {
                        cause = cause.getCause();
                    }
                    String fileName = Paths.get( pathException.getKey() ).getFileName().toString();
                    getLog().warn( "Can't extract module name from " + fileName + ": " + cause.getMessage() );
                }
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }

            mainModuleDescriptor = result.getMainModuleDescriptor();

            pathElements = new LinkedHashMap<>( result.getPathElements().size() );
            pathElements.putAll( result.getPathElements() );

            modulepathElements = result.getModulepathElements().keySet();
            classpathElements = result.getClasspathElements();
        }

        // Get additional information from the test module descriptor, if available
        if ( testModuleDescriptorJavaFile.exists() )
        {
            ResolvePathsResult<String> result;

            try
            {
                ResolvePathsRequest<String> request =
                        ResolvePathsRequest.ofStrings( testPath )
                                .setMainModuleDescriptor( testModuleDescriptorJavaFile.getAbsolutePath() );

                Toolchain toolchain = getToolchain();
                if ( toolchain instanceof DefaultJavaToolChain )
                {
                    request.setJdkHome( ( (DefaultJavaToolChain) toolchain ).getJavaHome() );
                }

                result = locationManager.resolvePaths( request );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }

            testModuleDescriptor = result.getMainModuleDescriptor();
        }

        if ( release != null )
        {
            if ( Integer.valueOf( release ) < 9 )
            {
                pathElements = Collections.emptyMap();
                modulepathElements = Collections.emptyList();
                classpathElements = testPath;
                return;
            }
        }
        else if ( Double.valueOf( getTarget() ) < Double.valueOf( MODULE_INFO_TARGET ) )
        {
            pathElements = Collections.emptyMap();
            modulepathElements = Collections.emptyList();
            classpathElements = testPath;
            return;
        }
            
        if ( testModuleDescriptor != null )
        {
            modulepathElements = testPath;
            classpathElements = Collections.emptyList();

            if ( mainModuleDescriptor != null )
            {
                if ( getLog().isDebugEnabled() )
                {
                    getLog().debug( "Main and test module descriptors exist:" );
                    getLog().debug( "  main module = " + mainModuleDescriptor.name() );
                    getLog().debug( "  test module = " + testModuleDescriptor.name() );
                }

                if ( testModuleDescriptor.name().equals( mainModuleDescriptor.name() ) )
                {
                    if ( compilerArgs == null )
                    {
                        compilerArgs = new ArrayList<>();
                    }
                    compilerArgs.add( "--patch-module" );

                    StringBuilder patchModuleValue = new StringBuilder();
                    patchModuleValue.append( testModuleDescriptor.name() );
                    patchModuleValue.append( '=' );

                    for ( String root : getProject().getCompileSourceRoots() )
                    {
                        if ( Files.exists( Paths.get( root ) ) )
                        {
                            patchModuleValue.append( root ).append( PS );
                        }
                    }

                    compilerArgs.add( patchModuleValue.toString() );
                }
                else
                {
                    getLog().debug( "Black-box testing - all is ready to compile" );
                }
            }
            else
            {
                // No main binaries available? Means we're a test-only project.
                if ( !mainOutputDirectory.exists() )
                {
                    return;
                }
                // very odd
                // Means that main sources must be compiled with -modulesource and -Xmodule:<moduleName>
                // However, this has a huge impact since you can't simply use it as a classpathEntry 
                // due to extra folder in between
                throw new UnsupportedOperationException( "Can't compile test sources "
                    + "when main sources are missing a module descriptor" );
            }
        }
        else
        {
            if ( mainModuleDescriptor != null )
            {
                if ( compilerArgs == null )
                {
                    compilerArgs = new ArrayList<>();
                }

                // Patch module to add 
                LinkedHashMap<String, List<String>> patchModules = new LinkedHashMap<>();

                List<String> mainModulePaths = new ArrayList<>();
                // Add main output dir
                mainModulePaths.add( mainOutputDirectory.getAbsolutePath() );
                // Add source roots
                mainModulePaths.addAll( compileSourceRoots );
                patchModules.put( mainModuleDescriptor.name(), mainModulePaths );
                
                // Main module detected, modularized test dependencies must augment patch modules
                patchModulesForTestDependencies( 
                    mainModuleDescriptor.name(), mainOutputDirectory.getAbsolutePath(),
                    patchModules 
                );

                for ( Entry<String, List<String>> patchModuleEntry: patchModules.entrySet() ) 
                {
                    compilerArgs.add( "--patch-module" );
                    StringBuilder patchModuleValue = new StringBuilder( patchModuleEntry.getKey() )
                                    .append( '=' );
                    
                    for ( String path : patchModuleEntry.getValue() )
                    {
                        patchModuleValue.append( path ).append( PS );
                    }
                    
                    compilerArgs.add( patchModuleValue.toString() );
                }
                
                compilerArgs.add( "--add-reads" );
                compilerArgs.add( mainModuleDescriptor.name() + "=ALL-UNNAMED" );
                
            }
            else
            {
                modulepathElements = Collections.emptyList();
                classpathElements = testPath;
            }

        }
    }

    /**
     * Compiling with test classpath is not sufficient because some dependencies <br/>
     * may be modularized and their test class path addition would be ignored.<p/>
     * Example from MCOMPILER-372_modularized_test:<br/>
     * prj2 test classes depend on prj1 classes and test classes<br/>
     * As prj1.jar is added as a module, pjr1-tests.jar classes are ignored if we add jar class path<br/>
     * We have to add pjr1-tests.jar as --patch-module<p/>
     * @param mainModuleName
     * param mainModuleTarget
     * @param patchModules map of module names -&gt; list of paths to add to --patch-module option
     * 
     */
    protected void patchModulesForTestDependencies( 
        String mainModuleName, String mainModuleTarget,
        LinkedHashMap<String, List<String>> patchModules 
    )
    {
     
        File mainModuleDescriptorClassFile = new File( mainModuleTarget, "module-info.class" );

        ResolvePathsResult<File> result;
        
        try
        {
            // Get compile path information to identify modularized compile path elements
            Collection<File> dependencyArtifacts = getCompileClasspathElements( getProject() );
            
            ResolvePathsRequest<File> request =
                ResolvePathsRequest.ofFiles( dependencyArtifacts )
                .setMainModuleDescriptor( mainModuleDescriptorClassFile );

            Toolchain toolchain = getToolchain();
            if ( toolchain instanceof DefaultJavaToolChain )
            {
                request.setJdkHome( new File( ( (DefaultJavaToolChain) toolchain ).getJavaHome() ) );
            }

            result = locationManager.resolvePaths( request );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
        
        // Prepare a path list containing test paths for modularized paths
        // This path list will augment modules to be able to compile
        List<String> listModuleTestPaths = new ArrayList<>( testPath.size() );
        
        // Browse modules
        for ( Entry<File, ModuleNameSource> pathElt : result.getModulepathElements().entrySet() )
        {
            
            File modulePathElt = pathElt.getKey();

            // Get module name
            JavaModuleDescriptor moduleDesc = result.getPathElements().get( modulePathElt );
            String moduleName = ( moduleDesc != null ) ? moduleDesc.name() : null;
            
            if ( 
                  // Is it a modularized compile elements?
                  ( modulePathElt != null ) && ( moduleName != null )
                  &&
                  // Is it different from main module name
                  !mainModuleName.equals( moduleName )
            ) 
            {
                
                // Get test path element
                File moduleTestPathElt = getModuleTestPathElt( modulePathElt );
                
                if ( moduleTestPathElt != null ) 
                {
                    listModuleTestPaths.add( moduleTestPathElt.getAbsolutePath() );
                }
                
            }
            
        }
        
        // Remove main target path
        listModuleTestPaths.remove( mainModuleTarget );
        listModuleTestPaths.remove( outputDirectory.getAbsolutePath() );
        
        // Freeze list
        listModuleTestPaths = Collections.unmodifiableList( listModuleTestPaths );
        
        if ( getLog().isDebugEnabled() ) 
        {
            getLog().debug( "patchModule test paths:" );
            for ( String moduleTestPath : listModuleTestPaths ) 
            {
                getLog().debug( "  " + moduleTestPath );
            }
            
        }
        
        // Get modularized dependencies resolved before
        for ( Entry<File, ModuleNameSource> pathElt : result.getModulepathElements().entrySet() ) 
        {
            
            File path = pathElt.getKey();
            ModuleNameSource moduleNameSource = pathElt.getValue();
            
            // Get module name
            JavaModuleDescriptor moduleDesc = result.getPathElements().get( path );
            String moduleName = ( moduleDesc != null ) ? moduleDesc.name() : null;
            
            if ( 
                  // Is it a modularized compile elements?
                  ( path != null ) && ( moduleName != null )
                  &&
                  // Not an auto-module
                  !moduleDesc.isAutomatic()
                  &&
                  // Is it different from main module name
                  !mainModuleName.equals( moduleName )
            ) 
            {
            
                // Add --add-reads <moduleName>=ALL-UNNAMED
                compilerArgs.add( "--add-reads" );
                StringBuilder sbAddReads = new StringBuilder();
                sbAddReads.append( moduleName ).append( "=ALL-UNNAMED" );
                compilerArgs.add( sbAddReads.toString() );
                
                // Add compile classpath if needed
                if ( !listModuleTestPaths.isEmpty() )
                {
                    // Yes, add it as patch module
                    List<String> listPath = patchModules.get( moduleName );
                    // Make sure it is initialized
                    if ( listPath == null ) 
                    {
                        listPath = new ArrayList<>();
                        patchModules.put( moduleName, listPath );
                    } 
                    
                    // Add test compile path but not main module target
                    listPath.addAll( listModuleTestPaths );
                }
                
            }
            
        }

        if ( pathElements == null ) 
        {
            pathElements = new LinkedHashMap<>( result.getPathElements().size() );
            for ( Entry<File, JavaModuleDescriptor> entry : result.getPathElements().entrySet() ) 
            {
                pathElements.put( entry.getKey().getAbsolutePath(), entry.getValue() );
            }
        }
    }

    /**
     * Get module test path element from module path element
     * @param modulePathElt
     * @return
     */
    private File getModuleTestPathElt( File modulePathElt )
    {

        File result = null;
        
        // Get parent, base name and extension
        File parentFile = modulePathElt.getParentFile();
        // Get base name
        String baseName = FilenameUtils.getBaseName( modulePathElt.getName() );
        
        // For test directory
        if ( modulePathElt.isDirectory() )
        {    
            
            // Already a test path?
            if ( baseName.startsWith( "test-" ) ) 
            {
                // Sorry, no test path for test path
                return null;
            }
            
            // Build new name
            String testName = "test-" + modulePathElt.getName();
            result = new File( parentFile, testName );
            
        }
        else
        {
        
            // Already a test path?
            if ( modulePathElt.isFile() && ( baseName.endsWith( "-tests" ) ) ) 
            {
                // Sorry, no test path for test path
                return null;
            }
            
            // Build new name
            String testName = baseName + "-tests." + FilenameUtils.getExtension( modulePathElt.getName() );
            result = new File( parentFile, testName );

        }
        
        // Last check : result (directory or file) exists?
        if ( ( result != null ) && !result.exists() ) 
        {
            result = null;
        }
        
        return result;
    }

    protected SourceInclusionScanner getSourceInclusionScanner( int staleMillis )
    {
        SourceInclusionScanner scanner;

        if ( testIncludes.isEmpty() && testExcludes.isEmpty() )
        {
            scanner = new StaleSourceScanner( staleMillis );
        }
        else
        {
            if ( testIncludes.isEmpty() )
            {
                testIncludes.add( "**/*.java" );
            }
            scanner = new StaleSourceScanner( staleMillis, testIncludes, testExcludes );
        }

        return scanner;
    }

    protected SourceInclusionScanner getSourceInclusionScanner( String inputFileEnding )
    {
        SourceInclusionScanner scanner;

        // it's not defined if we get the ending with or without the dot '.'
        String defaultIncludePattern = "**/*" + ( inputFileEnding.startsWith( "." ) ? "" : "." ) + inputFileEnding;

        if ( testIncludes.isEmpty() && testExcludes.isEmpty() )
        {
            testIncludes = Collections.singleton( defaultIncludePattern );
            scanner = new SimpleSourceInclusionScanner( testIncludes, Collections.<String>emptySet() );
        }
        else
        {
            if ( testIncludes.isEmpty() )
            {
                testIncludes.add( defaultIncludePattern );
            }
            scanner = new SimpleSourceInclusionScanner( testIncludes, testExcludes );
        }

        return scanner;
    }

    protected String getSource()
    {
        return testSource == null ? source : testSource;
    }

    protected String getTarget()
    {
        return testTarget == null ? target : testTarget;
    }
    
    @Override
    protected String getRelease()
    {
        return testRelease == null ? release : testRelease;
    }

    protected String getCompilerArgument()
    {
        return testCompilerArgument == null ? compilerArgument : testCompilerArgument;
    }

    protected Map<String, String> getCompilerArguments()
    {
        return testCompilerArguments == null ? compilerArguments : testCompilerArguments;
    }

    protected File getGeneratedSourcesDirectory()
    {
        return generatedTestSourcesDirectory;
    }

    @Override
    protected boolean isTestCompile()
    {
        return true;
    }

    private List<File> getCompileClasspathElements( MavenProject project )
    {
        // 3 is outputFolder + 2 preserved for multirelease  
        List<File> list = new ArrayList<>( project.getArtifacts().size() + 3 );

        list.add( new File( project.getBuild().getOutputDirectory() ) );

        for ( Artifact a : project.getArtifacts() )
        {
            list.add( a.getFile() );
        }
        return list;
    }
    

}
