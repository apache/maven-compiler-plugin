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

The preferable way to use a different JDK is to use the toolchains mechanism.
During the build of a project, Maven, without toolchains, will use the JDK to perform various steps,
like compiling the Java sources, generate the Javadoc, run unit tests or sign JARs.
Each of those plugins need a tool of the JDK to operate: `javac`, `javadoc`, `jarsigner`, etc.
A toolchain is a way to specify the path to the JDK to use for all of those plugins in a centralized manner,
independent from the one running Maven itself.

To set this up, refer to the [Guide to Using Toolchains](http://maven.apache.org/guides/mini/guide-using-toolchains.html),
which makes use of the [Maven Toolchains Plugin](http://maven.apache.org/plugins/maven-toolchains-plugin/).

With the maven-toolchains-plugin you configure 1 default JDK toolchain for all related maven-plugins.
Since maven-compiler-plugin 3.6.0 when using with Maven 3.3.1+ it is also possible to give the plugin its own toolchain,
which can be useful in case of different JDK calls per execution block
(e.g. the test sources require a different compiler compared to the main sources).


## Configuring the Compiler Plugin

Outside of a toolchain, it is still possible to tell the Compiler Plugin the specific JDK to use during compilation.
Note that such configuration will be specific to this plugin, and will not affect others.
If the `fork` parameter is set to `true`, the executable at the specified path will be used.
The following example uses a `JAVA_11_HOME` property which needs to be set by each developer,
as discussed in the next paragraph.

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

The above example uses a `JAVA_11_HOME` property in order to avoid hard-coding a filesystem path for the executable.
Each developer defines this property in [settings.xml](http://maven.apache.org/ref/current/maven-settings/settings.html),
or sets an environment variable, so that the build remains portable.

```xml
<settings>
  [...]
  <profiles>
    [...]
    <profile>
      <id>compiler</id>
        <properties>
          <JAVA_11_HOME>/usr/lib/jvm/java-11-openjdkk</JAVA_11_HOME>
        </properties>
    </profile>
  </profiles>
  [...]
  <activeProfiles>
    <activeProfile>compiler</activeProfile>
  </activeProfiles>
</settings>
```

If you build with a different JDK, you may want to
[customize the jar file manifest](http://maven.apache.org/plugins/maven-jar-plugin/examples/manifest-customization.html).
