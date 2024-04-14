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

import java.io.File;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.testing.junit5.InjectMojo;
import org.apache.maven.plugin.testing.junit5.MojoTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@MojoTest
public class CompilerTest {

    @Test
    @InjectMojo(goal = "compile", pom = "classpath:target/test-classes/unit/compiler-basic-test/plugin-config.xml")
    void testCompilerBasic(CompilerMojo compileMojo) throws Exception {
//        CompilerMojo compileMojo = getCompilerMojo("target/test-classes/unit/compiler-basic-test/plugin-config.xml");

        Log log = mock(Log.class);

        compileMojo.setLog(log);

        compileMojo.execute();

        File testClass = new File(compileMojo.getOutputDirectory(), "foo/TestCompile0.class");

        assertTrue(testClass.exists());
/*
        TestCompilerMojo testCompileMojo =
                getTestCompilerMojo(compileMojo, "target/test-classes/unit/compiler-basic-test/plugin-config.xml");

        testCompileMojo.execute();

        Artifact projectArtifact = getVariableValueFromObject(compileMojo, "projectArtifact");
        assertNotNull(
                projectArtifact.getFile(),
                "MCOMPILER-94: artifact file should only be null if there is nothing to compile");

        testClass = new File(testCompileMojo.getOutputDirectory(), "foo/TestCompile0Test.class");

        verify(log).warn(startsWith("No explicit value set for target or release!"));

        assertTrue(testClass.exists());
*/

    }
}
