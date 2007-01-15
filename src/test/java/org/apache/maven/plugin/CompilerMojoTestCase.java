package org.apache.maven.plugin;

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

import org.apache.maven.plugin.stubs.CompilerManagerStub;
import org.apache.maven.plugin.stubs.DebugEnabledLog;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.plugin.testing.stubs.ArtifactStub;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CompilerMojoTestCase
    extends AbstractMojoTestCase
{
    /**
     * tests the ability of the plugin to compile a basic file
     *
     * @throws Exception
     */
    public void testCompilerBasic()
        throws Exception
    {
        CompilerMojo compileMojo = getCompilerMojo( "target/test-classes/unit/compiler-basic-test/plugin-config.xml" );

        compileMojo.execute();

        File testClass = new File( compileMojo.getOutputDirectory(), "TestCompile0.class" );

        assertTrue( testClass.exists() );

        TestCompilerMojo testCompileMojo =
            getTestCompilerMojo( compileMojo, "target/test-classes/unit/compiler-basic-test/plugin-config.xml" );

        testCompileMojo.execute();

        testClass = new File( testCompileMojo.getOutputDirectory(), "TestCompile0Test.class" );

        assertTrue( testClass.exists() );
    }

    /**
     * tests the ability of the plugin to respond to empty source
     *
     * @throws Exception
     */
    public void testCompilerEmptySource()
        throws Exception
    {
        CompilerMojo compileMojo =
            getCompilerMojo( "target/test-classes/unit/compiler-empty-source-test/plugin-config.xml" );

        compileMojo.execute();

        assertFalse( compileMojo.getOutputDirectory().exists() );

        TestCompilerMojo testCompileMojo =
            getTestCompilerMojo( compileMojo, "target/test-classes/unit/compiler-empty-source-test/plugin-config.xml" );

        testCompileMojo.execute();

        assertFalse( testCompileMojo.getOutputDirectory().exists() );
    }

    /**
     * tests the ability of the plugin to respond to includes and excludes correctly
     *
     * @throws Exception
     */
    public void testCompilerIncludesExcludes()
        throws Exception
    {
        CompilerMojo compileMojo =
            getCompilerMojo( "target/test-classes/unit/compiler-includes-excludes-test/plugin-config.xml" );

        Set includes = new HashSet();
        includes.add( "**/TestCompile4*.java" );
        setVariableValueToObject( compileMojo, "includes", includes );

        Set excludes = new HashSet();
        excludes.add( "**/TestCompile2*.java" );
        excludes.add( "**/TestCompile3*.java" );
        setVariableValueToObject( compileMojo, "excludes", excludes );

        compileMojo.execute();

        File testClass = new File( compileMojo.getOutputDirectory(), "TestCompile2.class" );
        assertFalse( testClass.exists() );

        testClass = new File( compileMojo.getOutputDirectory(), "TestCompile3.class" );
        assertFalse( testClass.exists() );

        testClass = new File( compileMojo.getOutputDirectory(), "TestCompile4.class" );
        assertTrue( testClass.exists() );

        TestCompilerMojo testCompileMojo = getTestCompilerMojo( compileMojo,
                                                                "target/test-classes/unit/compiler-includes-excludes-test/plugin-config.xml" );

        setVariableValueToObject( testCompileMojo, "testIncludes", includes );
        setVariableValueToObject( testCompileMojo, "testExcludes", excludes );

        testCompileMojo.execute();

        testClass = new File( testCompileMojo.getOutputDirectory(), "TestCompile2TestCase.class" );
        assertFalse( testClass.exists() );

        testClass = new File( testCompileMojo.getOutputDirectory(), "TestCompile3TestCase.class" );
        assertFalse( testClass.exists() );

        testClass = new File( testCompileMojo.getOutputDirectory(), "TestCompile4TestCase.class" );
        assertTrue( testClass.exists() );
    }

    /**
     * tests the ability of the plugin to fork and successfully compile
     *
     * @throws Exception
     */
    public void testCompilerFork()
        throws Exception
    {
        CompilerMojo compileMojo = getCompilerMojo( "target/test-classes/unit/compiler-fork-test/plugin-config.xml" );

        compileMojo.execute();

        File testClass = new File( compileMojo.getOutputDirectory(), "TestCompile1.class" );
        assertTrue( testClass.exists() );

        TestCompilerMojo testCompileMojo =
            getTestCompilerMojo( compileMojo, "target/test-classes/unit/compiler-fork-test/plugin-config.xml" );

        testCompileMojo.execute();

        testClass = new File( testCompileMojo.getOutputDirectory(), "TestCompile1TestCase.class" );
        assertTrue( testClass.exists() );
    }

    public void testOneOutputFileForAllInput()
        throws Exception
    {
        CompilerMojo compileMojo =
            getCompilerMojo( "target/test-classes/unit/compiler-one-output-file-test/plugin-config.xml" );

        setVariableValueToObject( compileMojo, "compilerManager", new CompilerManagerStub() );

        compileMojo.execute();

        File testClass = new File( compileMojo.getOutputDirectory(), "compiled.class" );
        assertTrue( testClass.exists() );

        TestCompilerMojo testCompileMojo = getTestCompilerMojo( compileMojo,
                                                                "target/test-classes/unit/compiler-one-output-file-test/plugin-config.xml" );

        setVariableValueToObject( testCompileMojo, "compilerManager", new CompilerManagerStub() );

        testCompileMojo.execute();

        testClass = new File( testCompileMojo.getOutputDirectory(), "compiled.class" );
        assertTrue( testClass.exists() );
    }

    public void testCompilerArgs()
        throws Exception
    {
        CompilerMojo compileMojo = getCompilerMojo( "target/test-classes/unit/compiler-args-test/plugin-config.xml" );

        setVariableValueToObject( compileMojo, "compilerManager", new CompilerManagerStub() );

        compileMojo.execute();

        File testClass = new File( compileMojo.getOutputDirectory(), "compiled.class" );
        assertTrue( testClass.exists() );
    }

    public void testOneOutputFileForAllInput2()
        throws Exception
    {
        CompilerMojo compileMojo =
            getCompilerMojo( "target/test-classes/unit/compiler-one-output-file-test2/plugin-config.xml" );

        setVariableValueToObject( compileMojo, "compilerManager", new CompilerManagerStub() );

        Set includes = new HashSet();
        includes.add( "**/TestCompile4*.java" );
        setVariableValueToObject( compileMojo, "includes", includes );

        Set excludes = new HashSet();
        excludes.add( "**/TestCompile2*.java" );
        excludes.add( "**/TestCompile3*.java" );
        setVariableValueToObject( compileMojo, "excludes", excludes );

        compileMojo.execute();

        File testClass = new File( compileMojo.getOutputDirectory(), "compiled.class" );
        assertTrue( testClass.exists() );

        TestCompilerMojo testCompileMojo = getTestCompilerMojo( compileMojo,
                                                                "target/test-classes/unit/compiler-one-output-file-test2/plugin-config.xml" );

        setVariableValueToObject( testCompileMojo, "compilerManager", new CompilerManagerStub() );
        setVariableValueToObject( testCompileMojo, "testIncludes", includes );
        setVariableValueToObject( testCompileMojo, "testExcludes", excludes );

        testCompileMojo.execute();

        testClass = new File( testCompileMojo.getOutputDirectory(), "compiled.class" );
        assertTrue( testClass.exists() );
    }

    public void testCompileFailure()
        throws Exception
    {
        CompilerMojo compileMojo = getCompilerMojo( "target/test-classes/unit/compiler-fail-test/plugin-config.xml" );

        setVariableValueToObject( compileMojo, "compilerManager", new CompilerManagerStub( true ) );

        try
        {
            compileMojo.execute();

            fail( "Should throw an exception" );
        }
        catch ( CompilationFailureException e )
        {
            //expected
        }
    }

    public void testCompileFailOnError()
        throws Exception
    {
        CompilerMojo compileMojo =
            getCompilerMojo( "target/test-classes/unit/compiler-failonerror-test/plugin-config.xml" );

        setVariableValueToObject( compileMojo, "compilerManager", new CompilerManagerStub( true ) );

        try
        {
            compileMojo.execute();
            assertTrue( true );
        }
        catch ( CompilationFailureException e )
        {
            fail( "The compilation error should have been consumed because failOnError = false" );
        }
    }

    private CompilerMojo getCompilerMojo( String pomXml )
        throws Exception
    {
        File testPom = new File( getBasedir(), pomXml );

        CompilerMojo mojo = (CompilerMojo) lookupMojo( "compile", testPom );

        setVariableValueToObject( mojo, "log", new DebugEnabledLog() );
        setVariableValueToObject( mojo, "projectArtifact", new ArtifactStub() );
        setVariableValueToObject( mojo, "classpathElements", Collections.EMPTY_LIST );

        assertNotNull( mojo );

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

        List testClasspathList = new ArrayList();
        testClasspathList.add( System.getProperty( "localRepository" ) + "/junit/junit/3.8.1/junit-3.8.1.jar" );
        testClasspathList.add( compilerMojo.getOutputDirectory().getPath() );
        setVariableValueToObject( mojo, "classpathElements", testClasspathList );

        String testSourceRoot = testPom.getParent() + "/src/test/java";
        setVariableValueToObject( mojo, "compileSourceRoots", Collections.singletonList( testSourceRoot ) );

        return mojo;
    }
}
