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

[Annotation processing](https://docs.oracle.com/en/java/javase/23/docs/specs/man/javac.html#annotation-processing) is used to let the compiler generate source code based on annotations.
For example, the [Hibernate Processor](https://hibernate.org/orm/processor/) provides an annotation processor to generate the JPA metamodel.

## Recommended way to activate annotation processing
Up to JDK 23, the compiler automatically scanned the classpath for annotation processors and executed all found by default.
For security reasons, this got disabled by default since JDK 23 and annotation processing needs to be activated explicitly.
The recommended way for this is to list all desired processors using either the `<annotationProcessors>` plugin configuration
or, when using Maven 4 and Maven Compiler Plugin version 4.x, by declaring the processors as dependencies of type `processor`. `classpath-processor` or `modular-processor`.
Only those processors will get executed by the compiler.

The following example shows how to activate the Hibernate Processor.

### Maven 3
When using Maven 3 and Maven Compiler Plugin version 3.x you do this using the following configuration.

```xml
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
```

### Maven 4
Using Maven 4 and Maven Compiler Plugin 4.x, you can still use the same config as for Maven 3 and plugin version 3.x.
However, you can also make use of the new `processor` dependency type to shorten the configuration.
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
Each processor can be placed explicitly on one of those two kinds of path by specifying the
`classpath-processor` or `modular-processor` dependency type respectively.
If the specified type is only `processor`, then the Maven compiler plugin will try to guess on which path to place the processor.
Note that this guess is not guaranteed to be correct.
Developers are encouraged to declare a more explicit type (for example `<type>classpath-processor</type>`) when they know how the processor is intended to be used.


## Not recommended: Using the `proc` configuration

This section applies to Maven 3 and Maven 4.

If you don't want to provide a list of processors, you have to set the value of the `<proc>` configuration to either `only` or `full`.
The first will only scan the classpath for annotation processors and will execute them, while the later will also compile the code afterward.
Keep in mind that if no list of desired annotation processors is provided, using the `<proc>` configuration will execute found processors on the classpath.
**This might result in the execution of hidden and possible malicious processors.**
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
        <version>${version.maven-compiler-plugin}</version>
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

You can also just overwrite the default value of the property:

```xml
<project>
  [...]
  <properties>
    <maven.compiler.proc>full</maven.compiler.proc>
  </properties>
  [...]
</project>
```
