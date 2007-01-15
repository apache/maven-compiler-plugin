package org.apache.maven.plugin.stubs;

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

import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerError;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerOutputStyle;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author Edwin Punzalan
 */
public class CompilerStub
    implements org.codehaus.plexus.compiler.Compiler
{
    private boolean shouldFail;

    public CompilerStub()
    {
        this( false );
    }

    public CompilerStub( boolean shouldFail )
    {
        this.shouldFail = shouldFail;
    }

    public CompilerOutputStyle getCompilerOutputStyle()
    {
        return CompilerOutputStyle.ONE_OUTPUT_FILE_FOR_ALL_INPUT_FILES;
    }

    public String getInputFileEnding( CompilerConfiguration compilerConfiguration )
        throws CompilerException
    {
        return "java";
    }

    public String getOutputFileEnding( CompilerConfiguration compilerConfiguration )
        throws CompilerException
    {
        return "class";
    }

    public String getOutputFile( CompilerConfiguration compilerConfiguration )
        throws CompilerException
    {
        return "output-file";
    }

    public boolean canUpdateTarget( CompilerConfiguration compilerConfiguration )
        throws CompilerException
    {
        return false;
    }

    public List compile( CompilerConfiguration compilerConfiguration )
        throws CompilerException
    {
        File outputDir = new File( compilerConfiguration.getOutputLocation() );

        try
        {
            outputDir.mkdirs();

            File outputFile = new File( outputDir, "compiled.class" );
            if ( !outputFile.exists() && !outputFile.createNewFile() )
            {
                throw new CompilerException( "could not create output file: " + outputFile.getAbsolutePath() );
            }
        }
        catch ( IOException e )
        {
            throw new CompilerException( "An exception occurred while creating output file", e );
        }

        return Collections.singletonList( new CompilerError( "message 1", shouldFail ) );
    }

    public String[] createCommandLine( CompilerConfiguration compilerConfiguration )
        throws CompilerException
    {
        return new String[0];
    }
}
