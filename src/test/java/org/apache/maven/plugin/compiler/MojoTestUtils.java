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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MojoTestUtils {

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
}
