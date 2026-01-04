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

The Compiler Plugin is used to compile the sources of your project.
The default compiler used to compile Java sources is `javac`.
If you want to use another compiler, refer to the [using Non-Javac Compilers](./examples/non-javac-compilers.html) page.

**NOTE:** To know more about the JDK `javac`, please see the
[tool guide](https://docs.oracle.com/en/java/javase/24/docs/specs/man/javac.html).

# Goals Overview

The Compiler Plugin has two goals.
Both are already bound to their proper phases within the Maven Lifecycle and are therefore,
automatically executed during their respective phases.

* [compiler:compile](./compile-mojo.html) is bound to the compile phase and is used to compile the main source files.
* [compiler:testCompile](./testCompile-mojo.html) is bound to the test-compile phase and is used to compile the test source files.

# Usage

General instructions on how to use the Compiler Plugin can be found on the [usage page](./usage.html).
Some more specific use cases are described in the examples given below.

In case you still have questions regarding the plugin's usage, please have a look at the [FAQ](./faq.html)
and feel free to contact the [user mailing list](./mailing-lists.html).
The posts to the mailing list are archived and could already contain the answer to your question as part of an older thread.
Hence, it is also worth browsing/searching the [mail archive](./mailing-lists.html).

If you feel the plugin is missing a feature or has a defect,
you can file a feature request or bug report in our [issue tracker](./issue-management.html).
When creating a new issue, please provide a comprehensive description of your concern.
Especially for fixing bugs it is crucial that the developers can reproduce your problem.
For this reason, entire debug logs, POMs or most preferably little demo projects attached to the issue are very much appreciated.
Of course, patches are welcome, too.
Contributors can check out the project from our [source repository](./scm.html) and will find supplementary information
in the [guide to helping with Maven](http://maven.apache.org/guides/development/guide-helping.html).

The following pages describes how to use the plugin beyond the default
"one source directory, one module, one release" default configuration:

* [Declaration of source directories](./sources.html)
* [Multi-release project](./multirelease.html)
* [Modular project](./modules.html)
* [Module-info patch for tests](./module-info-patch.html)


# Examples

To provide you with better understanding on some usages of the Compiler Plugin,
you can take a look into the following examples:

* [Annotation processors](./examples/annotation-processor.html)
* [Arguments related to Java Platform Module System](./examples/jpms_args.html)
* [Compile using a different JDK](./examples/compile-using-different-jdk.html)
* [Compile using a non-javac compilers](./examples/non-javac-compilers.html)
* [Compile using the --source and --target javac options](./examples/set-compiler-source-and-target.html)
* [Compile using the --release javac option](./examples/set-compiler-release.html)
* [Compile using memory allocation enhancements](./examples/compile-with-memory-enhancements.html)
* [Java 9+ projects with module-info](./examples/module-info.html)
* [Pass compiler arguments](./examples/pass-compiler-arguments.html)
