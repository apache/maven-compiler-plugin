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
content = logFile.text

// JDK (,1.9): 1 warning: 
// [WARNING] bootstrap class path not set in conjunction with -source 1.6
// [INFO] 1 warning
// 
// JDK [1.9,):
// [WARNING] bootstrap class path not set in conjunction with -source 1.6
// [WARNING] source value 1.6 is obsolete and will be removed in a future release
// [WARNING] target value 1.6 is obsolete and will be removed in a future release
// [WARNING] To suppress warnings about obsolete options, use -Xlint:-options.
// [INFO] 4 warnings 
assert content.contains( '[WARNING] bootstrap class path not set in conjunction with -source 1.6' )
assert content.contains( '1 error' )
