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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Simple representation of a Module containing info required by this plugin.
 * It will provide only methods matching Java 9 ModuleDescriptor, so once Java 9  is required, we can easily switch 
 * 
 * @author Robert Scholte
 * @since 3.7.0
 *
 */
public class JavaModuleDescriptor
{
    private String name;
    
    private boolean automatic;

    private Set<JavaRequires> requires = new HashSet<JavaRequires>();
    
    private Set<JavaExports> exports = new HashSet<JavaExports>();

    public String name()
    {
        return name;
    }

    public boolean isAutomatic()
    {
        return automatic;
    }
    
    public Set<JavaRequires> requires()
    {
        return Collections.unmodifiableSet( requires );
    }
    
    public Set<JavaExports> exports()
    {
        return Collections.unmodifiableSet( exports );
    }
    
    public static JavaModuleDescriptor.Builder newModule( String name )
    {
        return new Builder( name ).setAutomatic( false );
    }
    
    public static Builder newAutomaticModule( String name )
    {
        return new Builder( name ).setAutomatic( true );
    }  

    /**
     * A JavaModuleDescriptor Builder
     * 
     * @author Robert Scholte
     * @since 3.7.0
     */
    public static final class Builder
    {
        private JavaModuleDescriptor jModule;
        
        private Builder( String name )
        {
            jModule = new JavaModuleDescriptor();
            jModule.name = name;
        }
        
        private Builder setAutomatic( boolean isAutomatic )
        {
            jModule.automatic = isAutomatic;
            return this;
        }

        public Builder requires( String name )
        {
            JavaRequires requires = new JavaRequires( name );
            jModule.requires.add( requires );
            return this;
        }
        
        public Builder exports( String source )
        {
            JavaExports exports = new JavaExports( source );
            jModule.exports.add( exports );
            return this;
        }
        
        public JavaModuleDescriptor build()
        {
            return jModule;
        }
    }
    
    /**
     * Represents Module.Requires
     * 
     * @author Robert Scholte
     * @since 3.7.0
     */
    public static class JavaRequires
    {
        private final String name;

        private JavaRequires( String name )
        {
            this.name = name;
        }

        public String name()
        {
            return name;
        }
    }
    
    /**
     * Represents Module.Exports
     * 
     * @author Robert Scholte
     * @since 3.7.0
     *
     */
    public static class JavaExports
    {
        private final String source;
        
        private JavaExports( String source )
        {
            this.source = source;
        }
        
        public String source()
        {
            return source;
        }
    }
    
}
