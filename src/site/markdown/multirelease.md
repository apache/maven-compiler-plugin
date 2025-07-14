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

With [JEP-238](http://openjdk.java.net/jeps/238) the support of multirelease JARs was introduced.
This means that you can have Java version dependent classes inside one JAR.
Based on the runtime, it will pick up the best matching version of a class.
The files of a multi-release project are organized like below:

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
the Java runtime will also look inside `META-INF/versions` for version specific classes,
otherwise only the base classes are used.


## Challenges

The theory behind multi release JARs is quite simple, but in practice it can become quite complex.
You must ensure that all classes stay in sync;
if you add a method to one class, don't forget to add it to the other classes as well.
The best is to test the JAR with all targeted Java versions.
You should think twice before turning your JAR into a multi release JAR,
because such JARs can be hard to read, maintain and test.
In general applications don't need this, unless it is a widely distributed application and you don't control the targeted Java runtime.
Libraries should make a decision based on: do I need this new Java feature? Can I make this Java version the new requirement?

There are a couple of important facts one should know when creating Multi Release JARs:

* The Java compiler must be called for every different version.
  With Maven 3, it requires either having multiple Maven Projects/Modules or adding extra compiler execution-blocks to the POM
  (like [older projects with module-info](./examples/module-info.html)).
* The `Multi-Release: true` attribute is only recognized when the classes are in a JAR.
  In other words, you cannot test the classes put in `target/classes/META-INF/versions/${release}/` with Maven 3.


## Maven 3

Maven 3 proposed many different patterns for building multi-release project.
One pattern is to create a sub-project for each version.
The project needs to be build with the highest required version of the JDK,
and a `--release` option is specified in each sub-project.
If desired, toolchains can be used for compiling and testing with the matching Java version.
This pattern is demonstrated in the [maven-jep238](https://github.com/hboutemy/maven-jep238) project example.
The downside it that a hierarchical structure is required even though the result is just 1 artifact.

Another pattern is to use the [Multi Release JAR Maven Plugin](https://github.com/metlos/multi-release-jar-maven-plugin).
This approach introduces a new packaging type and an extra plugin takes care of the multiple executions of the Maven Compiler Plugin,
but these are now handled by the `perReleaseConfiguration` of the `multi-release-jar-maven-plugin`.
What's not covered is how to test every class.

See [Maven Compiler Plugin integration tests](https://github.com/apache/maven-compiler-plugin/tree/master/src/it/multirelease-patterns)
for examples of small projects using the following patterns:

* Maven sub-projects
* Multi projects
* Single project (runtime)
* Single project (toolchains)
* Maven extension + plugin


## Maven 4

Building a multi-release project is much easier with version 4 of the Maven Compiler Plugin.
The source code for all versions are placed in different directories of the same Maven project.
These directories are declared together with the Java release like below:

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
      <targetVersion>17</targetVersion>
  </sources>
  [...]
</build>
```

The Maven Compiler plugin will take care of invoking `javac` once for each target version in increasing version order,
with the `--release` option set to the given `<targetVersion>` value, and
with the classes of previous versions added to the class-path or module-path with most recent versions having precedence.
The compiled classes are written in the appropriate `target/classes` or `target/classes/META-INF/versions` directory.
