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
package org.apache.maven.plugin.compiler;

import java.lang.reflect.Field;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.ReflectionUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MojoTestUtils {

    public static <T> T getVariableValueFromObject(Object object, String variable) throws IllegalAccessException {
        Field field = ReflectionUtils.getFieldByNameIncludingSuperclasses(variable, object.getClass());
        field.setAccessible(true);
        return (T) field.get(object);
    }

    public static <T> void setVariableValueToObject(Object object, String variable, T value)
            throws IllegalAccessException {
        Field field = ReflectionUtils.getFieldByNameIncludingSuperclasses(variable, object.getClass());
        field.setAccessible(true);
        field.set(object, value);
    }

    public static MavenProject getMockMavenProject() {
        MavenProject mp = new MavenProject();
        mp.getBuild().setDirectory("target");
        mp.getBuild().setOutputDirectory("target/classes");
        mp.getBuild().setSourceDirectory("src/main/java");
        mp.getBuild().setTestOutputDirectory("target/test-classes");
        return mp;
    }

    public static MavenSession getMockMavenSession() {
        MavenSession session = mock(MavenSession.class);
        // when( session.getPluginContext( isA(PluginDescriptor.class), isA(MavenProject.class) ) ).thenReturn(
        // Collections.emptyMap() );
        when(session.getCurrentProject()).thenReturn(getMockMavenProject());
        return session;
    }

    public static MojoExecution getMockMojoExecution() {
        MojoDescriptor md = new MojoDescriptor();
        MojoExecution me = new MojoExecution(md);

        PluginDescriptor pd = new PluginDescriptor();
        pd.setArtifactId("maven-compiler-plugin");
        md.setPluginDescriptor(pd);

        return me;
    }
}
