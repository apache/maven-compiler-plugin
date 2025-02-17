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
Contributing to [Apache Maven Compiler Plugin](https://maven.apache.org/plugins/maven-compiler-plugin/)
======================

[![Apache License, Version 2.0, January 2004](https://img.shields.io/github/license/apache/maven.svg?label=License)][license]
[![Maven Central](https://img.shields.io/maven-central/v/org.apache.maven.plugins/maven-compiler-plugin.svg?label=Maven%20Central&versionPrefix=3.)](https://search.maven.org/artifact/org.apache.maven.plugins/maven-compiler-plugin)
[![Maven Central](https://img.shields.io/maven-central/v/org.apache.maven.plugins/maven-compiler-plugin.svg?label=Maven%20Central)](https://search.maven.org/artifact/org.apache.maven.plugins/maven-compiler-plugin)
[![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/org/apache/maven/plugins/maven-compiler-plugin/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/org/apache/maven/plugins/maven-compiler-plugin/README.md)
[![Jenkins Status](https://img.shields.io/jenkins/s/https/ci-maven.apache.org/job/Maven/job/maven-box/job/maven-compiler-plugin/job/master.svg?)][build]
[![Jenkins tests](https://img.shields.io/jenkins/t/https/ci-maven.apache.org/job/Maven/job/maven-box/job/maven-compiler-plugin/job/master.svg?)][test-results]


You have found a bug or you have an idea for a cool new feature? Contributing
code is a great way to give something back to the open source community. Before
you dig right into the code, there are a few guidelines that we need
contributors to follow so that we can have a chance of keeping on top of
things.

Getting Started
---------------

+ Make sure you have a [GitHub account](https://github.com/signup/free).
+ If you're planning to implement a new feature, it makes sense to discuss your changes 
  on the [dev list][ml-list] first. 
  This way you can make sure you're not wasting your time on something that isn't 
  considered to be in Apache Maven's scope.
+ Submit a ticket for your issue, assuming one does not already exist.
  + Clearly describe the issue, including steps to reproduce when it is a bug.
  + Make sure you fill in the earliest version that you know has the issue.
+ Fork the repository on GitHub.

Making and Submitting Changes
--------------

We accept Pull Requests via GitHub. The [developer mailing list][ml-list] is the
main channel of communication for contributors.  
There are some guidelines which will make applying PRs easier for us:
+ Create a topic branch from where you want to base your work (this is usually the master branch).
  Push your changes to a topic branch in your fork of the repository.
+ Make commits of logical units.
+ Respect the original code style: by using the same [codestyle][code-style],
  patches should only highlight the actual difference, not being disturbed by any formatting issues:
  + Only use spaces for indentation.
  + Create minimal diffs - disable on save actions like reformat source code or organize imports. 
    If you feel the source code should be reformatted, create a separate PR for this change.
  + Check for unnecessary whitespace with `git diff --check` before committing.
+ Make sure you have added the necessary tests (JUnit/IT) for your changes.
+ Run all the tests with `mvn -Prun-its verify` to assure nothing else was accidentally broken.
+ Submit a pull request to the repository in the Apache organization.

If you plan to contribute on a regular basis, please consider filing a [contributor license agreement][cla].

Additional Resources
--------------------

+ [Contributing patches](https://maven.apache.org/guides/development/guide-maven-development.html#Creating_and_submitting_a_patch)
+ [Contributor License Agreement][cla]
+ [General GitHub documentation](https://help.github.com/)
+ [GitHub pull request documentation](https://help.github.com/send-pull-requests/)
+ [Apache Maven Twitter Account](https://twitter.com/ASFMavenProject)
+ #Maven IRC channel on freenode.org

[license]: https://www.apache.org/licenses/LICENSE-2.0
[ml-list]: http://maven.apache.org/mailing-lists.html
[code-style]: https://maven.apache.org/developers/conventions/code.html
[cla]: https://www.apache.org/licenses/#clas
[maven-wiki]: https://cwiki.apache.org/confluence/display/MAVEN/Index
[test-results]: https://ci-maven.apache.org/job/Maven/job/maven-box/job/maven-compiler-plugin/job/master/lastCompletedBuild/testReport/
[build]: https://ci-maven.apache.org/job/Maven/job/maven-box/job/maven-compiler-plugin/job/master/
