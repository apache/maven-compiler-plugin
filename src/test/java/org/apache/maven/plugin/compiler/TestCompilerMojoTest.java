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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.plugin.compiler.stubs.CompilerManagerStub;
import org.apache.maven.plugin.testing.junit5.InjectMojo;
import org.apache.maven.plugin.testing.junit5.MojoTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMavenProject;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMavenSession;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMojoExecution;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getVariableValueFromObject;
import static org.apache.maven.plugin.compiler.MojoTestUtils.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.*;

@MojoTest
class TestCompilerMojoTest {

    private static final String TEST_COMPILE = "testCompile";

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-basic-test/plugin-config.xml")
    void testCompilerBasic(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        testCompilerMojo.execute();

        File testClass = new File(testCompilerMojo.getOutputDirectory(), "foo/TestCompile0Test.class");

        assertTrue(testClass::exists);
    }

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-empty-source-test/plugin-config.xml")
    public void testCompilerEmptySource(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        testCompilerMojo.execute();

        assertFalse(testCompilerMojo.getOutputDirectory().exists());
    }

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-includes-excludes-test/plugin-config.xml")
    void testCompilerIncludesExcludes(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        Set<String> includes = new HashSet<>();
        includes.add("**/TestCompile4*.java");
        setVariableValueToObject(testCompilerMojo, "testIncludes", includes);

        Set<String> excludes = new HashSet<>();
        excludes.add("**/TestCompile2*.java");
        excludes.add("**/TestCompile3*.java");
        setVariableValueToObject(testCompilerMojo, "testExcludes", excludes);

        testCompilerMojo.execute();

        File testClass = new File(testCompilerMojo.getOutputDirectory(), "foo/TestCompile2TestCase.class");
        assertFalse(testClass.exists());

        testClass = new File(testCompilerMojo.getOutputDirectory(), "foo/TestCompile3TestCase.class");
        assertFalse(testClass.exists());

        testClass = new File(testCompilerMojo.getOutputDirectory(), "foo/TestCompile4TestCase.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-fork-test/plugin-config.xml")
    void testCompilerFork(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        // JAVA_HOME doesn't have to be on the PATH.
        setVariableValueToObject(
                testCompilerMojo, "executable", new File(System.getenv("JAVA_HOME"), "bin/javac").getPath());

        testCompilerMojo.execute();

        File testClass = new File(testCompilerMojo.getOutputDirectory(), "foo/TestCompile1TestCase.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-one-output-file-test/plugin-config.xml")
    void testOneOutputFileForAllInput(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        setVariableValueToObject(testCompilerMojo, "compilerManager", new CompilerManagerStub());

        testCompilerMojo.execute();

        File testClass = new File(testCompilerMojo.getOutputDirectory(), "compiled.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-one-output-file-test2/plugin-config.xml")
    void testOneOutputFileForAllInput2(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        setVariableValueToObject(testCompilerMojo, "compilerManager", new CompilerManagerStub());

        Set<String> includes = new HashSet<>();
        includes.add("**/TestCompile4*.java");
        setVariableValueToObject(testCompilerMojo, "testIncludes", includes);

        Set<String> excludes = new HashSet<>();
        excludes.add("**/TestCompile2*.java");
        excludes.add("**/TestCompile3*.java");
        setVariableValueToObject(testCompilerMojo, "testExcludes", excludes);

        testCompilerMojo.execute();

        File testClass = new File(testCompilerMojo.getOutputDirectory(), "compiled.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-skip-main/plugin-config.xml")
    void testCompileSkipMain(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        testCompilerMojo.execute();

        File testClass = new File(testCompilerMojo.getOutputDirectory(), "foo/TestSkipMainCompile0Test.class");
        assertTrue(testClass.exists());
    }

    @Test
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-skip-test/plugin-config.xml")
    void testCompileSkipTest(TestCompilerMojo testCompilerMojo) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        setVariableValueToObject(testCompilerMojo, "skip", true);

        testCompilerMojo.execute();

        File testClass = new File(testCompilerMojo.getOutputDirectory(), "foo/TestSkipTestCompile0Test.class");
        assertFalse(testClass.exists());
    }

    private void setUpCompilerMojoTestEnv(TestCompilerMojo mojo) throws Exception {
        File buildDir = getVariableValueFromObject(mojo, "buildDirectory");
        File testClassesDir = new File(buildDir, "test-classes");
        setVariableValueToObject(mojo, "outputDirectory", testClassesDir);

        setVariableValueToObject(mojo, "project", getMockMavenProject());

        Path baseDir = mojo.getOutputDirectory()
                .toPath()
                .resolveSibling(Paths.get("..", "..", "..", "..", "test-classes"))
                .normalize();
        Path subpath = mojo.getOutputDirectory().toPath().subpath(baseDir.getNameCount(), baseDir.getNameCount() + 2);
        String sourceRoot = baseDir.resolve(subpath) + "/src/main/java";
        String testSourceRoot = baseDir.resolve(subpath) + "/src/test/java";

        setVariableValueToObject(mojo, "compileSourceRoots", Arrays.asList(sourceRoot, testSourceRoot));

        setVariableValueToObject(mojo, "session", getMockMavenSession());
        setVariableValueToObject(mojo, "mojoExecution", getMockMojoExecution());
        setVariableValueToObject(mojo, "source", AbstractCompilerMojo.DEFAULT_SOURCE);
        setVariableValueToObject(mojo, "target", AbstractCompilerMojo.DEFAULT_TARGET);
    }

    static Stream<Arguments> olderThanJDK9() {
        return Stream.of(
                Arguments.of("1.8", true),
                Arguments.of("8", true),
                Arguments.of("1.9", false),
                Arguments.of("1.9", false),
                Arguments.of("9", false),
                Arguments.of("11", false));
    }

    @ParameterizedTest
    @MethodSource
    void olderThanJDK9(String version, boolean expected) {
        assertEquals(expected, TestCompilerMojo.isOlderThanJDK9(version));
    }
}
