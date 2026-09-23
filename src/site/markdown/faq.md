---
title: Frequently Asked Questions
---

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

<a id="top"></a>

# Frequently Asked Questions

1. [How do I add my generated sources to the compile path of Maven, when using Modello?](#how-to-add-generated-sources)

<a id="how-to-add-generated-sources"></a>

### How do I add my generated sources to the compile path of Maven, when using Modello?

Modello generates the sources in the generate-sources phase and automatically adds the source directory for
compilation in Maven. So you don't have to copy the generated sources.

You have to declare the [Modello Maven Plugin](https://codehaus-plexus.github.io/modello/modello-maven-plugin/)
in the build of your project for source generation (in that way the sources are generated each time).

For more information about Modello, please visit the
[Modello website](https://codehaus-plexus.github.io/modello/).
