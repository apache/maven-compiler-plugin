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

For white box testing, it is necessary to use compiler options such as
`--patch-module`, `--add-modules`, `--add-reads`, `--add-exports` and `--add-opens`.
Writing these options inside the Maven `<compilerArgs>` XML element is tedious, redundant
(the name of the module to patch is repeated in every occurrence of some options), error prone,
and must be repeated in every plugins that depends on the tests (Surefire, Javadoc for test documentation, _etc._).
An alternative is to put a `module-info.java` file in the tests which *replace* the `module-info.java` file of the main code.
However, it forces the developer to repeat all the content of the main `module-info.java`
into the test `module-info.java` before to add test-specific statements.
This is tedious if the main `module-info.java` is large, and risky if the two files become out of sync.

Instead of defining a `module-info.java` file in test, Maven projects can define a `module-info-patch.maven`.
The content of `module-info-patch.maven` uses the same syntax as Java, C/C++, JavaScript, Groovy, _etc._
(comments between `/*` … `*/` or after `//`, blocks between `{` … `}`, statements ending with `;`)
but is not Java, hence the `.maven` file suffix.
The general principles are:

* Everything that a developer would like to change in a `module-info.java` file for testing purposes is declared in `module-info-patch.maven`.
* Everything that is not in `module-info.java` is not in `module-info-patch.maven` neither.
  In particular, everything that specify paths to JAR files or paths to source code stay in the `pom.xml` file.
* All keywords except `patch-module`, `SUBPROJECT-MODULES` and `TEST-MODULE-PATH`
  map directly to Java compiler or Java launcher options.

Compared to declaring options in `<compilerArgs>` XML elements, the `module-info-patch.maven` file is more readable,
keep the options in separated files for each module on which the options are applied, is less redundant as it avoids
the need to repeat the module name in every `--add-reads`, `--add-exports` and `--add-opens` options,
and is more flexibly as it is translated in slightly different options for compilation and test executions
(e.g. `TEST-MODULE-PATH` means modules having `test` and `test-only` Maven's scope at compilation time,
but means modules having `test` and `test-runtime` Maven's scope at execution time).


## Syntax
The syntax is:

* The same styles of comment as Java (`/*` … `*/` and `//`) are accepted.
* The first tokens, after comments, shall be `patch-module` followed by the name of the module to patch.
* All keywords inside `patch-module` are Java compiler or Java launcher options without the leading `--` characters.
* Each option value ends at the `;` character, which is mandatory.

The accepted keywords are `add-modules`, `limit-modules`, `add-reads`, `add-exports` and `add-opens`.
Note that they are options where the values are package or module names, not paths to source or binary files.
Options with path values (`--module-path`, `--module-source-path`, `--patch-module`, _etc._)
continue to be derived from the dependencies declared in the POM.

### Options applying to all modules
All options declared in a `module-info-patch.maven` file apply only to the module declared after the `patch-module` token,
except the `--add-modules` and `--limit-modules` options.
These two options apply to all modules in a multi-modules project,
because these options given to `java` or `javac` expect no module name.
Therefore, it is not necessary to repeat `add-modules TEST-MODULE-PATH` in all modules:
declaring that particular option in only one module of a multi-modules project is sufficient.
If the `--add-modules` or `--limit-modules` options are declared in many `module-info-patch.maven` files of a multi-modules project,
then the effective value is the union of the values declared in each file, without duplicated values.


### Special option values
The following option values have special meanings:

* `SUBPROJECT-MODULES`: all other modules in the current Maven (sub)project.
  * This is Maven-specific, not a standard value recognized by Java tools.
  * Allowed in: `add-exports`.
* `TEST-MODULE-PATH`: all dependencies having a test scope in the build tools.
  * This is specific to this format, not a standard value recognized by Java tools.
  * Allowed in: `add-modules`, `add-reads` and `add-exports` options.
* `ALL-MODULE-PATH`: everything on the module path, regardless if test or main.
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
`module-info-patch.maven` are compiled into a file of options in the following ways:

* `add-modules org.foo, org.bar;` is translated to `--add-modules org.foo,org.bar`.
  * Note: spaces between `org.foo` and `org.bar` are removed for interpreting the option values as a single argument.
* `limit-modules org.foo, org.bar;` is translated to `--limit-modules org.foo,org.bar`.
  * Note: idem regarding spaces removal.
* `add-reads org.foo, org.bar;` is translated to `--add-reads org.patched=org.foo,org.bar`
  where `org.patched` is the module name declared in the first statement of the `module-info-patch` file.
* `add-exports com.biz to org.foo, org.bar;` is translated to `--add-exports org.patched/com.biz=org.foo,org.bar`
  where `org.patched` is as above.
* `add-opens com.biz to org.foo, org.bar;` is translated to `--add-opens org.patched/com.biz=org.foo,org.bar`
  like above but only for runtime execution, not for compilation.

There is a separated `module-info-patch.maven` file for each module,
and the Maven compiler plugin merges them in a single set of options for `java` and `javac`.
While this format does not require the use of module source hierarchy, it fits nicely in that hierarchy.

The results of the translation to compiler options can be seen in the `target/javac.args` and `target/javac-test.args` files.
Those files are produced when the build failed or when Maven was executed with the `--verbose` command-line option.
In addition, a slightly different set of options, suitable for tests execution, is written in the
`target/test-classes/META-INF/maven/module-info-patch.args` file.
