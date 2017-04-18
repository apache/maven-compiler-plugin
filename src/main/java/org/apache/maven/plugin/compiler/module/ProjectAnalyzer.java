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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;

/**
 * Maps artifacts to modules and analyzes the type of required modules
 * 
 * @author Robert Scholte
 * @since 3.7.0
 */
@Component( role = ProjectAnalyzer.class )
public class ProjectAnalyzer extends AbstractLogEnabled
{
    @Requirement( hint = "asm" )
    private ModuleInfoParser asmParser;

    @Requirement( hint = "reflect" )
    private ModuleInfoParser reflectParser;

    public ProjectAnalyzerResult analyze( ProjectAnalyzerRequest request )
        throws IOException
    {
        ProjectAnalyzerResult result = new ProjectAnalyzerResult();
        
        Map<File, JavaModuleDescriptor> pathElements =
            new LinkedHashMap<File, JavaModuleDescriptor>( request.getDependencyArtifacts().size() );

        JavaModuleDescriptor baseModuleDescriptor = request.getBaseModuleDescriptor();

        Map<String, JavaModuleDescriptor> availableNamedModules = new HashMap<String, JavaModuleDescriptor>(); 
        
        // start from root
        result.setBaseModuleDescriptor( baseModuleDescriptor );

        // collect all modules from path
        for ( File file : request.getDependencyArtifacts() )
        {
            JavaModuleDescriptor descriptor = extractDescriptor( file );
            
            if ( descriptor != null )
            {
                availableNamedModules.put( descriptor.name(), descriptor );
            }
            
            pathElements.put( file, descriptor );
        }
        result.setPathElements( pathElements );

        if ( baseModuleDescriptor != null )
        {
            Set<String> requiredNamedModules = new HashSet<String>();
            Set<String> requiredUnnamedModules = new HashSet<String>();
            
            select( baseModuleDescriptor, Collections.unmodifiableMap( availableNamedModules ), requiredNamedModules,
                    requiredUnnamedModules );
            
            result.setRequiredNormalModules( requiredNamedModules );
            result.setRequiredAutomaticModules( requiredUnnamedModules );
        }

        return result;
    }

    private JavaModuleDescriptor extractDescriptor( File file )
        throws IOException
    {
        JavaModuleDescriptor moduleDescriptor;
        if ( file.isFile() || new File( file, "module-info.class" ).exists() )
        {
            moduleDescriptor = reflectParser.getModuleDescriptor( file );

            if ( moduleDescriptor == null )
            {
                moduleDescriptor = asmParser.getModuleDescriptor( file );
            }
        }
        else
        {
            moduleDescriptor = null;
        }
        return moduleDescriptor;
    }
    
    private void select( JavaModuleDescriptor module, Map<String, JavaModuleDescriptor> availableModules,
                         Set<String> namedModules, Set<String> unnamedModules )
    {
        for ( JavaModuleDescriptor.JavaRequires requires : module.requires() )
        {
            String requiresName = requires.name();
            JavaModuleDescriptor requiredModule = availableModules.get( requiresName );

            if ( requiredModule != null && !requiredModule.isAutomatic() )
            {
                if ( namedModules.add( requiresName ) )
                {
                    select( requiredModule, availableModules, namedModules, unnamedModules );
                }
            }
            else
            {
                unnamedModules.add( requiresName );
            }
        }
    }
}
