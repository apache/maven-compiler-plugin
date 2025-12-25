/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugin.compiler;

import javax.lang.model.SourceVersion;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The way that source files are organized in a file system directory hierarchy.
 * <a href="https://docs.oracle.com/en/java/javase/25/docs/specs/man/javac.html#directory-hierarchies">Directory
 * hierarchies</a> are <i>package hierarchy</i>, <i>module hierarchy</i> and <i>module source hierarchy</i>, but
 * for the purpose of the Maven Compiler Plugin we do not distinguish between the two latter.
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/specs/man/javac.html#directory-hierarchies">Directory hierarchies</a>
 */
public enum DirectoryHierarchy {
    /**
     * Project using package hierarchy. This is the hierarchy used by all Java projects before Java 9.
     * Note that it does not necessarily implies a class-path project. A modular project can still use
     * the package hierarchy if the project contains only one module.
     */
    PACKAGE("versions"),

    /**
     * Project using package hierarchy, but in which a {@code module-info} file has been detected.
     * This is used for compilation of tests. For the main code, we pretend that the hierarchy is
     * {@link #MODULE_SOURCE} and move the directory output after compilation. Therefore, this
     * enumeration value can be understood as "pseudo module source hierarchy".
     *
     * @see ModuleDirectoryRemover
     *
     * @deprecated Used only for compatibility with Maven 3.
     */
    @Deprecated
    PACKAGE_WITH_MODULE("versions"),

    /**
     * A multi-module project using module source hierarchy. It could also be a module hierarchy,
     * as the Maven Compiler Plugin does not need to distinguish <i>module hierarchy</i> and
     * <i>module source hierarchy</i>.
     */
    MODULE_SOURCE("versions-modular");

    /**
     * The {@value} directory.
     */
    static final String META_INF = "META-INF";

    /**
     * Name of the {@code META-INF/} sub-directory where multi-release outputs are stored.
     */
    private final String versionDirectory;

    /**
     * Creates a new enumeration value.
     *
     * @param versionDirectory name of the {@code META-INF/} sub-directory where multi-release outputs are stored
     */
    DirectoryHierarchy(String versionDirectory) {
        this.versionDirectory = versionDirectory;
    }

    /**
     * Returns the directory where to write the compiled class files for all Java releases.
     * The standard path for {@link #PACKAGE} hierarchy is {@code META-INF/versions}.
     * The caller shall add the version number to the returned path.
     *
     * @param outputDirectory usually the value of {@link SourceDirectory#outputDirectory}
     * @return the directory for all versions
     */
    public Path outputDirectoryForReleases(Path outputDirectory) {
        // TODO: use Path.resolve(String, String...) with Java 22.
        return outputDirectory.resolve(META_INF).resolve(versionDirectory);
    }

    /**
     * Returns the directory where to write the compiled class files for a specific Java release.
     * The standard path is {@code META-INF/versions/${release}} where {@code ${release}} is the
     * numerical value of the {@code release} argument. However for {@link #MODULE_SOURCE} case,
     * the returned path is rather {@code META-INF/versions-modular/${release}}.
     * The latter is non-standard because there is no standard multi-module <abbr>JAR</abbr> formats as of 2025.
     * The use of {@code "versions-modular"} is for allowing other plugins such as Maven <abbr>JAR</abbr> plugin
     * to avoid confusion with the standard case.
     *
     * @param outputDirectory usually the value of {@link SourceDirectory#outputDirectory}
     * @param release the release, or {@code null} for the default release
     * @return the directory for the classes of the specified version
     */
    public Path outputDirectoryForReleases(Path outputDirectory, SourceVersion release) {
        if (release == null) {
            release = SourceVersion.latestSupported();
        }
        String version = release.name(); // TODO: replace by runtimeVersion() in Java 18.
        version = version.substring(version.lastIndexOf('_') + 1);
        return outputDirectoryForReleases(outputDirectory).resolve(version);
    }

    /**
     * Returns a string representation for use in error message.
     *
     * @return human-readable string representation
     */
    @Override
    public String toString() {
        return name().replace('_', ' ').toLowerCase(Locale.US);
    }
}
