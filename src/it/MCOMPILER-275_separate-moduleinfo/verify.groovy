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
def log = new File( basedir, 'build.log').text

assert log.count( "[INFO] Toolchain in maven-compiler-plugin: JDK" ) == 1

assert log.count( "[INFO] Recompiling the module because of changed source code." ) == 1
assert log.count( "[INFO] Recompiling the module because of added or removed source files." ) == 1
assert log.count( "[INFO] Recompiling the module because of changed dependency." ) == 1

// major_version: 52 = java8 -> execution id "base-compile"
assert new File( basedir, 'target/classes/com/foo/MyClass.class' ).bytes[7] == 52
// major_version: 53 = java9 -> execution id "default-compile"
assert new File( basedir, 'target/classes/module-info.class' ).bytes[7] == 53
