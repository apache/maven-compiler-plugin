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

// All class files should exist
assert new File( basedir, 'target/classes/api/Model.class' ).exists()
assert new File( basedir, 'target/classes/impl/Helper.class' ).exists()
assert new File( basedir, 'target/classes/impl/Service.class' ).exists()

// ABI state lives alongside class files; manifest is in target/ for reactor
assert new File( basedir, 'target/classes/.incremental-state' ).exists()
assert new File( basedir, 'target/.abi-fingerprints' ).exists()

// Build log should show incremental messages
def logFile = new File( basedir, 'build.log' )
assert logFile.exists()
def content = logFile.text

// Step 1 should be a full build
assert content.contains( 'full build' ) || content.contains( 'Compiling 3 source files' )

// Step 3 should show incremental behavior (not recompiling all 3 files)
// The ABI strategy should detect body-only change
assert content.contains( 'incremental' ) || content.contains( '1 file' )
