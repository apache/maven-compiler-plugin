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

# Setting the `--source` and `--target` of the Java Compiler

**Using `--source` and `--target` options is not recommended.**
If you're are using version 3.13.0 or newer of the Compiler Plugin,
use the recommended [release](./set-compiler-release.html) configuration instead.

Sometimes you may need to compile a certain project to a different version than what you are currently using.
The `javac` command can accept such options using `--source` and `--target`.
The Compiler Plugin can also be configured to provide these options during compilation.
You have to set the version following [Java's new Version-String Scheme (JEP 223)](https://openjdk.org/jeps/223).

For example, if you want to use the Java 8 language features and also want the compiled classes to be compatible with JVM 8 (former 1.8),
you can either add the two following properties, which are the default property names for the plugin parameters:

```xml
<project>
  [...]
  <properties>
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
  </properties>
  [...]
</project>
```

or configure the plugin directly:

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
          <source>8</source>
          <target>8</target>
        </configuration>
      </plugin>
    </plugins>
    [...]
  </build>
  [...]
</project>
```

**Note:** Merely setting the `target` option does not guarantee that your code actually runs on a JDK with the specified version.
The pitfall is unintended usage of APIs that only exist in later JDKs which would make your code fail at runtime with a linkage error.
To avoid this issue, you can either configure the compiler's boot classpath to match the target JDK,
or use the [Animal Sniffer Maven Plugin](http://www.mojohaus.org/animal-sniffer/animal-sniffer-maven-plugin/)
to verify your code doesn't use unintended APIs,
or better yet use the [release](./set-compiler-release.html) option.

In the same way, setting the `source` option does not guarantee that your code actually compiles on a JDK with the specified version.
To compile your code with a specific JDK version, different than the one used to launch Maven,
refer to the [Compile Using A Different JDK](./compile-using-different-jdk.html) example.
