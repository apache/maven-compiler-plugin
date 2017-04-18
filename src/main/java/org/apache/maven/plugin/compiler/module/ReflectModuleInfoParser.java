package org.apache.maven.plugin.compiler.module;

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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import org.codehaus.plexus.component.annotations.Component;

/**
 * This class is could be replaced with a Java 9 MultiRelease implementation 
 * 
 * @author Robert Scholte
 * @since 3.7.0
 */
@Component( role = ModuleInfoParser.class, hint = "reflect" )
public class ReflectModuleInfoParser implements ModuleInfoParser
{
    @Override
    public Type getType()
    {
        return Type.CLASS;
    }

    @Override
    public JavaModuleDescriptor getModuleDescriptor( File modulePath )
        throws IOException
    {
        JavaModuleDescriptor moduleDescriptor = null;
        
        try
        {
            // Use Java9 code to get moduleName, don't try to do it better with own implementation
            Class moduleFinderClass = Class.forName( "java.lang.module.ModuleFinder" );

            java.nio.file.Path path = modulePath.toPath();

            Method ofMethod = moduleFinderClass.getMethod( "of", java.nio.file.Path[].class );
            Object moduleFinderInstance = ofMethod.invoke( null, new Object[] { new java.nio.file.Path[] { path } } );

            Method findAllMethod = moduleFinderClass.getMethod( "findAll" );
            Set<Object> moduleReferences = (Set<Object>) findAllMethod.invoke( moduleFinderInstance );

            Object moduleReference = moduleReferences.iterator().next();
            Method descriptorMethod = moduleReference.getClass().getMethod( "descriptor" );
            Object moduleDescriptorInstance = descriptorMethod.invoke( moduleReference );

            JavaModuleDescriptor.Builder builder = getBuilder( moduleDescriptorInstance );
            
            Method requiresMethod = moduleDescriptorInstance.getClass().getMethod( "requires" );
            Set<Object> requires = (Set<Object>) requiresMethod.invoke( moduleDescriptorInstance );
            
            for ( Object requiresInstance : requires )
            {
                Method nameMethod = requiresInstance.getClass().getMethod( "name" );
                String name = (String) nameMethod.invoke( requiresInstance );
                
                builder.requires( name );
            }
            
            moduleDescriptor = builder.build();
        }
        catch ( ClassNotFoundException e )
        {
            // do nothing
        }
        catch ( NoSuchMethodException e )
        {
            e.printStackTrace();
        }
        catch ( SecurityException e )
        {
            // do nothing
        }
        catch ( IllegalAccessException e )
        {
            // do nothing
        }
        catch ( IllegalArgumentException e )
        {
            // do nothing
        }
        catch ( InvocationTargetException e )
        {
            // do nothing
        }
        return moduleDescriptor;
    }

    private JavaModuleDescriptor.Builder getBuilder( Object moduleDescriptorInstance )
        throws NoSuchMethodException, IllegalAccessException, InvocationTargetException
    {
        JavaModuleDescriptor.Builder builder;
        Method nameMethod = moduleDescriptorInstance.getClass().getMethod( "name" );
        String name = (String) nameMethod.invoke( moduleDescriptorInstance );
        
        Method isAutomaticMethod = moduleDescriptorInstance.getClass().getMethod( "isAutomatic" );
        boolean automatic = (Boolean) isAutomaticMethod.invoke( moduleDescriptorInstance );

        if ( automatic )
        {
            builder = JavaModuleDescriptor.newAutomaticModule( name );
        }
        else
        {
            builder = JavaModuleDescriptor.newModule( name );
        }
        return builder;
    }

}
