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
import java.util.Collection;

/**
 * Contains all information required to analyze the project
 * 
 * @author Robert Scholte
 * @since 3.7.0
 */
public class ProjectAnalyzerRequest
{
    private JavaModuleDescriptor baseModuleDescriptor;
    
    private Collection<File> dependencyArtifacts;

    public JavaModuleDescriptor getBaseModuleDescriptor()
    {
        return baseModuleDescriptor;
    }

    public ProjectAnalyzerRequest setBaseModuleDescriptor( JavaModuleDescriptor baseModuleDescriptor )
    {
        this.baseModuleDescriptor = baseModuleDescriptor;
        return this;
    }

    public Collection<File> getDependencyArtifacts()
    {
        return dependencyArtifacts;
    }

    public ProjectAnalyzerRequest setDependencyArtifacts( Collection<File> dependencyArtifacts )
    {
        this.dependencyArtifacts = dependencyArtifacts;
        return this;
    }
}
