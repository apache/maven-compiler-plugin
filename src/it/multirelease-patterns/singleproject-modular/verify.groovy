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

def baseVersion = 52 // Java 8
def mr1Version = 53; // Java 9
def mr2Version = 61; // Java 17

def mrjar = new JarFile(new File(basedir,'target/multirelease-1.0.0-SNAPSHOT.jar'))

assert (je = mrjar.getEntry('base/Base.class')) != null
assert baseVersion == getMajor(mrjar.getInputStream(je))
assert (je = mrjar.getEntry('mr/A.class')) != null
assert baseVersion == getMajor(mrjar.getInputStream(je))
assert (je = mrjar.getEntry('mr/I.class')) != null
assert baseVersion == getMajor(mrjar.getInputStream(je))

assert mrjar.manifest.mainAttributes.getValue('Multi-Release') == 'true'

assert (je = mrjar.getEntry('META-INF/versions/9/mr/A.class')) != null
assert mr1Version == getMajor(mrjar.getInputStream(je))
assert (je = mrjar.getEntry('META-INF/versions/9/module-info.class')) != null
assert mr1Version == getMajor(mrjar.getInputStream(je))

assert (je = mrjar.getEntry('META-INF/versions/17/mr/A.class')) != null
assert mr2Version == getMajor(mrjar.getInputStream(je))

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
  META-INF/versions/9/module-info.class
  META-INF/versions/17
  META-INF/versions/17/mr
  META-INF/versions/17/mr/A.class
  META-INF/maven
  META-INF/maven/multirelease
  META-INF/maven/multirelease/multirelease
  META-INF/maven/multirelease/multirelease/pom.xml
  META-INF/maven/multirelease/multirelease/pom.properties
*/
assert mrjar.entries().size() == 20

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

