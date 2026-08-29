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

# Arguments related to Java Platform Module System

Java 9 comes with a new set of arguments related to the Java Platform Modular System (JPMS).
Besides the module path there are other new arguments which can change the behavior of the application.
These can be used during both compile time and runtime.
Except the module path, these extra arguments should not be needed for compilation and execution of the main code
(if they are needed, then maybe the `module-info.java` file is incomplete).
But they may be needed for compilation and execution of tests.
In such case, at runtime it is useful to know which extra arguments were used at compile time.


## Debugging information

If the compilation fails, version 4 of the Maven Compiler Plugin generates one of the following files,
depending on which scope (main or test) cannot be compiled:
These files are usually not generated when the build is successful,
but their generation can be forced by providing the `--verbose` option to the `mvn` command.

* Main: `target/javac.args`
* Test: `target/javac-test.args`

The following arguments are of particular interest, because they may be required at runtime.
These arguments should appear in the `javac-test.args` file only, not in `javac.args`.

  * `--upgrade-module-path`
  * `--add-exports`
  * `--add-reads`
  * `--add-modules`
  * `--limit-modules`
  * `--patch-module`
