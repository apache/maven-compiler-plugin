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

import static org.codehaus.plexus.util.FileUtils.copyDirectoryStructure;

import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles extra application test sources to a separate location, optionally copying the resulting classes
 * back into the main test-compile output directory. This mojo offers the advantage of using different compiler
 * arguments or even a separate source tree from that used during the main test-compilation step.
 *
 * @author jdcasey
 * @version $Id$
 * @since 2.4
 * @goal compile-test-extras
 * @phase test-compile
 * @threadSafe
 * @requiresDependencyResolution test
 */
public class CompileTestExtrasMojo
    extends AbstractCompilerMojo
{
    /**
     * Set this to 'true' to bypass unit tests entirely.
     * Its use is NOT RECOMMENDED, but quite convenient on occasion.
     *
     * @parameter expression="${maven.test.skip}"
     */
    private boolean skip;

    /**
     * The source directories containing the sources to be compiled.
     *
     * @parameter
     * @required
     */
    private List<String> sourceRoots;

    /**
     * Project classpath.
     *
     * @parameter default-value="${project.testClasspathElements}"
     * @required
     * @readonly
     */
    private List<String> classpathElements;

    /**
     * The directory for compiled classes.
     *
     * @parameter
     * @required
     */
    private File outputDirectory;

    /**
     * A list of inclusion filters for the compiler.
     *
     * @parameter
     */
    private Set<String> includes = new HashSet<String>();

    /**
     * A list of exclusion filters for the compiler.
     *
     * @parameter
     */
    private final Set<String> excludes = new HashSet<String>();

    /**
     * <p>
     * Specify where to place generated source files created by annotation processing.
     * Only applies to JDK 1.6+
     * </p>
     * @parameter default-value="${project.build.directory}/generated-sources/annotations"
     * @since 2.2
     */
    private File generatedSourcesDirectory;

    /**
     * If {@link #copyOutput} is true, the compiled contents of {@link #outputDirectory} will be 
     * copied to this location so it will be available along with the rest of the compiled test classes.
     *
     * @parameter default-value="${project.build.testOutputDirectory}"
     * @required
     * @readonly
     */
    private File testOutputDirectory;

    /**
     * If true, copy the compiled classes from the {@link #outputDirectory} to the normal test-compile 
     * output directory, given by the expression ${project.build.testOutputDirectory}.
     * 
     * @parameter default-value="true"
     */
    private boolean copyOutput;

    private List<String> consolidatedClasspath;

    @Override
    protected List<String> getCompileSourceRoots()
    {
        return sourceRoots;
    }

    @Override
    protected synchronized List<String> getClasspathElements()
    {
        if ( consolidatedClasspath == null )
        {
            consolidatedClasspath = new ArrayList<String>( classpathElements.size() + 1 );
            consolidatedClasspath.add( outputDirectory.getAbsolutePath() );
            consolidatedClasspath.addAll( classpathElements );
        }

        return consolidatedClasspath;
    }

    @Override
    protected File getOutputDirectory()
    {
        return outputDirectory;
    }

    @Override
    public void execute()
        throws MojoExecutionException, CompilationFailureException
    {
        if ( skip )
        {
            getLog().info( "Not compiling test sources" );
        }
        else
        {
            super.execute();

            if ( copyOutput )
            {
                try
                {
                    testOutputDirectory.mkdirs();
                    copyDirectoryStructure( outputDirectory, testOutputDirectory );
                }
                catch ( final IOException e )
                {
                    throw new MojoExecutionException( "Failed to copy compiled output to test output directory: "
                        + e.getMessage(), e );
                }
            }
        }
    }

    @Override
    protected SourceInclusionScanner getSourceInclusionScanner( final int staleMillis )
    {
        SourceInclusionScanner scanner = null;

        if ( includes.isEmpty() && excludes.isEmpty() )
        {
            scanner = new StaleSourceScanner( staleMillis );
        }
        else
        {
            if ( includes.isEmpty() )
            {
                includes.add( "**/*.java" );
            }
            scanner = new StaleSourceScanner( staleMillis, includes, excludes );
        }

        return scanner;
    }

    @Override
    protected SourceInclusionScanner getSourceInclusionScanner( final String inputFileEnding )
    {
        SourceInclusionScanner scanner = null;

        if ( includes.isEmpty() && excludes.isEmpty() )
        {
            includes = Collections.singleton( "**/*." + inputFileEnding );
            scanner = new SimpleSourceInclusionScanner( includes, Collections.EMPTY_SET );
        }
        else
        {
            if ( includes.isEmpty() )
            {
                includes.add( "**/*." + inputFileEnding );
            }
            scanner = new SimpleSourceInclusionScanner( includes, excludes );
        }

        return scanner;
    }

    @Override
    protected String getSource()
    {
        return source;
    }

    @Override
    protected String getTarget()
    {
        return target;
    }

    @Override
    protected String getCompilerArgument()
    {
        return compilerArgument;
    }

    @Override
    protected Map<String, String> getCompilerArguments()
    {
        return compilerArguments;
    }

    @Override
    protected File getGeneratedSourcesDirectory()
    {
        return generatedSourcesDirectory;
    }

}