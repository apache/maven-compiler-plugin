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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;

/**
 * Extract information from module with ASM
 * 
 * @author Robert Scholte
 * @since 3.5
 */
public class AsmModuleInfoParser implements ModuleInfoParser
{
    @Override
    public String getModuleName( File modulePath )
        throws IOException
    {
        InputStream in = getModuleInfoClass( modulePath );

        ClassReader reader = new ClassReader( in );
        String className = reader.getClassName();
        int index = className.indexOf( "/module-info" );

        String moduleName;
        if ( index >= 1 )
        {
            moduleName = className.substring( 0, index ).replace( '/', '.' );
        }
        else
        {
            moduleName = null;
        }

        return moduleName;
    }

    private InputStream getModuleInfoClass( File modulePath )
        throws FileNotFoundException, IOException
    {
        InputStream in;
        if ( modulePath.isDirectory() )
        {
            in = new FileInputStream( new File( modulePath, "module-info.class" ) );
        }
        else
        {
            JarFile jarFile = null;
            try
            {
                jarFile = new JarFile( modulePath );
                JarEntry moduleInfo = jarFile.getJarEntry( "/module-info.class" );
                in = jarFile.getInputStream( moduleInfo );
            }
            finally
            {
                if ( jarFile != null )
                {
                    jarFile.close();
                }
            }
        }
        return in;
    }
}
