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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds the results of the project analyzer
 * 
 * @author Robert Scholte
 * @since 3.7.0
 */
public class ProjectAnalyzerResult
{
    /**
     * Source of the modulename 
     */
    public enum ModuleNameSource
    {
        FILENAME, MANIFEST, MODULEDESCRIPTOR
    }
    
    private JavaModuleDescriptor baseModuleDescriptor;
    
    /**
     * Ordered map, respects the classpath order
     */
    private Map<File, JavaModuleDescriptor> pathElements;
    
    private Map<String, ModuleNameSource> moduleNameSources = new HashMap<String, ModuleNameSource>();
    
    private Set<String> requiredNormalModules = new HashSet<String>();

    private Set<String> requiredAutomaticModules = new HashSet<String>();

    public void setBaseModuleDescriptor( JavaModuleDescriptor baseModuleDescriptor )
    {
        this.baseModuleDescriptor = baseModuleDescriptor;
    }

    public JavaModuleDescriptor getBaseModuleDescriptor()
    {
        return baseModuleDescriptor;
    }

    public void setPathElements( Map<File, JavaModuleDescriptor> pathElements )
    {
        this.pathElements = pathElements;
    }
    
    /**
     * Ordered map, respects the classpath order
     */
    public Map<File, JavaModuleDescriptor> getPathElements()
    {
        return pathElements;
    }
    
    public void setModuleNameSources( Map<String, ModuleNameSource> moduleNameSources )
    {
        this.moduleNameSources = moduleNameSources;
    }
    
    public ModuleNameSource getModuleNameSource( String name )
    {
        return moduleNameSources.get( name );
    }
    
    public void setRequiredNormalModules( Set<String> requiredNormalModules )
    {
        this.requiredNormalModules = requiredNormalModules;
    }
    
    public Set<String> getRequiredNormalModules()
    {
        return requiredNormalModules;
    }
    
    public void setRequiredAutomaticModules( Set<String> requiredAutomaticModules )
    {
        this.requiredAutomaticModules = requiredAutomaticModules;
    }
    
    public Set<String> getRequiredAutomaticModules()
    {
        return requiredAutomaticModules;
    }
}
