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

import java.util.jar.JarFile

def log = new File(basedir,'build.log')
assert 1 == log.text.count('[INFO] Building multirelease-parent 1.0.0-SNAPSHOT') : 'parent should be built once'
assert 2 == log.text.count('[INFO] Building Base 1.0.0-SNAPSHOT')                : 'base should be built twice'
assert 1 == log.text.count('[INFO] Building multirelease-nine 1.0.0-SNAPSHOT')   : 'nine should be built once'

def mrjar = new JarFile(new File(basedir,'multirelease-base/target/multirelease-1.0.0-SNAPSHOT.jar'))

assert mrjar.manifest.mainAttributes.getValue('Multi-Release') == 'true' : 'Multi-Release attribute in manifest should be true'

assert (je = mrjar.getEntry('base/Base.class')) != null : 'jar should contain base/Base.class'
assert 52 == getMajor(mrjar.getInputStream(je))         : 'base/Base.class should have 52 as major bytecode version'
assert (je = mrjar.getEntry('mr/A.class')) != null      : 'jar should contain mr/A.class'
assert 52 == getMajor(mrjar.getInputStream(je))         : 'mr/A.class should have 52 as major bytecode version'
assert (je = mrjar.getEntry('mr/I.class')) != null      : 'jar should contain mr/I.class'
assert 52 == getMajor(mrjar.getInputStream(je))         : 'mr/I.class should have 52 as major bytecode version'

assert (je = mrjar.getEntry('META-INF/versions/9/mr/A.class')) != null : 'jar should contain META-INF/versions/9/mr/A.class'
assert 53 == getMajor(mrjar.getInputStream(je))                        : 'META-INF/versions/9/mr/A.class should have 53 as major bytecode version'

/*
  base
  base/Base.class
  mr
  mr/A.class
  mr/I.class
  META-INF
  META-INF/MANFEST.MF
  META-INF/versions
  META-INF/versions/9
  META-INF/versions/9/mr
  META-INF/versions/9/mr/A.class
  META-INF/maven
  META-INF/maven/multirelease
  META-INF/maven/multirelease/multirelease
  META-INF/maven/multirelease/multirelease/pom.xml
  META-INF/maven/multirelease/multirelease/pom.properties
*/
assert mrjar.entries().size() == 16

int getMajor(InputStream is)
{
  def dis = new DataInputStream(is)
  final String firstFourBytes = Integer.toHexString(dis.readUnsignedShort()) + Integer.toHexString(dis.readUnsignedShort())
  if (!firstFourBytes.equalsIgnoreCase("cafebabe"))
  {
    throw new IllegalArgumentException(dataSourceName + " is NOT a Java .class file.")
  }
  final int minorVersion = dis.readUnsignedShort()
  final int majorVersion = dis.readUnsignedShort()

  is.close();
  return majorVersion;
}

