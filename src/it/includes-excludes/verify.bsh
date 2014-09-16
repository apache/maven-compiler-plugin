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

import java.io.*;
import java.util.*;
import java.util.regex.*;

try
{
    File targetDir = new File( basedir, "target" );

    File mainClass = new File( targetDir, "classes/org/apache/maven/it0055/Person.class" );
    System.out.println( "Checking for existence of: " + mainClass );
    if ( !mainClass.isFile() )
    {
        System.err.println( "FAILED!" );
        return false;
    }

    File excludedMainClass = new File( targetDir, "classes/org/apache/maven/it0055/PersonTwo.class" );
    System.out.println( "Checking for absence of: " + excludedMainClass );
    if ( excludedMainClass.exists() )
    {
        System.err.println( "FAILED!" );
        return false;
    }

    File testClass = new File( targetDir, "test-classes/org/apache/maven/it0055/PersonTest.class" );
    System.out.println( "Checking for existence of: " + testClass );
    if ( !testClass.isFile() )
    {
        System.err.println( "FAILED!" );
        return false;
    }

    File excludedTestClass = new File( targetDir, "test-classes/org/apache/maven/it0055/PersonTwoTest.class" );
    System.out.println( "Checking for absence of: " + excludedTestClass );
    if ( excludedTestClass.exists() )
    {
        System.err.println( "FAILED!" );
        return false;
    }
}
catch( Throwable t )
{
    t.printStackTrace();
    return false;
}

return true;
