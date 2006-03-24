package org.apache.maven.plugin;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import org.codehaus.plexus.util.FileUtils;
import org.apache.maven.plugins.testing.AbstractMojoTestCase;

import java.io.File;

public class CompilerMojoTest
    extends AbstractMojoTestCase
{
    protected void setUp() throws Exception {

        // required for mojo lookups to work
        super.setUp();

    }

    /**
     * tests the proper discovery and configuration of the mojo
     *
     * @throws Exception
     */
    public void testCompilerTestEnvironment() throws Exception {

        File testPom = new File( getBasedir(), "target/test-classes/unit/compiler-basic-test/plugin-config.xml" );

        CompilerMojo mojo = (CompilerMojo) lookupMojo ("compile", testPom );

        assertNotNull( mojo );
    }

    /**
     * tests the ability of the plugin to compile a basic file
     *
     * @throws Exception
     */
    public void testCompilerBasic() throws Exception {

        File testPom = new File( getBasedir(), "target/test-classes/unit/compiler-basic-test/plugin-config.xml" );

        CompilerMojo mojo = (CompilerMojo) lookupMojo ("compile", testPom );

        assertNotNull( mojo );

        mojo.execute();

        assertTrue( FileUtils.fileExists( "target/test/unit/compiler-basic-test/target/classes/TestCompile1.class" ) );
    }

    /**
     * tests the ability of the plugin to respond to empty source
     *
     * @throws Exception
     */
    public void testCompilerEmptySource() throws Exception {

        File testPom = new File( getBasedir(), "target/test-classes/unit/compiler-empty-source-test/plugin-config.xml" );

        CompilerMojo mojo = (CompilerMojo) lookupMojo ("compile", testPom );

        assertNotNull( mojo );

        mojo.execute();

        assertFalse( FileUtils.fileExists( "target/test/unit/compiler-empty-source-test/target/classes/TestCompile1.class" ) );
    }

       /**
     * tests the ability of the plugin to respond to includes and excludes correctly
     *
     * @throws Exception
     */
    public void testCompilerIncludesExcludes() throws Exception
    {

        File testPom = new File( getBasedir(), "target/test-classes/unit/compiler-includes-excludes-test/plugin-config.xml" );

        CompilerMojo mojo = (CompilerMojo) lookupMojo ("compile", testPom );

        assertNotNull( mojo );

        mojo.execute();

        assertTrue( FileUtils.fileExists( "target/test/unit/compiler-includes-excludes-test/target/classes/TestCompile1.class" ) );
        assertFalse( FileUtils.fileExists( "target/test/unit/compiler-includes-excludes-test/target/classes/TestCompile2.class" ) );
        assertFalse( FileUtils.fileExists( "target/test/unit/compiler-includes-excludes-test/target/classes/TestCompile3.class" ) );
    }

       /**
     * tests the ability of the plugin to fork and successfully compile
     *
     * @throws Exception
     */
    public void testCompilerFork() throws Exception {

        File testPom = new File( getBasedir(), "target/test-classes/unit/compiler-fork-test/plugin-config.xml" );

        CompilerMojo mojo = (CompilerMojo) lookupMojo ("compile", testPom );

        assertNotNull( mojo );

        mojo.execute();

        assertTrue( FileUtils.fileExists( "target/test/unit/compiler-fork-test/target/classes/TestCompile1.class" ) );
    }

    protected void tearDown() throws Exception {

        //FileUtils.deleteDirectory( new File ("target/test/unit/compiler") );

    }
}
