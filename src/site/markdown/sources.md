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

# Declaration of source directories in Maven 4

By default, Maven compiles all `*.java` files in the `src/main/java` directory as the main Java code
and all `*.java` files in the `src/test/java` directory as the test Java code.
This is suitable for a project in a single Java module targeting a single Java release.
This page describes how to use different or additional directories in Maven 4.

The Maven 3 `<sourceDirectory>` and `<testSourceDirectory>` elements are deprecated
and should be replaced by the new `<sources>` element introduced in Maven 4.
This new element allows multi-source, multi-release and multi-module projects,
as shown in sub-sections of this page.
Instead of:

```xml
<project>
  <build>
    <sourceDirectory>my-custom-dir/foo</sourceDirectory>
    <testSourceDirectory>my-custom-dir/bar</testSourceDirectory>
  </build>
</project>
```

One can write:

```xml
<project>
  <build>
    <sources>
      <source>
        <scope>main</scope>     <!-- Can be omited as it is the default -->
        <directory>my-custom-dir/foo</directory>
      </source>
      <source>
        <scope>test</scope>
        <directory>my-custom-dir/bar</directory>
      </source>
    </sources>
  <build>
</project>
```

Note that the declaration of a `<sources>` element *replaces* the default values.
If a `<source>` element is defined for one of the `main` or `test` scopes, then a
`<source>` element should generally be defined for the other scope
even if the latter use the default directory.
See the example in next sub-section.


## Declaration of many source directories

External plugins such as `build-helper-maven-plugin` are no longer needed
and should be replaced by the built-in `<sources>` elements as shown below.
Note that the directories of the first and last `<source>` elements are omitted
as their default values are `src/main/java` and `src/test/java` respectively.

```xml
<project>
  <build>
    <sources>
      <source>
        <scope>main</scope>
        <!-- Default directory is src/main/java -->
      </source>
      <source>
        <scope>main</scope>     <!-- Can be omited as it is the default -->
        <directory>src/extension/java</directory>
      </source>
      <source>
        <scope>test</scope>
        <!-- Default directory is src/test/java -->
      </source>
    </sources>
  <build>
</project>
```


## Multi-release project

The compiler plugin automatically handles multiple executions of `javac` with different `--release` option values
together with automatic adjustments of class-path, module-path and output directories for producing a multi-release project.
Example:

```xml
<project>
  <build>
    <sources>
      <source>
        <targetVersion>17</targetVersion>
        <!-- Default directory is src/main/java -->
      </source>
      <source>
        <targetVersion>21</targetVersion>
        <directory>src/main/java_21</directory>
      </source>
      <source>
        <scope>test</scope>
        <!-- Default directory is src/test/java -->
      </source>
    </sources>
  </build>
</project>
```


## Multi-module project

Maven 4 supports the Java [module source hierarchy](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#directory-hierarchies)
with the caveat that as of February 2026, not all plugins have been updated yet.
Compared to multiple Maven sub-projects, using multiple Java modules in a single Maven sub-project has advantages such as
resolving compiler warnings in references to dependent modules (the converse of references to dependencies),
easier sharing of test code between modules in the Maven sub-project (no need for `test-jar`),
and easier aggregated Javadoc for modules in the Maven sub-project.
See the [modular projects](./modules.html) page for more information.
For example, a Maven sub-project containing two Java modules named `org.foo.bar.module1` and `org.foo.bar.module2`
can be declared with the following fragment in the `pom.xml` file:

```xml
<build>
  <sources>
    <source>
      <module>org.foo.bar.module1</module>
      <!-- Default directory is src/org.foo.bar.module1/main/java -->
    </source>
    <source>
      <module>org.foo.bar.module2</module>
      <!-- Default directory is src/org.foo.bar.module2/main/java -->
    </source>
    <source>
      <scope>test</scope>
      <module>org.foo.bar.module1</module>
      <!-- Default directory is src/org.foo.bar.module1/test/java -->
    </source>
    <source>
      <scope>test</scope>
      <module>org.foo.bar.module2</module>
      <!-- Default directory is src/org.foo.bar.module2/test/java -->
    </source>
  </sources>
</build>
```

The default directory layout is then as below:

```
src
├─ org.foo.bar.module1
│  ├─ main
│  │  ├─ java
│  │  │  └─ org/foo/bar/**/*.java
│  └─ test
│     └─ java
│        └─ org/foo/bar/**/*Test.java
└─ org.foo.bar.module2
   ├─ main
   │  └─ java
   │     └─ org/foo/bar/**/*.java
   └─ test
      └─ java
         └─ org/foo/bar/**/*Test.java
```


### Current support

As of October 2025, only the Maven Compiler Plugin supports module source hierarchy.
The following plugins need to be updated:

* Maven Surefire plugin
* Maven JAR plugin
* Maven Javadoc plugin


## Include/exclude filters

The Maven 3 way to declare include/exclude filters is still supported,
but should be replaced by the `<sources>` element when applicable.
Those two ways are not strictly equivalent:

* The Maven 4 way specifies filters independently for each source directory.
  These filters will be applied by all plugins that have migrated to the Maven 4 API, not only the compiler plugin.
* Conversely, the Maven 3 way specifies filters which will be applied only by the compiler plugin.
  However, these filters apply to all source directories.

The following (Maven 3) specifies a filter applied on all source directories but only by the compiler plugin:

```xml
<project>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>**/Foo*.java</exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

The following (Maven 4) specifies a filter applied only on the specified directories,
but potentially used (when relevant) by all plugins upgraded to Maven 4:

```xml
<project>
  <build>
    <sources>
      <source>
        <directory>src/main/java</directory>
        <excludes>
          <exclude>**/Foo*.java</exclude>
        </excludes>
      </source>
      <source>
        <scope>test</scope>
        <directory>src/test/java</directory>
      </source>
    </sources>
  </build>
</project>
```
