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
def logFile = new File( basedir, 'build.log' )
assert logFile.exists()

def content = logFile.getText('UTF-8')

def causedByExpected = content.contains ( 'Caused by: org.apache.maven.plugin.compiler.CompilationFailureException:' )
def twoFilesBeingCompiled = content.contains ( 'Compiling 2 source files' )
def checkResult = content.contains ( 'BUILD FAILURE' )
def compilationFailure1 = content.contains( '[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:')
def compilationFailure2 = content.contains( ':compile (default-compile) on project blah: Cannot compile')

println "Jenkins: causedByExpected:${causedByExpected} twoFilesBeingCompiled:${twoFilesBeingCompiled} checkResult: ${checkResult} compilationFailure1: ${compilationFailure1} compilationFailure2: ${compilationFailure2}"

// We need to combine different identification to handle differences between OS's and JDK's.
def finalResult = twoFilesBeingCompiled && checkResult && causedByExpected && compilationFailure1 && compilationFailure2

if ( !finalResult ) {
  throw new RuntimeException( "log does not contain expected result to be failed but <startLog>" + content + "</startLog>")
}
