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

# Java 9+ projects with module-info

If a project with a `module-info.java` file does not need to be compatible with Java 8 or earlier environment,
there is nothing special to do.


# Java 8 projects with module-info

Projects that want to be compatible with older versions of Java (i.e, 8 or below bytecode and API),
but also want to provide a `module-info.java` for use on Java 9+ runtime,
must be aware that they need to call `javac` twice:

1. the `module-info.java` must be compiled with `release` set to 9 or later,
2. the rest of the sources must be compiled with the lower expected compatibility version of source/target.

A way to do this is by having two execution blocks, as described below:

1. default `default-compile` execution with `release` set to 9 or later,
2. additional custom `base-compile` execution with expected target compatibility.

The following snippet gives an example.
This snippet assumes that the JDK used by Maven supports the `--release 8` option.
It may not be the case for all JDKs, as newer JDKs may drop the support of Java versions that are too old.

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
        <executions>
          <execution>
            <id>default-compile</id>
            <configuration>
              <release>9</release>
              <!-- no excludes: compile everything to ensure module-info contains right entries -->
            </configuration>
          </execution>
          <execution>
            <id>base-compile</id>
            <goals>
              <goal>compile</goal>
            </goals>
            <configuration>
              <release>8</release>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
    [...]
  </build>
  [...]
</project>
```

If the JDK used by Maven does not support the `--release 8` option,
then projects which want to be compatible with old Java versions need to use two different JDKs for the two executions.
Using a [toolchains](/guides/mini/guide-using-toolchains.html) configuration, it is possible to achieve this, even if more complex.
In above snippet, the following fragment:

```xml
              <release>8</release>
```

can be replaced by:

```xml
              <source>1.8</source>
              <target>1.8</target>
              <jdkToolchain>
                <version>1.8</version>
              </jdkToolchain>
```
