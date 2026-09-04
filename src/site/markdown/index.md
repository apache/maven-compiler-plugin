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

The Compiler Plugin compiles the sources of your project.
The default compiler for Java sources is `javac`.
To use another compiler, read the [using Non-Javac Compilers](./examples/non-javac-compilers.html) page.

**NOTE:** For more information about the JDK `javac`, read the
[tool guide](https://docs.oracle.com/en/java/javase/24/docs/specs/man/javac.html).

# Goals Overview

The Compiler Plugin has two goals.
Both goals bind to their phases in the Maven Lifecycle.
Maven executes them automatically during these phases.

* [compiler:compile](./compile-mojo.html) binds to the compile phase. It compiles the main source files.
* [compiler:testCompile](./testCompile-mojo.html) binds to the test-compile phase. It compiles the test source files.

# Usage

You can find the general usage instructions on the [usage page](./usage.html).
The examples below describe more specific use cases.

If you have questions about the plugin, read the [FAQ](./faq.html) or contact the [user mailing list](./mailing-lists.html).

The mailing list archives can contain an answer from an older thread. You can also search the [mail archive](./mailing-lists.html).

If the plugin lacks a feature or has a defect, create a feature request or a bug report. Submit the request in the [issue tracker](./issue-management.html).

When you create a new issue, describe the problem completely. Attach complete debug logs, POMs, or small demo projects to the issue.

The developers must reproduce the problem to fix the bug. Patches are welcome.

Contributors can check out the project from the [source repository](./scm.html). They will find more information in the [guide to helping with Maven](https://maven.apache.org/guides/development/guide-helping.html).

The following pages describe how to use the plugin beyond the default
"one source directory, one module, one release" configuration:

* [Declaration of source directories](./sources.html)
* [Multi-release project](./multirelease.html)
* [Modular project](./modules.html)
* [Module-info patch for tests](./module-info-patch.html)


# Examples

To understand some usages of the Compiler Plugin, read the following examples:

* [Annotation processors](./examples/annotation-processor.html)
* [Arguments related to Java Platform Module System](./examples/jpms_args.html)
* [Compile using a different JDK](./examples/compile-using-different-jdk.html)
* [Compile using a non-javac compiler](./examples/non-javac-compilers.html)
* [Compile using the --source and --target javac options](./examples/set-compiler-source-and-target.html)
* [Compile using the --release javac option](./examples/set-compiler-release.html)
* [Compile using memory allocation enhancements](./examples/compile-with-memory-enhancements.html)
* [Java 9+ projects with module-info](./examples/module-info.html)
* [Pass compiler arguments](./examples/pass-compiler-arguments.html)
