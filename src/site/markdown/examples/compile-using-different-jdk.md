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

The preferable way to use a different JDK is the toolchains mechanism.
During the build of a project, Maven uses the JDK to perform various steps without toolchains.
These steps include compiling the Java sources, generating the Javadoc, running unit tests or signing JARs.
Each of those plugins needs a JDK tool to operate: `javac`, `javadoc`, `jarsigner`, etc.
A toolchain specifies the path to the JDK for all of those plugins in a centralized manner.
It is independent from the JDK that runs Maven itself.

To set this up, refer to the [Guide to Using Toolchains](https://maven.apache.org/guides/mini/guide-using-toolchains.html).
This guide uses the [Maven Toolchains Plugin](https://maven.apache.org/plugins/maven-toolchains-plugin/).

With the maven-toolchains-plugin you configure 1 default JDK toolchain for all related Maven plugins.
Since maven-compiler-plugin 3.6.0, when used with Maven 3.3.1+, it is also possible to give the plugin its own toolchain.
This is useful for a different JDK per execution block
(for example, the test sources require a different compiler compared to the main sources).


## Configuring the Compiler Plugin

Outside of a toolchain, it is still possible to tell the Compiler Plugin the specific JDK to use during compilation.
Note that such configuration is specific to this plugin. It does not affect other plugins.
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