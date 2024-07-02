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
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Provides;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoExtension;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.plugin.testing.stubs.ArtifactStub;
import org.apache.maven.api.plugin.testing.stubs.ProjectStub;
import org.apache.maven.api.plugin.testing.stubs.SessionMock;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.ToolchainManager;
import org.apache.maven.internal.impl.DefaultMessageBuilderFactory;
import org.apache.maven.internal.impl.InternalSession;
import org.apache.maven.plugin.compiler.stubs.CompilerManagerStub;
import org.codehaus.plexus.languages.java.version.JavaVersion;
import org.junit.jupiter.api.Test;

import static org.apache.maven.api.plugin.testing.MojoExtension.getBasedir;
import static org.apache.maven.api.plugin.testing.MojoExtension.getVariableValueFromObject;
import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
     * tests the ability of the plugin to compile a basic file
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-basic-test")
    public void testCompilerBasic(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        Log log = setMockLogger(compileMojo);

        setVariableValueToObject(compileMojo, "targetOrReleaseSet", Boolean.FALSE);
        execute(compileMojo);

        verify(log).warn(startsWith("No explicit value set for target or release!"));

        Path testClass = compileMojo.getOutputDirectory().resolve("foo/TestCompile0.class");
        assertTrue(Files.exists(testClass));
        Artifact projectArtifact = (Artifact) getVariableValueFromObject(compileMojo, "projectArtifact");
        assertNotNull(
                session.getArtifactPath(projectArtifact).orElse(null),
                "MCOMPILER-94: artifact file should only be null if there is nothing to compile");

        execute(testCompileMojo);

        testClass = testCompileMojo.getOutputDirectory().resolve("foo/TestCompile0Test.class");
        assertTrue(Files.exists(testClass));
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-basic-sourcetarget")
    public void testCompilerBasicSourceTarget(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo)
            throws Exception {
        Log log = setMockLogger(compileMojo);

        execute(compileMojo);

        verify(log, never()).warn(startsWith("No explicit value set for target or release!"));
    }

    /**
     * tests the ability of the plugin to respond to empty source
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-empty-source-test")
    public void testCompilerEmptySource(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${basedir}/src/test/java")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        execute(compileMojo);

        assertFalse(Files.exists(compileMojo.getOutputDirectory()));
        Artifact projectArtifact = (Artifact) getVariableValueFromObject(compileMojo, "projectArtifact");
        assertNull(
                session.getArtifactPath(projectArtifact).orElse(null),
                "MCOMPILER-94: artifact file should be null if there is nothing to compile");

        execute(testCompileMojo);

        assertFalse(Files.exists(testCompileMojo.getOutputDirectory()));
    }

    /**
     * tests the ability of the plugin to respond to includes and excludes correctly
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-includes-excludes-test")
    public void testCompilerIncludesExcludes(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        Set<String> includes = new HashSet<>();
        includes.add("**/TestCompile4*.java");
        setVariableValueToObject(compileMojo, "includes", includes);

        Set<String> excludes = new HashSet<>();
        excludes.add("**/TestCompile2*.java");
        excludes.add("**/TestCompile3*.java");
        setVariableValueToObject(compileMojo, "excludes", excludes);

        execute(compileMojo);

        Path testClass = compileMojo.getOutputDirectory().resolve("foo/TestCompile2.class");
        assertFalse(Files.exists(testClass));

        testClass = compileMojo.getOutputDirectory().resolve("foo/TestCompile3.class");
        assertFalse(Files.exists(testClass));

        testClass = compileMojo.getOutputDirectory().resolve("foo/TestCompile4.class");
        assertTrue(Files.exists(testClass));

        setVariableValueToObject(testCompileMojo, "testIncludes", includes);
        setVariableValueToObject(testCompileMojo, "testExcludes", excludes);

        execute(testCompileMojo);

        testClass = testCompileMojo.getOutputDirectory().resolve("foo/TestCompile2TestCase.class");
        assertFalse(Files.exists(testClass));

        testClass = testCompileMojo.getOutputDirectory().resolve("foo/TestCompile3TestCase.class");
        assertFalse(Files.exists(testClass));

        testClass = testCompileMojo.getOutputDirectory().resolve("foo/TestCompile4TestCase.class");
        assertTrue(Files.exists(testClass));
    }

    /**
     * tests the ability of the plugin to fork and successfully compile
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-fork-test")
    public void testCompilerFork(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "compileSourceRoots", value = "${project.basedir}/src/test/java")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        // JAVA_HOME doesn't have to be on the PATH.
        setVariableValueToObject(
                compileMojo, "executable", new File(System.getenv("JAVA_HOME"), "bin/javac").getPath());

        execute(compileMojo);

        Path testClass = compileMojo.getOutputDirectory().resolve("foo/TestCompile1.class");
        assertTrue(Files.exists(testClass));

        // JAVA_HOME doesn't have to be on the PATH.
        setVariableValueToObject(
                testCompileMojo, "executable", new File(System.getenv("JAVA_HOME"), "bin/javac").getPath());

        execute(testCompileMojo);

        testClass = testCompileMojo.getOutputDirectory().resolve("foo/TestCompile1TestCase.class");
        assertTrue(Files.exists(testClass));
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-one-output-file-test")
    public void testOneOutputFileForAllInput(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        setVariableValueToObject(compileMojo, "compilerManager", new CompilerManagerStub());

        execute(compileMojo);

        Path testClass = compileMojo.getOutputDirectory().resolve("compiled.class");
        assertTrue(Files.exists(testClass));

        setVariableValueToObject(testCompileMojo, "compilerManager", new CompilerManagerStub());

        setVariableValueToObject(
                testCompileMojo,
                "compileSourceRoots",
                Collections.singletonList(
                        Paths.get(getBasedir(), "src/test/java").toString()));
        execute(testCompileMojo);

        testClass = testCompileMojo.getOutputDirectory().resolve("compiled.class");
        assertTrue(Files.exists(testClass));
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-args-test")
    public void testCompilerArgs(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo)
            throws Exception {
        setVariableValueToObject(compileMojo, "compilerManager", new CompilerManagerStub());

        execute(compileMojo);

        Path testClass = compileMojo.getOutputDirectory().resolve("compiled.class");
        assertTrue(Files.exists(testClass));
        assertEquals(
                Arrays.asList("key1=value1", "-Xlint", "-my&special:param-with+chars/not>allowed_in_XML_element_names"),
                compileMojo.compilerArgs);
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-implicit-test")
    public void testImplicitFlagNone(
            @InjectMojo(goal = "compile", pom = "plugin-config-none.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo)
            throws Exception {
        assertEquals("none", compileMojo.getImplicit());
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-implicit-test")
    public void testImplicitFlagNotSet(
            @InjectMojo(goal = "compile", pom = "plugin-config-not-set.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo)
            throws Exception {
        assertNull(compileMojo.getImplicit());
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-one-output-file-test2")
    public void testOneOutputFileForAllInput2(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        setVariableValueToObject(compileMojo, "compilerManager", new CompilerManagerStub());

        Set<String> includes = new HashSet<>();
        includes.add("**/TestCompile4*.java");
        setVariableValueToObject(compileMojo, "includes", includes);

        Set<String> excludes = new HashSet<>();
        excludes.add("**/TestCompile2*.java");
        excludes.add("**/TestCompile3*.java");
        setVariableValueToObject(compileMojo, "excludes", excludes);

        execute(compileMojo);

        Path testClass = compileMojo.getOutputDirectory().resolve("compiled.class");
        assertTrue(Files.exists(testClass));

        setVariableValueToObject(testCompileMojo, "compilerManager", new CompilerManagerStub());
        setVariableValueToObject(testCompileMojo, "testIncludes", includes);
        setVariableValueToObject(testCompileMojo, "testExcludes", excludes);

        setVariableValueToObject(
                testCompileMojo,
                "compileSourceRoots",
                Collections.singletonList(
                        Paths.get(getBasedir(), "src/test/java").toString()));
        execute(testCompileMojo);

        testClass = testCompileMojo.getOutputDirectory().resolve("compiled.class");
        assertTrue(Files.exists(testClass));
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-fail-test")
    public void testCompileFailure(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo)
            throws Exception {
        setVariableValueToObject(compileMojo, "compilerManager", new CompilerManagerStub(true));

        assertThrows(CompilationFailureException.class, compileMojo::execute, "Should throw an exception");
    }

    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-failonerror-test")
    public void testCompileFailOnError(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo)
            throws Exception {
        setVariableValueToObject(compileMojo, "compilerManager", new CompilerManagerStub(true));

        try {
            execute(compileMojo);
            assertTrue(true);
        } catch (CompilationFailureException e) {
            fail("The compilation error should have been consumed because failOnError = false");
        }
    }

    /**
     * Tests that setting 'skipMain' to true skips compilation of the main Java source files, but that test Java source
     * files are still compiled.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-skip-main")
    public void testCompileSkipMain(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        setVariableValueToObject(compileMojo, "skipMain", true);
        execute(compileMojo);
        Path testClass = compileMojo.getOutputDirectory().resolve("foo/TestSkipMainCompile0.class");
        assertFalse(Files.exists(testClass));

        setVariableValueToObject(
                testCompileMojo,
                "compileSourceRoots",
                Collections.singletonList(
                        Paths.get(getBasedir(), "src/test/java").toString()));
        execute(testCompileMojo);
        testClass = testCompileMojo.getOutputDirectory().resolve("foo/TestSkipMainCompile0Test.class");
        assertTrue(Files.exists(testClass));
    }

    /**
     * Tests that setting 'skip' to true skips compilation of the test Java source files, but that main Java source
     * files are still compiled.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/compiler-skip-test")
    public void testCompileSkipTest(
            @InjectMojo(goal = "compile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/compile")
                    CompilerMojo compileMojo,
            @InjectMojo(goal = "testCompile", pom = "plugin-config.xml")
                    @MojoParameter(name = "mojoStatusPath", value = "maven-status/testCompile")
                    TestCompilerMojo testCompileMojo)
            throws Exception {
        execute(compileMojo);
        Path testClass = compileMojo.getOutputDirectory().resolve("foo/TestSkipTestCompile0.class");
        assertTrue(Files.exists(testClass));

        setVariableValueToObject(testCompileMojo, "skip", true);
        setVariableValueToObject(
                testCompileMojo,
                "compileSourceRoots",
                Collections.singletonList(
                        Paths.get(getBasedir(), "src/test/java").toString()));
        execute(testCompileMojo);
        testClass = testCompileMojo.getOutputDirectory().resolve("foo/TestSkipTestCompile0Test.class");
        assertFalse(Files.exists(testClass));
    }

    @Provides
    @Singleton
    @SuppressWarnings("unused")
    private static InternalSession createSession() {
        InternalSession session = SessionMock.getMockSession(getBasedir() + LOCAL_REPO);

        ToolchainManager toolchainManager = mock(ToolchainManager.class);
        doReturn(toolchainManager).when(session).getService(ToolchainManager.class);

        doAnswer(iom -> Instant.now().minus(200, ChronoUnit.MILLIS))
                .when(session)
                .getStartTime();

        Artifact junit = new ArtifactStub("junit", "junit", null, "3.8.1", "jar");

        MessageBuilderFactory messageBuilderFactory = new DefaultMessageBuilderFactory();
        doReturn(messageBuilderFactory).when(session).getService(MessageBuilderFactory.class);

        String source = AbstractCompilerMojo.DEFAULT_SOURCE;
        String target = AbstractCompilerMojo.DEFAULT_TARGET;
        String javaSpec = System.getProperty("java.specification.version");
        // It is needed to set target/source to JDK 7 for JDK12+ and JDK 8 for JDK17+
        // because this is the lowest version which is supported by those JDK's.
        // The default source/target "6" is not supported anymore.
        if (JavaVersion.parse(javaSpec).isAtLeast("17")) {
            source = "8";
            target = "8";
        } else if (JavaVersion.parse(javaSpec).isAtLeast("12")) {
            source = "7";
            target = "7";
        }

        Map<String, String> props = new HashMap<>();
        props.put("basedir", MojoExtension.getBasedir());
        props.put("maven.compiler.source", source);
        props.put("maven.compiler.target", target);
        doReturn(props).when(session).getUserProperties();

        List<Path> artifacts = new ArrayList<>();
        try {
            Path artifactFile;
            String localRepository = System.getProperty("localRepository");
            if (localRepository != null) {
                artifactFile = Paths.get(localRepository, "junit/junit/3.8.1/junit-3.8.1.jar");
            } else {
                // for IDE
                String junitURI = junit.framework.Test.class
                        .getResource("Test.class")
                        .toURI()
                        .toString();
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
        doAnswer(iom -> Collections.emptyList()).when(session).resolveDependencies(any(), eq(PathScope.MAIN_COMPILE));
        doAnswer(iom -> artifacts).when(session).resolveDependencies(any(), eq(PathScope.TEST_COMPILE));

        return session;
    }

    @Provides
    @Singleton
    @SuppressWarnings("unused")
    private static Project createProject() {
        ProjectStub stub = new ProjectStub();
        ArtifactStub artifact = new ArtifactStub("myGroupId", "myArtifactId", null, "1.0-SNAPSHOT", "jar");
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
        stub.setBasedir(Paths.get(MojoExtension.getBasedir()));
        return stub;
    }

    //    @Provides
    //    @SuppressWarnings("unused")
    //    ProjectManager createProjectManager(InternalSession session) {
    //        return session.getService(ProjectManager.class);
    //    }

    //    @Provides
    //    @SuppressWarnings("unused")
    //    ArtifactManager createArtifactManager(InternalSession session) {
    //        return session.getService(ArtifactManager.class);
    //    }

    private Log setMockLogger(AbstractCompilerMojo mojo) throws IllegalAccessException {
        Log log = mock(Log.class);
        setVariableValueToObject(mojo, "logger", log);
        return log;
    }

    private static void execute(Mojo mojo) {
        try {
            mojo.execute();
        } catch (CompilationFailureException e) {
            throw new RuntimeException(e.getLongMessage(), e);
        }
    }

    /*
    private CompilerMojo getCompilerMojo( String pomXml )
        throws Exception
    {
        File testPom = new File( getBasedir(), pomXml );

        CompilerMojo mojo = (CompilerMojo) lookupMojo( "compile", testPom );

        setVariableValueToObject( mojo, "projectArtifact", new ArtifactStub() );
        setVariableValueToObject( mojo, "compilePath", Collections.EMPTY_LIST );
        setVariableValueToObject( mojo, "session", getMockMavenSession() );
        setVariableValueToObject( mojo, "project", getMockMavenProject() );
        setVariableValueToObject( mojo, "mojoExecution", getMockMojoExecution() );
        setVariableValueToObject( mojo, "source", source );
        setVariableValueToObject( mojo, "target", target );

        return mojo;
    }

    private TestCompilerMojo getTestCompilerMojo( CompilerMojo compilerMojo, String pomXml )
        throws Exception
    {
        File testPom = new File( getBasedir(), pomXml );

        TestCompilerMojo mojo = (TestCompilerMojo) lookupMojo( "testCompile", testPom );

        setVariableValueToObject( mojo, "log", new DebugEnabledLog() );

        File buildDir = (File) getVariableValueFromObject( compilerMojo, "buildDirectory" );
        File testClassesDir = new File( buildDir, "test-classes" );
        setVariableValueToObject( mojo, "outputDirectory", testClassesDir );

        List<String> testClasspathList = new ArrayList<>();

        Artifact junitArtifact = mock( Artifact.class );
        ArtifactHandler handler = mock( ArtifactHandler.class );
        when( handler.isAddedToClasspath() ).thenReturn( true );
        when( junitArtifact.getArtifactHandler() ).thenReturn( handler );

        File artifactFile;
        String localRepository = System.getProperty( "localRepository" );
        if ( localRepository != null )
        {
            artifactFile = new File( localRepository, "junit/junit/3.8.1/junit-3.8.1.jar" );
        }
        else
        {
            // for IDE
            String junitURI = org.junit.Test.class.getResource( "Test.class" ).toURI().toString();
            junitURI = junitURI.substring( "jar:".length(), junitURI.indexOf( '!' ) );
            artifactFile = new File( URI.create( junitURI ) );
        }
        when ( junitArtifact.getFile() ).thenReturn( artifactFile );

        testClasspathList.add( artifactFile.getAbsolutePath() );
        testClasspathList.add( compilerMojo.getOutputDirectory().toString() );

        String testSourceRoot = testPom.getParent() + "/src/test/java";
        setVariableValueToObject( mojo, "compileSourceRoots", Collections.singletonList( testSourceRoot ) );

        Project project = getMockMavenProject();
        project.setFile( testPom );
        project.addCompileSourceRoot("/src/main/java" );
        project.setArtifacts( Collections.singleton( junitArtifact )  );
        project.getBuild().setOutputDirectory( new File( buildDir, "classes" ).getAbsolutePath() );
        setVariableValueToObject( mojo, "project", project );
        setVariableValueToObject( mojo, "testPath", testClasspathList );
        setVariableValueToObject( mojo, "session", getMockMavenSession() );
        setVariableValueToObject( mojo, "mojoExecution", getMockMojoExecution() );
        setVariableValueToObject( mojo, "source", source );
        setVariableValueToObject( mojo, "target", target );

        return mojo;
    }


    private MavenProject getMockMavenProject()
    {
        MavenProject mp = new MavenProject();
        mp.getBuild().setDirectory( "target" );
        mp.getBuild().setOutputDirectory( "target/classes" );
        mp.getBuild().setSourceDirectory( "src/main/java" );
        mp.getBuild().setTestOutputDirectory( "target/test-classes" );
        return mp;
    }

    private MavenSession getMockMavenSession()
    {
        MavenSession session = mock( MavenSession.class );
        // when( session.getPluginContext( isA(PluginDescriptor.class), isA(MavenProject.class) ) ).thenReturn(
        // Collections.emptyMap() );
        when( session.getCurrentProject() ).thenReturn( getMockMavenProject() );
        return session;
    }

    private MojoExecution getMockMojoExecution()
    {
        MojoDescriptor md = new MojoDescriptor();
        md.setGoal( "compile" );

        MojoExecution me = new MojoExecution( md );

        PluginDescriptor pd = new PluginDescriptor();
        pd.setArtifactId( "maven-compiler-plugin" );
        md.setPluginDescriptor( pd );

        return me;
    }
     */

}
