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

import org.codehaus.plexus.compiler.CompilerError;

import java.util.Iterator;
import java.util.List;

/**
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @version $Id$
 */
public class CompilationFailureException
    extends MojoFailureException
{
    private static final String LS = System.getProperty( "line.separator" );

    public CompilationFailureException( List messages )
    {
        super( null, shortMessage( messages ), longMessage( messages ) );
    }

    public static String longMessage( List messages )
    {
        StringBuffer sb = new StringBuffer();

        for ( Iterator it = messages.iterator(); it.hasNext(); )
        {
            CompilerError compilerError = (CompilerError) it.next();

            sb.append( compilerError ).append( LS );
        }
        return sb.toString();
    }

    /**
     * Short message will have the error message if there's only one, useful for errors forking the compiler
     *
     * @param messages
     * @return the short error message
     */
    public static String shortMessage( List messages )
    {
        StringBuffer sb = new StringBuffer();

        sb.append( "Compilation failure" );

        if ( messages.size() == 1 )
        {
            sb.append( LS );

            CompilerError compilerError = (CompilerError) messages.get( 0 );

            sb.append( compilerError ).append( LS );
        }
        return sb.toString();
    }
}
