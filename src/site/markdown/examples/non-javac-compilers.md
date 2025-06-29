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

# Using Non-Javac Compilers

Contrary to this plugin's name, the Compiler Plugin does not compile the
sources of your project by itself. To compile, the Compiler Plugin uses
another class to compile them.

The parameter `compilerId` determines which class will be used.

By default, the version 4 of Maven Compiler Plugin uses the `javac` compiler
bundled in the `javax.tools` package of the standard Java library.
But it is possible to use any other compiler, as long the
implementation declares itself as a `javax.tools.JavaCompiler` service.
To use such an implementation:

* Add the compiler implementation in the Maven compiler plugin dependencies.
* Declare the compiler's identifier in the `compilerId` element of the Maven plugin configuration.
  The identifier value shall be the value returned by the `JavaCompiler.name()` method of the implementation.

The example below shows how to use the Eclipse compiler `ecj`.
Note: This configuration requires Maven 4 with Maven Compiler Plugin 4.

```xml
<project>
  [...]
  <build>
    [...]
    <plugins>
      [...]
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>...</version>
        <configuration>
          <compilerId>ecj</compilerId>
        </configuration>
        <dependencies>
          <dependency>
            <groupId>org.eclipse.jdt</groupId>
            <artifactId>ecj</artifactId>
            <version>...</version>
          </dependency>
        </dependencies>
      </plugin>
    </plugins>
  </build>
</project>
```


## Maven 3

Version 3 of the Maven Compiler Plugin does not use the compiler directly.
Instead, it uses an intermediate layer called the Plexus Compiler.
The [Plexus Compiler](https://codehaus-plexus.github.io/plexus-compiler/) component
has some compiler identifiers available under the group `org.codehaus.plexus`.
To use any of the non-javac compilers with version 3.x of the Maven Compiler plugin,
you need to declare a dependency to the Plexus artifact in your project's `pom.xml`.
The example below shows how to use the `csharp` compiler:

```xml
<project>
  [...]
  <build>
    [...]
    <plugins>
      [...]
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>...</version>
        <configuration>
          <compilerId>csharp</compilerId>
        </configuration>
        <dependencies>
          <dependency>
            <groupId>org.codehaus.plexus</groupId>
            <artifactId>plexus-compiler-csharp</artifactId>
            <version>...</version>
          </dependency>
        </dependencies>
      </plugin>
    </plugins>
  </build>
</project>
```
