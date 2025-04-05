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

def baseVersion = 59; // Java 15
def nextVersion = 60; // Java 16
def lastVersion = 61; // Java 17

assert baseVersion == getMajor(new File( basedir, "target/classes/foo/MainFile.class"))
assert baseVersion == getMajor(new File( basedir, "target/classes/foo/OtherFile.class"))
assert nextVersion == getMajor(new File( basedir, "target/classes/META-INF/versions/16/foo/OtherFile.class"))
assert lastVersion == getMajor(new File( basedir, "target/classes/META-INF/versions/17/foo/YetAnotherFile.class"))

int getMajor(File file)
{
  assert file.exists()
  def dis = new DataInputStream(new FileInputStream(file))
  final String firstFourBytes = Integer.toHexString(dis.readUnsignedShort()) + Integer.toHexString(dis.readUnsignedShort())
  if (!firstFourBytes.equalsIgnoreCase("cafebabe"))
  {
    throw new IllegalArgumentException(dataSourceName + " is not a Java .class file.")
  }
  final int minorVersion = dis.readUnsignedShort()
  final int majorVersion = dis.readUnsignedShort()

  dis.close()
  return majorVersion
}
