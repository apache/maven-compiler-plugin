 ------
 Multi Release
 ------
 Robert Scholte
 ------
 2018-05-08
 ------

~~ Licensed to the Apache Software Foundation (ASF) under one
~~ or more contributor license agreements.  See the NOTICE file
~~ distributed with this work for additional information
~~ regarding copyright ownership.  The ASF licenses this file
~~ to you under the Apache License, Version 2.0 (the
~~ "License"); you may not use this file except in compliance
~~ with the License.  You may obtain a copy of the License at
~~
~~   http://www.apache.org/licenses/LICENSE-2.0
~~
~~ Unless required by applicable law or agreed to in writing,
~~ software distributed under the License is distributed on an
~~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
~~ KIND, either express or implied.  See the License for the
~~ specific language governing permissions and limitations
~~ under the License.

~~ NOTE: For help with the syntax of this file, see:
~~ http://maven.apache.org/doxia/references/apt-format.html

Multi Release
 
  With {{{http://openjdk.java.net/jeps/238}JEP-238}} the support of multirelease jars was introduced.
  This means that you can have Java version dependent classes inside one jar.
  Based on the runtime it will pick up the best matching version of a class.

* JEP-238 Introduction

** The "forward compatibility" problem

  The problem this JEP is trying to solve is to make it possible to use new JDK features even though the codebase must stay compatible with earlier versions.

  Let's try to make this concrete with a real world example: In Java 7 <<<java.nio>>> was added, which was much better in File handling. In those days Maven still required Java 6 to run, however they wanted to make use of these new features when Maven was running on Java 7 or beyond.
    
  Up until Java 8 there were 2 solutions:
  
  [[1]] Compile with the targeted JDK (i.e. Java 6) and use reflection. You can ensure that the code is Java 6 compatible, but the reflection-part is hard to verify.
  
---
  if ( isAtLeastJava7() ) {
    Method toPathMethod = f.getClass().getMethod( "toPath" );
    Object toPathInstance = toPathMethod.invoke( f );
    Method isSymbolicLink = Class.forName( "java.nio.file.Files" ).getMethod( "isSymbolicLink" );
    ...
  }
  else {
    // compare absoluteFile with canonicalFile 
    ...
  }
---
  
  [[2]] Compile with the required JDK (i.e. Java 7), but use source/target of the lowest version (i.e. 1.6). The danger here is that you cannot ensure that all code is Java 6 compatible. Depending on the code structure {{{https://www.mojohaus.org/animal-sniffer/}Animal Sniffer}} might help.
  
---
  if ( isAtLeastJava7() ) {
    return Files.isSymbolicLink( f.toPath() );
  }
  else {
    // compare absoluteFile with canonicalFile 
    ...
  }
--- 
 
  []

** The "forward compatibility" solution

---
A.class
B.class
C.class
D.class
META-INF/MANIFEST.MF { Multi-Release: true }
         versions/9/A.class
                    B.class
                  10/A.class
                     C.class
                      
---

  With the <<<Multi-Release: true>>> flag in the MANIFEST file, the Java runtime will also look inside <<<META-INF/versions>>> for version specific classes, otherwise only the base classes are used.

* Challenges

  The theory behind multi release jars is quite simple, but in practice it can become quite complex. You must ensure that all classes stay in sync; if you add a method to one class, don't forget to add it to the other classes as well.
  There are some options which should reduce problems at this level:
  Let all multirelease classes implement an interface and in the basecode instantiate the class but only call the interface methods.
  The best is to test the *jar* with all targeted Java versions.
  You should think twice before turning your jar into a multi release jar, because such jars can be hard to read, maintain and test. In general applications don't need this, unless it is a widely distributed application and you don't control the targeted Java runtime. Libraries should make a decision based on: do I need this new Java feature? Can I make this Java version the new requirement? Would it be acceptable to solve this with an else/if-statement as mentioned in the first paragraph? 

  There are a couple of important facts one should know when creating Multi Release jars.
  
  * The Java compiler must be called for every different version. The solutions solve this either by having multiple Maven Projects/Modules or by adding extra compiler execution-blocks to the POM (like {{{./examples/module-info.html}older projects with module-info}}). 
  
  * The <<<Multi-Release: true>>> attribute is only recognized when the classes are in a jar. In other words, you cannot test the classes put in <<<target/classes/META-INF/versions/$\{release}/>>>.
  
  * Up until the moment of writing all IDEs can only have one JDK per Maven Project, whereas with multi release you want to specify it per source folder.
  
  []       

Pattern 1: {{{https://github.com/hboutemy/maven-jep238}Maven Multimodule}}

  This is the first pattern provided by the Maven team themselves. They had the following  requirements:
  
  * Only one Maven call to compile, test and package the Multi Release jar

  * It must work with IDEs

  * Developers should not change their way of work / simple configuration
  
  []
  
  The only solution to cover the first two bullets was to split the code into a Maven multimodule project.
  Now every Maven module is just a standard Maven project with close to no specific adjustments in the pom.
  There are several ways you can run this project:
  
  * Use the highest required version of the JDK to build the project. You can use <<<release>>> to ensure the code only uses matching code and syntax. Since the version-code is isolated you can run surefire with a higher Java runtime.
  
  * Use toolchains if you really want to compile and test with the matching Java version. 
  
  []
   
  The downside it that a hierarchical structure is required even though the result is just 1 artifact.
  
Pattern 2: {{{http://word-bits.flurg.com/multrelease-jars/}Multi Project}} 

  This solution is a response to the previous Maven multimodule setup. The requirements are almost the same

  * Do not require the project to switch to a multi-module format

  * Developers should not change their way of work / simple configuration
  
  []

  The first requirements implies that the projects now are separate Maven projects.
  One Maven project contains the base-code and in the end this will also be the multirelease jar.
  When building such project for the first time, you'll need to call Maven at least 3 times: first the base needs to be compiled, next all version specific projects must be built and finally the main project needs to be built again, now including the version specific classes extracted from their jars.
  This setup is compact, but has cyclic dependencies. This requires some tricks and makes releasing a bit more complicated.
  Another downside is that you must install SNAPSHOTs to your local repository and when doing a release it requires 2 releases of the base project, one to prepare for the multirelease-nine and one with the released multirelease-nine. 
   
Pattern 3: Single Project

  By now there are 3 solutions, each inspired by their previous version.

  Main goal:
  
  * Do not require the project to switch to a multi-module format
  
  * Only one Maven call to compile and package the Multi Release jar
  
  []

* {{{http://in.relation.to/2017/02/13/building-multi-release-jars-with-maven/}Single Project}}

  In this case everything stays inside one Maven project.
  Every specific Java version gets its own source folder and output folder, and just before packaging they are combined.
  What's not covered is how to test every class.

* {{{http://www.russgold.net/sw/2018/04/easier-than-it-looks/}Multi-Release Parent}}

  This approach replaces the maven-ant-plugin with extra exucution blocks in the maven-compiler-plugin. It has been setup as a parent, so other projects can use it. It uses toolchains to be able to build all classes with their matching Java version, so you always get the multi release jar. Because of the huge configuration and since Maven doesn't support mixins yet, it makes sense to put it all in a parent. 
  However, at the same time surefire is only called once.

* {{{https://github.com/codehaus-plexus/plexus-languages}CI-server}}

  This approach reduces the previous solution by only specifying execution blocks for sources for a specific Java version. It doesn't use toolchains, but the JDK used to run Maven. This means that only the sources up to the specific Java version are compiled and tested. 
  This solution relies heavily on a CI-server where every targeted Java version is available. If the CI-server succeeds, then all classes are tested with their matching Java version.

Pattern 4: {{{https://github.com/metlos/multi-release-jar-maven-plugin}Maven extension + plugin}}

  This approach introduces a new packaging type and an extra plugin takes care of the multiple executions of the maven-compiler-plugin, but these are now
  handled by the <<<perReleaseConfiguration>>> of the <<<multi-release-jar-maven-plugin>>>. What's not covered is how to test every class.

Patterns Summary

  For every pattern there are integration tests created, based on the same set of sourcefiles. See {{https://github.com/apache/maven-compiler-plugin/tree/master/src/it/multirelease-patterns}}

*-------------------------------*-----------------------*-------------------------*---------------------------*------------------------------*--------------------------------*
||                              || Maven Multimodule    || Multi Project          || Single project (runtime) || Single project (toolchains) || Maven extension+plugin        ||
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+
| # projects                    | 1                     | 1 + #javaVersions       | 1                         | 1                            | 1                              |
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+
| # builds to package           | 1                     | 2 + #javaVersions       | 1                         | 1                            | 1                              |
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+
| # builds/project to test      | 1                     | 1                       | #javaVersions             | 1                            | N/A (a)                        |
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+
| Simple Maven Project Layout   | No                    | Yes                     | Yes                       | Yes                          | Yes                            |
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+
| Additional POM adjustments(b) | 1 (c)                 | #javaVersions(d)        | #javaVersions(e)          | ??(f)                        | #javaVersions(g)               |
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+
| Include module descriptor     | No (h)                | No (h)                  | Yes                       | Yes                          | Yes                            |
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+
| IDE support (i)               | Yes                   | Yes                     | No                        | No                           | No                             |
*-------------------------------+-----------------------+-------------------------+---------------------------+------------------------------*--------------------------------+

  (a) Project can only be executed with highest required JDK, hence you can't test the code for all JDKs

  (b) Additional POM adjustments: # of executions added to the default lifecycle. This reflects the complexity of the POM.

  (c) Maven multimodule uses maven-assembly-plugin to assemble to multirelease jar

  (d) Multi project uses the maven-dependency-plugin to unpack java specific dependency to its matching outputDirectory

  (e) There's a profile for every Java version required which contains an extra execution-block for that Java version.

  (f) 

  (g) Maven extension+plugin hides the multiple executions in the <<<perReleaseConfiguration>>> configuration

  (h) Requires a --patch-module on a dependency 

  (i) IDE Support: All classes are recognized and can be tested within the IDE.
