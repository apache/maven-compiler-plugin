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

/*
 * A previous version of this test was looking for the following warnings in the logs:
 *
 *    - Can't extract module name from geronimo-servlet_2.4_spec-1.1.1.jar
 *      (because of invalid module name: '2' is not a Java identifier)
 *
 *    - Can't extract module name from jdom-1.0.jar
 *      (because of JDOMAbout.class found in top-level directory while unnamed package not allowed in module)
 *
 * Those warnings do not happen anymore, even if above JARs are still invalid. However, it is nevertheless
 * possible to build the project with the dependency on the classpath and an `--add-reads` option.
 * We verify by ensuring that the test file, which use JDOM, has been compiled.
 */
def targetFile = new File( basedir, 'target/test-classes/test/MyTest.class')
assert targetFile.exists()
