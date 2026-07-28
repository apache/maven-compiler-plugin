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

def exampleClass = new File( basedir, 'target/classes/org/example/Example.class' )
assert exampleClass.isFile()
def exampleMajorVersion = exampleClass.bytes[7] & 0xFF
// major_version: 52 = Java 8, from the base-compile execution.
assert exampleMajorVersion == 52

def moduleInfoClass = new File( basedir, 'target/classes/module-info.class' )
assert moduleInfoClass.isFile()
def moduleInfoMajorVersion = moduleInfoClass.bytes[7] & 0xFF
// major_version: 55 = Java 11, from the base-modules-compile execution.
assert moduleInfoMajorVersion == 55
