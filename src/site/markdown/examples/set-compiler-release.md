<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Setting the `--release` of the Java Compiler

Starting with JDK 9, the `javac` executable can accept the `--release` option
to specify against which Java SE release you want to build the project.
For example, you have JDK 17 installed and used by Maven,
but you want to build the project against Java 11.
The `--release` option ensures that the code is compiled following
the rules of the programming language of the specified release,
and that generated classes target the release as well as the public API of that release.
This means that, unlike the old `--source` and `--target` options,
the compiler will detect and generate an error when using APIs that don't exist in previous releases of Java SE.

The preferred was to specify the release depends on the Maven version in use.

## Maven 3
Since version 3.6 of the Compiler Plugin, this option can be provided either via a property:

```xml
<project>
  [...]
  <properties>
    <maven.compiler.release>8</maven.compiler.release>
  </properties>
  [...]
</project>
```

or by configuring the plugin directly:

```xml
<project>
  [...]
  <build>
    [...]
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>...</version>
        <configuration>
          <release>11</release>
        </configuration>
      </plugin>
    </plugins>
    [...]
  </build>
  [...]
</project>
```

## Maven 4
Since version 4 of the compiler plugin, which requires Maven 4,
the preferred way to specify the release is together with the source declaration.
This is the recommended way because it makes the creation of
[multi-releases](../multirelease.html) projects easier.

```xml
<project>
  [...]
  <sources>
    <source>
      <targetVersion>11</targetVersion>
    </source>
  </sources>
  [...]
</project>
```

**Note:** The value in the `release` parameter follows
[Java's new Version-String Scheme (JEP 223)](https://openjdk.org/jeps/223) adopted since Java 9.
As such, the release number does not start with 1.x anymore.
Also note that the supported `release` targets include the release of the currently used JDK
plus a limited number of previous releases.
