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

# Multi Release

[JEP-238](https://openjdk.org/jeps/238) introduced the support of multi-release JARs.
This means your JAR can contain classes that depend on the Java version.
Based on the runtime, Java picks the best matching version of a class.
The output files of a multi-release project are organized as below:

```
.
├─ A.class
├─ B.class
├─ C.class
├─ D.class
└─ META-INF
   ├─ MANIFEST.MF { Multi-Release: true }
   └─ versions
      ├─ 9
      │  ├─ A.class
      │  └─ B.class
      └─ 10
         ├─ A.class
         └─ C.class
```

With the `Multi-Release: true` flag in the `MANIFEST.MF` file,
the Java runtime also looks inside `META-INF/versions` for version-specific classes.
Otherwise, only the base classes are used.


## Challenges

The theory behind multi-release JARs is simple, but the practice can become complex.
You must ensure that all classes stay in sync.
If you add a method to one class, add it to the other classes as well.
Test the JAR with all targeted Java versions.
Think twice before you turn your JAR into a multi-release JAR.
Such JARs can be hard to read, maintain and test.
In general, applications do not need this. It is useful only for a widely distributed application where you do not control the targeted Java runtime.
Libraries must decide: do I need this new Java feature? Can I make this Java version the new requirement?

There are a couple of important facts to know when you create multi-release JARs:

* The Java compiler must be called for every different version.
  With Maven 3, this requires either multiple Maven Projects/Modules or extra compiler execution-blocks in the POM
  (like [older projects with module-info](./examples/module-info.html)).
* The `Multi-Release: true` attribute is only recognized when the classes are in a JAR.
  You cannot test the classes in `target/classes/META-INF/versions/${release}/` with Maven 3.


## Maven 3

Maven 3 proposed many different patterns for building multi-release projects.
One pattern is to create a sub-project for each version.
The project must be built with the highest required version of the JDK.
A `--release` option is specified in each sub-project.
If desired, you can use toolchains for compiling and testing with the matching Java version.
The [maven-jep238](https://github.com/hboutemy/maven-jep238) project demonstrates this pattern.
The downside is that a hierarchical structure is required even though the result is 1 artifact.

Another pattern is to use the [Multi Release JAR Maven Plugin](https://github.com/metlos/multi-release-jar-maven-plugin).
This approach introduces a new packaging type. An extra plugin handles the multiple executions of the Maven Compiler Plugin.
These executions are now handled by the `perReleaseConfiguration` of the `multi-release-jar-maven-plugin`.
What is not covered is how to test every class.

See the [Maven Compiler Plugin integration tests](https://github.com/apache/maven-compiler-plugin/tree/master/src/it/multirelease-patterns)
for examples of small projects using the following patterns:

* Maven sub-projects
* Multi projects
* Single project (runtime)
* Single project (toolchains)
* Maven extension + plugin


## Maven 4

Building a multi-release project is much easier with version 4 of the Maven Compiler Plugin.
The source code for all versions goes into different directories of the same Maven project.
These directories are declared together with the Java release as below:

```xml
<build>
  [...]
  <sources>
    <source>
      <directory>src/main/java</directory>
      <targetVersion>17</targetVersion>
    </source>
    <source>
      <directory>src/main/java_21</directory>
      <targetVersion>21</targetVersion>
    </source>
    <source>
      <scope>test</scope>
      <directory>src/test/java</directory>
      <targetVersion>21</targetVersion>     <!-- Can often be omitted for tests -->
  </sources>
  [...]
</build>
```

The Maven Compiler plugin invokes `javac` once for each target version in increasing version order.
It sets the `--release` option to the given `<targetVersion>` value.
It adds the classes of previous versions to the class-path or module-path. The most recent versions have precedence.
The compiled classes are written in the `target/classes` and `target/classes/META-INF/versions` directories.
