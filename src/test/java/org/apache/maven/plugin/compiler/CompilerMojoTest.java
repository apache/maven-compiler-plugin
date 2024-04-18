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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.compiler.stubs.CompilerManagerStub;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.testing.junit5.InjectMojo;
import org.apache.maven.plugin.testing.junit5.MojoTest;
import org.apache.maven.plugin.testing.stubs.ArtifactStub;
import org.junit.jupiter.api.Test;

import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMavenProject;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMavenSession;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMojoExecution;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getVariableValueFromObject;
import static org.apache.maven.plugin.compiler.MojoTestUtils.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@MojoTest
class CompilerMojoTest {

    private static final String COMPILE = "compile";

    /**
     * tests the ability of the plugin to compile a basic file
     *
     * @throws Exception
     */
    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-basic-test/plugin-config.xml")
    void testCompilerBasic(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        Log log = mock(Log.class);

        compilerMojo.setLog(log);

        setVariableValueToObject(compilerMojo, "targetOrReleaseSet", false);
        compilerMojo.execute();

        Artifact projectArtifact = getVariableValueFromObject(compilerMojo, "projectArtifact");
        assertNotNull(
                projectArtifact.getFile(),
                "MCOMPILER-94: artifact file should only be null if there is nothing to compile");

        File testClass = new File(compilerMojo.getOutputDirectory(), "foo/TestCompile0.class");

        verify(log).warn(startsWith("No explicit value set for target or release!"));

        assertTrue(testClass::exists);
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-basic-sourcetarget/plugin-config.xml")
    void testCompilerBasicSourceTarget(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        Log log = mock(Log.class);

        compilerMojo.setLog(log);

        compilerMojo.execute();

        verify(log, never()).warn(startsWith("No explicit value set for target or release!"));
    }

    /**
     * tests the ability of the plugin to respond to empty source
     *
     * @throws Exception
     */
    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-empty-source-test/plugin-config.xml")
    void testCompilerEmptySource(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        compilerMojo.execute();

        assertFalse(compilerMojo.getOutputDirectory().exists());

        Artifact projectArtifact = getVariableValueFromObject(compilerMojo, "projectArtifact");
        assertNull(
                projectArtifact.getFile(), "MCOMPILER-94: artifact file should be null if there is nothing to compile");
    }

    /**
     * tests the ability of the plugin to respond to includes and excludes correctly
     *
     * @throws Exception
     */
    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-includes-excludes-test/plugin-config.xml")
    void testCompilerIncludesExcludes(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        Set<String> includes = new HashSet<>();
        includes.add("**/TestCompile4*.java");
        setVariableValueToObject(compilerMojo, "includes", includes);

        Set<String> excludes = new HashSet<>();
        excludes.add("**/TestCompile2*.java");
        excludes.add("**/TestCompile3*.java");
        setVariableValueToObject(compilerMojo, "excludes", excludes);

        compilerMojo.execute();

        File testClass = new File(compilerMojo.getOutputDirectory(), "foo/TestCompile2.class");
        assertFalse(testClass.exists());

        testClass = new File(compilerMojo.getOutputDirectory(), "foo/TestCompile3.class");
        assertFalse(testClass.exists());

        testClass = new File(compilerMojo.getOutputDirectory(), "foo/TestCompile4.class");
        assertTrue(testClass.exists());
    }

    /**
     * tests the ability of the plugin to fork and successfully compile
     *
     * @throws Exception
     */
    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-fork-test/plugin-config.xml")
    void testCompilerFork(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);
        // JAVA_HOME doesn't have to be on the PATH.
        setVariableValueToObject(
                compilerMojo, "executable", new File(System.getenv("JAVA_HOME"), "bin/javac").getPath());

        compilerMojo.execute();

        File testClass = new File(compilerMojo.getOutputDirectory(), "foo/TestCompile1.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-one-output-file-test/plugin-config.xml")
    void testOneOutputFileForAllInput(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        setVariableValueToObject(compilerMojo, "compilerManager", new CompilerManagerStub());

        compilerMojo.execute();

        File testClass = new File(compilerMojo.getOutputDirectory(), "compiled.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-args-test/plugin-config.xml")
    void testCompilerArgs(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        setVariableValueToObject(compilerMojo, "compilerManager", new CompilerManagerStub());

        compilerMojo.execute();

        File testClass = new File(compilerMojo.getOutputDirectory(), "compiled.class");
        assertTrue(testClass.exists());
        assertEquals(
                Arrays.asList("key1=value1", "-Xlint", "-my&special:param-with+chars/not>allowed_in_XML_element_names"),
                compilerMojo.compilerArgs);
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-implicit-test/plugin-config-none.xml")
    void testImplicitFlagNone(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        assertEquals("none", compilerMojo.getImplicit());
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-implicit-test/plugin-config-not-set.xml")
    void testImplicitFlagNotSet(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        assertNull(compilerMojo.getImplicit());
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-one-output-file-test2/plugin-config.xml")
    void testOneOutputFileForAllInput2(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        setVariableValueToObject(compilerMojo, "compilerManager", new CompilerManagerStub());

        Set<String> includes = new HashSet<>();
        includes.add("**/TestCompile4*.java");
        setVariableValueToObject(compilerMojo, "includes", includes);

        Set<String> excludes = new HashSet<>();
        excludes.add("**/TestCompile2*.java");
        excludes.add("**/TestCompile3*.java");
        setVariableValueToObject(compilerMojo, "excludes", excludes);

        compilerMojo.execute();

        File testClass = new File(compilerMojo.getOutputDirectory(), "compiled.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-fail-test/plugin-config.xml")
    void testCompileFailure(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        setVariableValueToObject(compilerMojo, "compilerManager", new CompilerManagerStub(true));

        try {
            compilerMojo.execute();

            fail("Should throw an exception");
        } catch (CompilationFailureException e) {
            // expected
        }
    }

    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-failonerror-test/plugin-config.xml")
    void testCompileFailOnError(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        setVariableValueToObject(compilerMojo, "compilerManager", new CompilerManagerStub(true));

        try {
            compilerMojo.execute();
            assertTrue(true);
        } catch (CompilationFailureException e) {
            fail("The compilation error should have been consumed because failOnError = false");
        }
    }

    /**
     * Tests that setting 'skipMain' to true skips compilation of the main Java source files, but that test Java source
     * files are still compiled.
     * @throws Exception
     */
    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-skip-main/plugin-config.xml")
    void testCompileSkipMain(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);
        setVariableValueToObject(compilerMojo, "skipMain", true);
        compilerMojo.execute();
        File testClass = new File(compilerMojo.getOutputDirectory(), "foo/TestSkipMainCompile0.class");
        assertFalse(testClass.exists());
    }

    /**
     * Tests that setting 'skip' to true skips compilation of the test Java source files, but that main Java source
     * files are still compiled.
     * @throws Exception
     */
    @Test
    @InjectMojo(goal = COMPILE, pom = "classpath:/unit/compiler-skip-test/plugin-config.xml")
    void testCompileSkipTest(CompilerMojo compilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(compilerMojo);

        compilerMojo.execute();

        File testClass = new File(compilerMojo.getOutputDirectory(), "foo/TestSkipTestCompile0.class");
        assertTrue(testClass.exists());
    }

    private void setUpCompilerMojoTestEnv(CompilerMojo mojo) throws Exception {
        setVariableValueToObject(mojo, "projectArtifact", new ArtifactStub());
        setVariableValueToObject(mojo, "compilePath", Collections.EMPTY_LIST);
        setVariableValueToObject(mojo, "session", getMockMavenSession());
        setVariableValueToObject(mojo, "project", getMockMavenProject());
        setVariableValueToObject(mojo, "mojoExecution", getMockMojoExecution());
        setVariableValueToObject(mojo, "source", AbstractCompilerMojo.DEFAULT_SOURCE);
        setVariableValueToObject(mojo, "target", AbstractCompilerMojo.DEFAULT_TARGET);
    }
}
