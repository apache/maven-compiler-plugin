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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.plugin.compiler.stubs.CompilerManagerStub;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.apache.maven.api.plugin.testing.MojoExtension.getVariableValueFromObject;
import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMavenProject;
import static org.apache.maven.plugin.compiler.MojoTestUtils.getMockMavenSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Tests that test sources of a module are compiled as a patch of the main module: the mojo records
     * {@code --patch-module} and {@code --add-reads} in {@code META-INF/jpms.args}.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    @InjectMojo(goal = TEST_COMPILE, pom = "classpath:/unit/compiler-jpms-patch-module/plugin-config.xml")
    void testJpmsPatchModule(TestCompilerMojo testCompilerMojo, @TempDir Path mainClasses) throws Exception {
        setUpCompilerMojoTestEnv(testCompilerMojo);

        // the test mojo reads the main module descriptor from the compiled main classes
        Path fixture = Paths.get(testCompilerMojo.getCompileSourceRoots().get(0))
                .getParent()
                .getParent()
                .getParent();
        Path mainSources = fixture.resolve("src/main/java");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(
                0,
                javac.run(
                        null,
                        null,
                        null,
                        "--release",
                        "9",
                        "-d",
                        mainClasses.toString(),
                        mainSources.resolve("module-info.java").toString(),
                        mainSources.resolve("org/maven/test/Main.java").toString()));

        MavenProject project = getMockMavenProject();
        project.getBuild().setOutputDirectory(mainClasses.toString());
        setVariableValueToObject(testCompilerMojo, "project", project);
        setVariableValueToObject(
                testCompilerMojo,
                "compileSourceRoots",
                Arrays.asList(fixture.resolve("src/test/java").toString()));
        setVariableValueToObject(testCompilerMojo, "testPath", Arrays.asList(mainClasses.toString()));

        // output of earlier runs stays under target/, so start without it
        Path jpmsArgs = testCompilerMojo.getOutputDirectory().toPath().resolve("META-INF/jpms.args");
        Files.deleteIfExists(jpmsArgs);

        testCompilerMojo.execute();

        assertEquals(
                Arrays.asList("--patch-module", "app=_", "--add-reads", "app=ALL-UNNAMED"),
                Files.readAllLines(jpmsArgs));
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

    @Test
    void shouldSelectApplicableModuleDescriptor(@TempDir Path temporaryDirectory) throws Exception {
        Path outputDirectory = temporaryDirectory.resolve("classes");
        Path version9 = Files.createDirectories(outputDirectory.resolve("META-INF/versions/9"));
        Path version11 = Files.createDirectories(outputDirectory.resolve("META-INF/versions/11"));
        Files.createDirectories(outputDirectory.resolve("META-INF/versions/17"));

        Path version9Descriptor = Files.createFile(version9.resolve("module-info.class"));
        Path version11Descriptor = Files.createFile(version11.resolve("module-info.class"));
        Path rootDescriptor = Files.createFile(outputDirectory.resolve("module-info.class"));

        List<File> outputDirectories = AbstractCompilerMojo.getProjectOutputDirectories(outputDirectory.toFile(), 11);
        assertEquals(Arrays.asList(version11.toFile(), version9.toFile(), outputDirectory.toFile()), outputDirectories);
        assertEquals(version11Descriptor.toFile(), TestCompilerMojo.getModuleDescriptor(outputDirectories));

        Files.delete(version11Descriptor);
        assertEquals(version9Descriptor.toFile(), TestCompilerMojo.getModuleDescriptor(outputDirectories));

        Files.delete(version9Descriptor);
        assertEquals(rootDescriptor.toFile(), TestCompilerMojo.getModuleDescriptor(outputDirectories));
    }
}
