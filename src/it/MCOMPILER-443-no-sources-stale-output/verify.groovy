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
def log = new File(basedir, 'build.log').text

// First invocation (-Pwith-sources) compiles Foo.java.
assert log.contains('Foo.java')

// Second invocation has no sourceDirectory at all: MCOMPILER-443 is that this used to leave the first
// invocation's output behind forever instead of forgetting it along with the sources.
assert log.contains('No sources to compile')
assert !new File(basedir, 'target/classes/demo/Foo.class').exists()
