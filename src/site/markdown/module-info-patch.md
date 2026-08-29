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

# Module-info patch

For white box testing, you must use compiler options such as
`--patch-module`, `--add-modules`, `--add-reads`, `--add-exports` and `--add-opens`.
Writing these options inside the Maven `<compilerArgs>` XML element is tedious, redundant
(the name of the module to patch repeats in every occurrence of some options), error prone,
and repeats in every plugin that depends on the tests (Surefire, Javadoc for test documentation, _etc._).
An alternative is to put a `module-info.java` file in the tests. It *replaces* the `module-info.java` file of the main code.
However, it forces the developer to repeat all the content of the main `module-info.java`
into the test `module-info.java` before adding test-specific statements.
This is tedious if the main `module-info.java` is large, and risky if the two files fall out of sync.

Instead of defining a `module-info.java` file in the tests, Maven projects can define a `module-info-patch.maven`.
The content of `module-info-patch.maven` uses the same syntax as Java, C/C++, JavaScript, Groovy, _etc._
(comments between `/*` … `*/` or after `//`, blocks between `{` … `}`, statements ending with `;`)
but is not Java. Hence the `.maven` file suffix.
The general principles are:

* Everything that a developer changes in a `module-info.java` file for testing is declared in `module-info-patch.maven`.
* Everything that is not in `module-info.java` is not in `module-info-patch.maven` either.
  In particular, everything that specifies paths to JAR files or paths to source code stays in the `pom.xml` file.
* All keywords except `patch-module`, `SUBPROJECT-MODULES` and `TEST-MODULE-PATH`
  map directly to Java compiler or Java launcher options.

Compared to declaring options in `<compilerArgs>` XML elements, the `module-info-patch.maven` file is more readable.
It keeps the options in separate files for each module.
It avoids repeating the module name in every `--add-reads`, `--add-exports` and `--add-opens` option.
It is also more flexible. It translates into slightly different options for compilation and test execution.
For example, `TEST-MODULE-PATH` means modules with the `test` and `test-only` Maven scope at compilation time.
It means modules with the `test` and `test-runtime` Maven scope at execution time.


## Syntax

The syntax is:

* The same comment styles as Java (`/*` … `*/` and `//`) are accepted.
* The first tokens, after comments, shall be `patch-module` followed by the name of the module to patch.
* All keywords inside `patch-module` are Java compiler or Java launcher options without the leading `--` characters.
* Each option value ends at the `;` character. This character is mandatory.

The accepted keywords are `add-modules`, `limit-modules`, `add-reads`, `add-exports` and `add-opens`.
These options have package or module names as values, not paths to source or binary files.
Options with path values (`--module-path`, `--module-source-path`, `--patch-module`, _etc._)
continue to derive from the dependencies declared in the POM.


### Options applying to all modules

All options declared in a `module-info-patch.maven` file apply only to the module declared after the `patch-module` token,
except the `--add-modules` and `--limit-modules` options.
These two options apply to all modules in a multi-modules project.
The `java` and `javac` commands expect no module name for these options.
Therefore, it is not necessary to repeat `add-modules TEST-MODULE-PATH` in all modules.
Declaring that option in only one module of a multi-modules project is sufficient.
If the `--add-modules` or `--limit-modules` options appear in many `module-info-patch.maven` files of a multi-modules project,
then the effective value is the union of the values in each file, without duplicated values.


### Special option values

The following option values have special meanings:

* `SUBPROJECT-MODULES`: all other modules in the current Maven (sub)project.
  * This is Maven-specific, not a standard value recognized by Java tools.
  * Allowed in: `add-exports`.
* `TEST-MODULE-PATH`: all dependencies having a test scope in the build tools.
  * This is specific to this format, not a standard value recognized by Java tools.
  * Allowed in: `add-modules`, `add-reads` and `add-exports` options.
* `ALL-MODULE-PATH`: everything on the module path, regardless of the test or main scope.
  * This is a standard value accepted by the Java compiler.
  * Allowed in: `add-modules` option.
* `ALL-UNNAMED`: all non-modular dependencies.
  * This is a standard value accepted by the Java compiler.
  * Allowed in: `add-exports` option.


## Example

Below is an example of a `module-info-patch.maven` file content
for modifying the `module-info` of a module named `org.foo.bar`:

```java
/*
 * The same comments as in Java are allowed.
 */
patch-module org.foo.bar {                // Put here the name of the module to patch.
    add-modules TEST-MODULE-PATH;         // Recommended value in the majority of cases.

    add-reads org.junit.jupiter.api,      // Frequently used dependency for tests.
              my.product.test.fixture;    // Put here any other dependency needed for tests.

    add-exports org.foo.bar.internal      // Name of a package which is normally not exported.
             to org.junit.jupiter.api,    // Any module that need access to above package for testing.
                org.something.else;       // Can export to many modules, as a coma-separated list.

    add-exports org.foo.bar.fixtures      // Another package to export. It may be a package defined in the tests.
             to org.foo.bar.other;        // Another module of this project which may want to reuse test fixtures.
}
```


### How module info patches are compiled

`module-info-patch.maven` files compile into a file of options in the following ways:

* `add-modules org.foo, org.bar;` translates to `--add-modules org.foo,org.bar`.
  * Note: spaces between `org.foo` and `org.bar` are removed to interpret the option values as a single argument.
* `limit-modules org.foo, org.bar;` translates to `--limit-modules org.foo,org.bar`.
  * Note: idem regarding space removal.
* `add-reads org.foo, org.bar;` translates to `--add-reads org.patched=org.foo,org.bar`
  where `org.patched` is the module name declared in the first statement of the `module-info-patch` file.
* `add-exports com.biz to org.foo, org.bar;` translates to `--add-exports org.patched/com.biz=org.foo,org.bar`
  where `org.patched` is as above.
* `add-opens com.biz to org.foo, org.bar;` translates to `--add-opens org.patched/com.biz=org.foo,org.bar`
  as above but only for runtime execution, not for compilation.

There is a separate `module-info-patch.maven` file for each module.
The Maven compiler plugin merges them into a single set of options for `java` and `javac`.
While this format does not require the module source hierarchy, it fits nicely in that hierarchy.

The results of the translation to compiler options appear in the `target/javac.args` and `target/javac-test.args` files.
Those files are produced when the build fails or when Maven runs with the `--verbose` command-line option.
In addition, a slightly different set of options, suitable for test execution, is written in the
`target/test-classes/META-INF/maven/module-info-patch.args` file.
