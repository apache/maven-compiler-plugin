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
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;

import java.io.File;
import java.util.Collection;
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
 */
@org.apache.maven.plugins.annotations.Mojo( name = "compile", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true,
                                            requiresDependencyResolution = ResolutionScope.COMPILE )
public class CompilerMojo
    extends AbstractCompilerMojo
{
    /**
     * The source directories containing the sources to be compiled.
     */
    @Parameter( defaultValue = "${project.compileSourceRoots}", readonly = true, required = true )
    private List<String> compileSourceRoots;

    /**
     * Project classpath.
     */
    @Parameter( defaultValue = "${project.compileClasspathElements}", readonly = true, required = true )
    private List<String> classpathElements;

    /**
     * The directory for compiled classes.
     */
    @Parameter( defaultValue = "${project.build.outputDirectory}", required = true, readonly = true )
    private File outputDirectory;

    /**
     * Projects main artifact.
     *
     * @todo this is an export variable, really
     */
    @Parameter( defaultValue = "${project.artifact}", readonly = true, required = true )
    private Artifact projectArtifact;

    /**
     * We need all the projects artifacts to determine whether we shall force a re-compile.
     */
    @Parameter( defaultValue = "${project.artifacts}", readonly = true, required = true )
    private Set<Artifact> projectArtifacts;

    /**
     * A list of inclusion filters for the compiler.
     */
    @Parameter
    private Set<String> includes = new HashSet<String>();

    /**
     * A list of exclusion filters for the compiler.
     */
    @Parameter
    private Set<String> excludes = new HashSet<String>();

    /**
     * <p>
     * Specify where to place generated source files created by annotation processing.
     * Only applies to JDK 1.6+
     * </p>
     *
     * @since 2.2
     */
    @Parameter( defaultValue = "${project.build.directory}/generated-sources/annotations" )
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

    @Override
    protected Collection<Artifact> getArtifacts()
    {
        return projectArtifacts;
    }

    protected SourceInclusionScanner getSourceInclusionScanner( String inputFileEnding )
    {
        SourceInclusionScanner scanner = null;

        if ( includes.isEmpty() && excludes.isEmpty() )
        {
            String includePattern = "**/*" + ( inputFileEnding.startsWith( "." ) ? "" : "." ) + inputFileEnding;
            includes = Collections.singleton( includePattern );
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
