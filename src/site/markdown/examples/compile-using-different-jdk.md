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

# Compiling Sources Using A Different JDK

## Using Maven Toolchains

Maven is itself a Java application running in a JDK.
By default the same JDK that runs Maven builds the code and runs the tests.
However, sometimes you need different JDKs. For instance, recent versions of Maven require 
Java 17 to run, but you might need to compile a project with Java 8.
Toolchains are the preferred way to use different JDKs to run Maven and to build the project.

During the build, Maven uses the JDK to perform various steps.
These steps include compiling the Java sources, generating the Javadoc, running unit tests, signing JARs, and more.
Most core Maven plugins execute a JDK tool: `javac`, `javadoc`, `jarsigner`, etc.
A toolchain specifies the path to the JDK where the plugin finds these tools.
It is independent of the JDK that runs Maven itself.

To set this up, refer to the [Guide to Using Toolchains](https://maven.apache.org/guides/mini/guide-using-toolchains.html)
and the [Maven Toolchains Plugin](https://maven.apache.org/plugins/maven-toolchains-plugin/).

With the maven-toolchains-plugin, you configure one default JDK toolchain for all related Maven plugins.
Since maven-compiler-plugin 3.6.0, it is also possible assign different plugins different toolchains.
For example, the test sources might require Java 8 but compilation requires Java 11.


## Configuring the Compiler Plugin

Outside of a toolchain, it is still possible to tell the Compiler Plugin the specific JDK to use during compilation.
Such configuration is specific to the compiler plugin. It does not affect other plugins.
If the `fork` parameter is set to `true`, the compiler uses the executable at the specified path.
The following example uses a `JAVA_11_HOME` property which each developer must set.
The next paragraph discusses this property.

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
          <verbose>true</verbose>
          <fork>true</fork>
          <executable>${JAVA_11_HOME}/bin/javac</executable>
        </configuration>
      </plugin>
    </plugins>
    [...]
  </build>
  [...]
</project>
```

The example above uses a `JAVA_11_HOME` property to avoid hard-coding a filesystem path for the executable.
Each developer defines this property in [settings.xml](https://maven.apache.org/ref/current/maven-settings/settings.html),
or sets an environment variable, so that the build stays portable.

```xml
<settings>
  [...]
  <profiles>
    [...]
    <profile>
      <id>compiler</id>
      <properties>
        <JAVA_11_HOME>/usr/lib/jvm/java-11-openjdk</JAVA_11_HOME>
      </properties>
    </profile>
  </profiles>
  [...]
  <activeProfiles>
    <activeProfile>compiler</activeProfile>
  </activeProfiles>
</settings>
```

If you build with a different JDK, you can
[customize the jar file manifest](https://maven.apache.org/plugins/maven-jar-plugin/examples/manifest-customization.html).
