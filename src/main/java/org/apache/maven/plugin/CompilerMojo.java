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

import org.apache.maven.artifact.Artifact;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles application sources
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl </a>
 * @version $Id$
 * @since 2.0
 * @goal compile
 * @phase compile
 * @threadSafe
 * @requiresDependencyResolution compile
 */
public class CompilerMojo
    extends AbstractCompilerMojo
{
    /**
     * The source directories containing the sources to be compiled.
     *
     * @parameter default-value="${project.compileSourceRoots}"
     * @required
     * @readonly
     */
    private List<String> compileSourceRoots;

    /**
     * Project classpath.
     *
     * @parameter default-value="${project.compileClasspathElements}"
     * @required
     * @readonly
     */
    private List<String> classpathElements;

    /**
     * The directory for compiled classes.
     *
     * @parameter default-value="${project.build.outputDirectory}"
     * @required
     * @readonly
     */
    private File outputDirectory;

    /**
     * Project artifacts.
     *
     * @parameter default-value="${project.artifact}"
     * @required
     * @readonly
     * @todo this is an export variable, really
     */
    private Artifact projectArtifact;

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
    private Set<String> excludes = new HashSet<String>();

    /**
     * <p>
     * Specify where to place generated source files created by annotation processing.
     * Only applies to JDK 1.6+
     * </p>
     * @parameter default-value="${project.build.directory}/generated-sources/annotations"
     * @since 2.2
     */
    private File generatedSourcesDirectory;


    protected List<String> getCompileSourceRoots()
    {
        return compileSourceRoots;
    }

    protected List<String> getClasspathElements()
    {
        return classpathElements;
    }

    protected File getOutputDirectory()
    {
        return outputDirectory;
    }

    public void execute()
        throws MojoExecutionException, CompilationFailureException
    {
        super.execute();

        if ( outputDirectory.isDirectory() )
        {
            projectArtifact.setFile( outputDirectory );
        }
    }

    protected SourceInclusionScanner getSourceInclusionScanner( int staleMillis )
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

    protected SourceInclusionScanner getSourceInclusionScanner( String inputFileEnding )
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

    protected String getSource()
    {
      return source;
    }

    protected String getTarget()
    {
      return target;
    }

    protected String getCompilerArgument()
    {
      return compilerArgument;
    }

    protected Map<String, String> getCompilerArguments()
    {
      return compilerArguments;
    }

    protected File getGeneratedSourcesDirectory()
    {
        return generatedSourcesDirectory;
    }

}