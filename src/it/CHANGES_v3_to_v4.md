<!---
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Changes in integration tests since Maven 3
This section describes noticeable changes in the tests of Maven Compiler Plugin 4
compared to the tests of Maven Compiler Plugin 3.
The purpose of this section is to help the port of new tests added to the Maven Compiler Plugin 3
by documenting which tests to ignore and what to modify in new tests.

## Changes in POM parameters
Moved or added the following configuration parameters in the `pom.xml` files of some tests:

* The `<outputDirectory>` and `<testOutputDirectory>` parameters declared under `<configuration>`
  moved to `<build>`, because those properties are read-only in the configuration.
* Many `<source>` and `<target>` parameters have been either removed or replaced by `<release>`.
* For some tests using a non-modular JAR in a modular project,
 `<type>modular-jar</type>` has been added in the dependency declaration.

## Changes to met new plugin assumptions
The plugin incremental compilation algorithm depends on the convention that
Java source files are located in directories of the same name as their package names,
with the `.` separator replaced by path separator (`/` or `\`).
This is a very common convention, but not strictly required by the Java compiler.
For example, if the `src/main/java/MyCode.java` file contains the `package foo` statement,
the compiled class will be located in `target/classes/foo/MyCode.class` — note the `foo` additional directory.
In such case, the incremental build algorithm will not track correctly the changes.
The following tests have been made compliant with the convention for allowing the algorithm to work:

* `mcompiler-182` in integration tests.

Note that due to [MCOMPILER-209](https://jira.codehaus.org/browse/MCOMPILER-209),
the old algorithm was compiling everything without really detecting change.
So this issue is maybe not really a regression.
To reproduce the old behavior, users can just disable the incremental compilation.

## Removed integration tests
The tests in the following directories were already disabled and have been removed:

* `MCOMPILER-197` because it ran only on Java 8 while the build now requires Java 17.
* `MCOMPILER-346` because it tests an issue fixed in Java 15 while the build now requires Java 17.
* `groovy-project-with-new-plexus-compiler` because it ran only on Java 8 and the plexus compiler has been removed.

The tests in the following directores are not supported anymore and have been removed:

* `release-without-profile` because the plugin no longer try to chose automatically
   which parameters to use between `--source` and `--release`. This is justified by
   the fact that the plugin cannot run on Java 8.
* `release-without-profile-fork` for the same reason as above.
* `MCOMPILER-190`, which has been replaced by `MCOMPILER-609`.
  They are compilation tests using the Eclipse compiler, but the former test depended on Nexus.
  It has been replaced by a test that depends on `javax.tools`.


## Removed JUnit tests
Removed the following directories and associated test methods:

* `compiler-one-output-file-test2` because it was redundant with `compiler-one-output-file-test`.

The only difference was the addition of include/exclude filters, but that difference had
no effect because the compiler mock used in this test was ignoring all sources anyway.
This test has been replaced by `compiler-modular-project`.
