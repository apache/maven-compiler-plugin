package org.issue;

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
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.LifecyclePhase;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.shared.utils.io.FileUtils;

@Mojo( name = "read-source", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES )
public class SourcePathReadGoal
        implements org.apache.maven.api.plugin.Mojo
{

    @Parameter
    protected String sourceClass;

    @Parameter
    protected String testSourceClass;

    @Inject
    protected Project project;

    @Inject
    protected Log log;

    @Inject
    protected Session session;

    @SuppressWarnings( "unchecked" )
    public void execute()
            throws MojoException
    {
        ProjectManager projectManager = session.getService(ProjectManager.class);
        if ( sourceClass != null )
        {
            log.info( "Checking compile source roots for: '" + sourceClass + "'" );
            assertGeneratedSourceFileFor( sourceClass, projectManager.getCompileSourceRoots(project, ProjectScope.MAIN) );
        }

        if ( testSourceClass != null )
        {
            log.info( "Checking test-compile source roots for: '" + testSourceClass + "'" );
            assertGeneratedSourceFileFor( testSourceClass, projectManager.getCompileSourceRoots(project, ProjectScope.TEST) );
        }
    }

    private void assertGeneratedSourceFileFor( String sourceClass, List<Path> sourceRoots )
            throws MojoException
    {
        String sourceFile = sourceClass.replace( '.', '/' )
                .concat( ".txt" );

        boolean found = false;
        for ( Path root : sourceRoots )
        {
            File f = root.resolve( sourceFile ).toFile();
            log.info( "Looking for: " + f );
            if ( f.exists() )
            {
                try
                {
                    String[] nameParts = sourceClass.split( "\\." );
                    String content = FileUtils.fileRead( f );
                    if ( !nameParts[nameParts.length-1].equals( content ) )
                    {
                        throw new MojoException( "Non-matching content in: " + f + "\n  expected: '"
                                + sourceClass + "'\n  found: '" + content + "'" );
                    }

                    found = true;
                    break;
                }
                catch ( IOException e )
                {
                    throw new MojoException( "Cannot read contents of: " + f, e );
                }
            }
        }

        if ( !found )
        {
            throw new MojoException( "Cannot find generated source file: " + sourceFile + " in:\n  "
                    + sourceRoots.stream().map( Path::toString ).collect(Collectors.joining( "\n  " ) ) );
        }
    }

}
