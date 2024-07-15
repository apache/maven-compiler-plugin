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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Provides;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoExtension;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.plugin.testing.stubs.ProducedArtifactStub;
import org.apache.maven.api.plugin.testing.stubs.ProjectStub;
import org.apache.maven.api.plugin.testing.stubs.SessionMock;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.ToolchainManager;
import org.apache.maven.internal.impl.DefaultMessageBuilderFactory;
import org.apache.maven.internal.impl.InternalSession;
import org.apache.maven.plugin.compiler.stubs.CompilerStub;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@MojoTest
public class CompilerMojoTestCase {

    private static final String LOCAL_REPO = "/target/local-repo";

    @Inject
    private Session session;

    /**
     * Verifies that the given output file exists.
     *
     * @param mojo the tested mojo
     * @param first the first path element
     * @param more the other path elements, if any
     */
    private static void assertOutputFileExists(AbstractCompilerMojo mojo, String first, String... more) {
        Path file = mojo.getOutputDirectory().resolve(Path.of(first, more));
        assertTrue(Files.isRegularFile(file), () -> "File not found: " + file);
    }

    /**
     * Verifies that the given output file does not exist.
     *
     * @param mojo the tested mojo
     * @param first the first path element
     * @param more the other path elements, if any
     */
    private static void assertOutputFileDoesNotExist(AbstractCompilerMojo mojo, String first, String... more) {
        Path file = mojo.getOutputDirectory().resolve(Path.of(first, more));
        assertFalse(Files.exists(file), () -> "File should not exist: " + file);
    }

    /**
     * Tests the ability of the plugin to compile a basic file.
     * This test does not declare a Java release version. Therefore, a warning should be emitted.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-basic-test")
    public void testCompilerBasic(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        Log log = mock(Log.class);
        compileMojo.logger = log;
        compileMojo.execute();
        verify(log).warn(startsWith("No explicit value set for --release or --target."));
        assertOutputFileExists(compileMojo, "foo", "TestCompile0.class");
        assertTrue(
                session.getArtifactPath(compileMojo.projectArtifact).isPresent(),
                "MCOMPILER-94: artifact file should only be null if there is nothing to compile");

        testCompileMojo.execute();
        assertOutputFileExists(testCompileMojo, "foo", "TestCompile0Test.class");
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestCompile0Test.class");
    }

    /**
     * A project with a source and target version specified.
     * No warning should be logged.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-basic-sourcetarget")
    public void testCompilerBasicSourceTarget(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo) {

        Log log = mock(Log.class);
        compileMojo.logger = log;
        compileMojo.execute();
        verify(log, never()).warn(startsWith("No explicit value set for --release or --target."));
    }

    /**
     * Tests the ability of the plugin to respond to empty source.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-empty-source-test")
    public void testCompilerEmptySource(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        compileMojo.execute();
        assertFalse(Files.exists(compileMojo.getOutputDirectory()));
        assertNull(
                session.getArtifactPath(compileMojo.projectArtifact).orElse(null),
                "MCOMPILER-94: artifact file should be null if there is nothing to compile");

        testCompileMojo.execute();
        assertFalse(Files.exists(testCompileMojo.getOutputDirectory()));
    }

    /**
     * Tests the ability of the plugin to respond to includes and excludes correctly.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-includes-excludes-test")
    public void testCompilerIncludesExcludes(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        compileMojo.execute();
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestCompile2.class");
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestCompile3.class");
        assertOutputFileExists(compileMojo, "foo", "TestCompile4.class");

        testCompileMojo.execute();
        assertOutputFileDoesNotExist(testCompileMojo, "foo", "TestCompile2TestCase.class");
        assertOutputFileDoesNotExist(testCompileMojo, "foo", "TestCompile3TestCase.class");
        assertOutputFileExists(testCompileMojo, "foo", "TestCompile4TestCase.class");
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestCompile4TestCase.class");
    }

    /**
     * Tests the ability of the plugin to fork and successfully compile.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-fork-test")
    public void testCompilerFork(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        // JAVA_HOME doesn't have to be on the PATH.
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            String command = new File(javaHome, "bin/javac").getPath();
            compileMojo.executable = command;
            testCompileMojo.executable = command;
        }
        compileMojo.execute();
        assertOutputFileExists(compileMojo, "foo", "TestCompile1.class");

        testCompileMojo.execute();
        assertOutputFileExists(testCompileMojo, "foo", "TestCompile1TestCase.class");
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestCompile1TestCase.class");
    }

    /**
     * Tests the use of a custom compiler.
     * The dummy compiler used in this test generates only one file, despite having more than one source.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-one-output-file-test")
    public void testOneOutputFileForAllInput(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        assertEquals(CompilerStub.COMPILER_ID, compileMojo.compilerId);
        compileMojo.execute();
        assertOutputFileExists(compileMojo, CompilerStub.OUTPUT_FILE);

        assertEquals(CompilerStub.COMPILER_ID, testCompileMojo.compilerId);
        testCompileMojo.execute();
        assertOutputFileExists(testCompileMojo, CompilerStub.OUTPUT_FILE);
    }

    /**
     * Verifies that the options in the {@code <compilerArgs>} elements are given to the compiler.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-args-test")
    public void testCompilerArgs(@InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo) {

        assertEquals(CompilerStub.COMPILER_ID, compileMojo.compilerId);
        compileMojo.execute();

        assertOutputFileExists(compileMojo, CompilerStub.OUTPUT_FILE);
        assertArrayEquals(
                new String[] {"key1=value1", "-Xlint", "-my&special:param-with+chars/not>allowed_in_XML_element_names"},
                compileMojo.compilerArgs.toArray(String[]::new));

        List<String> options = CompilerStub.getOptions();
        assertArrayEquals(
                new String[] {
                    "--module-version", // Added by the plugin
                    "1.0-SNAPSHOT",
                    "key1=value1", // Specified in <compilerArgs>
                    "-Xlint",
                    "-my&special:param-with+chars/not>allowed_in_XML_element_names",
                    "param", // Specified in <compilerArgument>
                    "value"
                },
                options.toArray(String[]::new));
    }

    /**
     * Tests the {@code <implicit>} option when set to "none".
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-implicit-test")
    public void testImplicitFlagNone(
            @InjectMojo(goal = "compile", pom = "plugin-config-none.xml") CompilerMojo compileMojo) {

        assertEquals("none", compileMojo.implicit);
        compileMojo.execute();
    }

    /**
     * Tests the {@code <implicit>} option when not set.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-implicit-test")
    public void testImplicitFlagNotSet(
            @InjectMojo(goal = "compile", pom = "plugin-config-not-set.xml") CompilerMojo compileMojo) {

        assertNull(compileMojo.implicit);
        compileMojo.execute();
    }

    /**
     * Tests the compilation of a project having a {@code module-info.java} file, together with its tests.
     * The compilation of tests requires a {@code --patch-module} option, otherwise compilation will fail.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-modular-project")
    public void testModularProject(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        compileMojo.execute();
        assertOutputFileExists(compileMojo, SourceDirectory.MODULE_INFO + SourceDirectory.CLASS_FILE_SUFFIX);
        assertOutputFileExists(compileMojo, "foo", "TestModular.class");

        testCompileMojo.execute();
        assertOutputFileExists(testCompileMojo, "foo", "TestModularTestCase.class");
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestModularTestCase.class");
    }

    /**
     * Tests a compilation task which is expected to fail.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-fail-test")
    public void testCompileFailure(@InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo) {
        assertThrows(CompilationFailureException.class, compileMojo::execute, "Should throw an exception");
        assertOutputFileExists(compileMojo, "..", "javac.args"); // Command-line that user can execute.
    }

    /**
     * Tests a compilation task which is expected to fail, but where test failure are ignored.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-failonerror-test")
    public void testCompileFailOnError(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo) {

        try {
            compileMojo.execute();
        } catch (CompilationFailureException e) {
            fail("The compilation error should have been consumed because failOnError = false");
        }
        assertOutputFileExists(compileMojo, "..", "javac.args"); // Command-line that user can execute.
    }

    /**
     * Tests that setting {@code skipMain} to true skips compilation of the main Java source files,
     * but that test Java source files are still compiled.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-skip-main")
    public void testCompileSkipMain(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        compileMojo.skipMain = true;
        compileMojo.execute();
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestSkipMainCompile0.class");

        testCompileMojo.execute();
        assertOutputFileExists(testCompileMojo, "foo", "TestSkipMainCompile0Test.class");
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestSkipMainCompile0Test.class");
    }

    /**
     * Tests that setting {@code skip} to true skips compilation of the test Java source files,
     * but that main Java source files are still compiled.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-skip-test")
    public void testCompileSkipTest(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml") CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    TestCompilerMojo testCompileMojo) {

        compileMojo.execute();
        assertOutputFileExists(compileMojo, "foo/TestSkipTestCompile0.class");

        testCompileMojo.skip = true;
        testCompileMojo.execute();
        assertOutputFileDoesNotExist(testCompileMojo, "foo", "TestSkipTestCompile0Test.class");
        assertOutputFileDoesNotExist(compileMojo, "foo", "TestSkipTestCompile0Test.class");
    }

    @Provides
    @Singleton
    @SuppressWarnings("unused")
    private static InternalSession createSession() {
        InternalSession session = SessionMock.getMockSession(MojoExtension.getBasedir() + LOCAL_REPO);

        ToolchainManager toolchainManager = mock(ToolchainManager.class);
        doReturn(toolchainManager).when(session).getService(ToolchainManager.class);

        doAnswer(iom -> Instant.now().minus(200, ChronoUnit.MILLIS))
                .when(session)
                .getStartTime();

        var junit = new ProducedArtifactStub("junit", "junit", null, "3.8.1", "jar");

        MessageBuilderFactory messageBuilderFactory = new DefaultMessageBuilderFactory();
        doReturn(messageBuilderFactory).when(session).getService(MessageBuilderFactory.class);

        Map<String, String> props = new HashMap<>();
        props.put("basedir", MojoExtension.getBasedir());
        doReturn(props).when(session).getUserProperties();

        List<Path> artifacts = new ArrayList<>();
        try {
            Path artifactFile;
            String localRepository = System.getProperty("localRepository");
            if (localRepository != null) {
                artifactFile = Path.of(
                        localRepository,
                        "org",
                        "junit",
                        "jupiter",
                        "junit-jupiter-api",
                        "5.10.2",
                        "junit-jupiter-api-5.10.2.jar");
            } else {
                // for IDE
                String junitURI = Test.class.getResource("Test.class").toURI().toString();
                junitURI = junitURI.substring("jar:".length(), junitURI.indexOf('!'));
                artifactFile = new File(URI.create(junitURI)).toPath();
            }
            ArtifactManager artifactManager = session.getService(ArtifactManager.class);
            artifactManager.setPath(junit, artifactFile);
            artifacts.add(artifactFile);
        } catch (Exception e) {
            throw new RuntimeException("Unable to setup junit jar path", e);
        }

        ProjectManager projectManager = session.getService(ProjectManager.class);
        doAnswer(iom -> List.of()).when(session).resolveDependencies(any(), eq(PathScope.MAIN_COMPILE));
        doAnswer(iom -> artifacts).when(session).resolveDependencies(any(), eq(PathScope.TEST_COMPILE));

        return session;
    }

    @Provides
    @Singleton
    @SuppressWarnings("unused")
    private static Project createProject() {
        ProjectStub stub = new ProjectStub();
        var artifact = new ProducedArtifactStub("myGroupId", "myArtifactId", null, "1.0-SNAPSHOT", "jar");
        stub.setMainArtifact(artifact);
        stub.setModel(Model.newBuilder()
                .groupId(artifact.getGroupId())
                .artifactId(artifact.getArtifactId())
                .version(artifact.getVersion().asString())
                .build(Build.newBuilder()
                        .directory(MojoExtension.getBasedir() + "/target")
                        .outputDirectory(MojoExtension.getBasedir() + "/target/classes")
                        .sourceDirectory(MojoExtension.getBasedir() + "/src/main/java")
                        .testOutputDirectory(MojoExtension.getBasedir() + "/target/test-classes")
                        .build())
                .build());
        stub.setBasedir(Path.of(MojoExtension.getBasedir()));
        return stub;
    }
}
