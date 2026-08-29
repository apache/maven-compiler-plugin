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

# Annotation processors

[Annotation processing](https://docs.oracle.com/en/java/javase/23/docs/specs/man/javac.html#annotation-processing) generates source code based on annotations.
For example, the [Hibernate Processor](https://hibernate.org/orm/processor/) provides an annotation processor to generate the JPA metamodel.


## Recommended way to activate annotation processing

Up to JDK 23, the compiler automatically scanned the classpath for annotation processors. It executed all processors found by default.
For security reasons, this is disabled by default since JDK 23. You must activate annotation processing explicitly.
Use either the `<annotationProcessors>` plugin configuration or,
when using Maven 4 and Maven Compiler Plugin version 4.x, declare the processors as dependencies of type `processor`, `classpath-processor`, or `modular-processor`.
Only those processors are executed by the compiler.

The following example shows how to activate the Hibernate Processor.


### Maven 3

When using Maven 3 and Maven Compiler Plugin version 3.x, you do this with the following configuration.

```xml
<project>
  <build>
    <plugins>
      [...]
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>...</version>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>org.hibernate.orm</groupId>
              <artifactId>hibernate-processor</artifactId>
              <version>${version.hibernate}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
      [...]
    </plugins>
  </build>
</project>
```


### Maven 4

With Maven 4 and Maven Compiler Plugin 4.x, the way described above is deprecated. The plugin will remove it in a future version.
Configuration now uses the new `processor` dependency type. This shortens the configuration,
gives control over the placement on class-path or module-path, and makes the information available to other plugins.
The following example shows this.

```xml
<project>
  <dependencies>
    [...]
    <dependency>
      <groupId>org.hibernate.orm</groupId>
      <artifactId>hibernate-processor</artifactId>
      <version>${version.hibernate}</version>
      <type>processor</type>
    </dependency>
    [...]
  </dependencies>
</project>
```

Like ordinary dependencies, processors can be placed on the processor class-path or processor module-path.
Each processor can be placed on one of these two kinds of path. Specify the
`classpath-processor` or `modular-processor` dependency type respectively.
If the specified type is only `processor`, then the Maven compiler plugin tries to guess on which path to place the processor.
Note that this guess is not guaranteed to be correct.
When you know how the processor is intended to be used, declare a more explicit type (for example `<type>classpath-processor</type>`).


## Not recommended: Using the `proc` configuration

This section applies to Maven 3 and Maven 4.

If you do not want to provide a list of processors, set the value of the `<proc>` configuration to `only` or `full`.
The first value scans the classpath for annotation processors and executes them. The second value also compiles the code afterward.
Keep in mind that if you provide no list of desired annotation processors, the `<proc>` configuration executes the processors found on the classpath.
**This can execute hidden and possibly malicious processors.**
Therefore, using only the `proc` configuration is not recommended.

You set the value of the `<proc>` configuration like every other [configuration](/usage.html) of the Maven Compiler Plugin:

```xml
<project>
  [...]
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>...</version>
        <configuration>
          <proc>full</proc>
        </configuration>
      </plugin>
      [...]
    </plugins>
    [...]
  </build>
</project>
```

You can also overwrite the default value of the property:

```xml
<project>
  [...]
  <properties>
    <maven.compiler.proc>full</maven.compiler.proc>
  </properties>
  [...]
</project>
```
