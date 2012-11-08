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

import org.codehaus.plexus.compiler.CompilerMessage;

import java.util.List;

/**
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @version $Id$
 * @since 2.0
 */
@SuppressWarnings( "serial" )
public class CompilationFailureException
    extends MojoFailureException
{
    private static final String LS = System.getProperty( "line.separator" );

    public CompilationFailureException( List<CompilerMessage> messages )
    {
        super( null, shortMessage( messages ), longMessage( messages ) );
    }

    public static String longMessage( List<CompilerMessage> messages )
    {
        StringBuilder sb = new StringBuilder();

        if ( messages != null )
        {
            for ( CompilerMessage compilerError : messages )
            {
                sb.append( compilerError ).append( LS );
            }
        }
        return sb.toString();
    }

    /**
     * Short message will have the error message if there's only one, useful for errors forking the compiler
     *
     * @param messages
     * @return the short error message
     * @since 2.0.2
     */
    public static String shortMessage( List<CompilerMessage> messages )
    {
        StringBuilder sb = new StringBuilder();

        sb.append( "Compilation failure" );

        if ( messages.size() == 1 )
        {
            sb.append( LS );

            CompilerMessage compilerError = messages.get( 0 );

            sb.append( compilerError ).append( LS );
        }
        
        return sb.toString();
    }
}
