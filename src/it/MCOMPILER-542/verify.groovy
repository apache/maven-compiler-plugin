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

// Check if the javap tool is available
def javapTool = java.util.spi.ToolProvider.findFirst("javap")
assert javapTool.isPresent() : "javap tool not found. Make sure you have the JDK installed."

def moduleDescriptor = new File(basedir, "target/classes/module-info.class")
// Create a list of arguments to pass to the javap tool
String[] args = ["-v", moduleDescriptor]

def swout = new StringWriter(), swerr = new StringWriter()
// Execute the javap tool with args
def result = javapTool.get().run(new PrintWriter(swout), new PrintWriter(swerr), args)
println swerr.toString().isEmpty() ? "javap output:\n$swout" : "javap error:\n$swerr"
assert (result == 0) : "javap run failed"

// Assertions of module content
def out = swout.toString()
assert out.contains('// "java.base" ACC_MANDATED') : "module not found in module-info.class"
assert out.contains('// "java.logging"') : "module not found in module-info.class"
assert out.contains('// "jdk.zipfs"') : "module not found in module-info.class"
assert out.contains('// "org.slf4j.jdk.platform.logging"') : "module not found in module-info.class"
assert out.contains('// 2.0.9') : "version of org.slf4j.jdk.platform.logging module not found"

// Validation that the module-info should not contain the full java version but the spec version.
def javaVersion = System.getProperty('java.version')
def javaSpecVersion = System.getProperty('java.specification.version')
if (javaVersion != javaSpecVersion) { // handle the case when is the first release
  assert !out.contains('// ' + javaVersion) : "full java version found in module descriptor"
}
assert out.contains('// ' + javaSpecVersion) : "java specification version not found in module descriptor"

// Additional validation that the checksum is always the same: useful because constant pool reordering happens when
// transforming bytecode, then we need to check results precisely
def checksumMap = [
    '21': 'SHA-256 checksum ccc6515c8fc1bf4e675e205b2a5200d02545b06014b304c292eeddc68cffee8d',
    '17': 'SHA-256 checksum 102f24c71aff97210d66ef791b7d56f8a25ff8692d2c97b21682bc7170aaca9c',
    '11': 'MD5 checksum 5779cc6044dcba6ae4060e5a2f8a32c8'
]

def expectedChecksum = checksumMap[javaSpecVersion]
if (expectedChecksum) {
    println "Java version: $javaVersion"
    assert out.contains(expectedChecksum) : "checksum doesn't match expected output"
}
