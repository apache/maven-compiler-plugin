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
def content = logFile.text

// With proc=none (annotation processing disabled), changing exactly one of three independent
// classes must recompile only the modified file, on every Java version. This is the documented
// workaround for Java < 23, where the default otherwise conservatively rebuilds all files.
assert content.contains( 'Compiling 1 modified source file' ) :
        'Expected only the single modified source file to be recompiled'

// It must NOT fall back to a full rebuild of the whole source set.
assert !content.contains( 'Recompiling all files because at least one source file changed' ) :
        'proc=none recompiled all files after a single-file change'
