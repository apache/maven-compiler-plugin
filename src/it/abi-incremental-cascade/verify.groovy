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
assert new File( basedir, 'target/classes/impl/Service.class' ).exists()

// Graph state is stored in maven-status dir (not inside classes/ to avoid polluting JARs)
assert new File( basedir, 'target/maven-status/maven-compiler-plugin/classes/incremental-state' ).exists()

// Build should succeed (compilation didn't fail after cascade)
def logFile = new File( basedir, 'build.log' )
assert logFile.exists()
def content = logFile.text

// Should contain graph-related messages showing cascade behavior
// The step 3 compile should recompile more than just Model
assert !content.contains( 'COMPILATION ERROR' )

// Step 1 should be a full build via the graph strategy
assert content.contains( 'graph: full build' )

// Step 3 should cascade: Model API changed → Service recompiled
assert content.contains( 'graph cascade: recompiling' )
