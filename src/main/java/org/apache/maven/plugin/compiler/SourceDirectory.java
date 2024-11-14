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
import javax.tools.JavaFileObject;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A single root directory of source files, associated with module name and release version.
 * The module names are used when compiling a Module Source Hierarchy.
 * The release version is used for multi-versions JAR files.
 *
 * <p>This class contains also the output directory, because this information is needed
 * for determining whether a source file need to be recompiled.</p>
 *
 * @author Martin Desruisseaux
 */
final class SourceDirectory {
    /**
     * The module-info filename, without extension.
     */
    static final String MODULE_INFO = "module-info";

    /**
     * File suffix of source code.
     */
    static final String JAVA_FILE_SUFFIX = ".java";

    /**
     * File suffix of compiler classes.
     */
    static final String CLASS_FILE_SUFFIX = ".class";

    /**
     * The root directory of all source files.
     */
    final Path root;

    /**
     * Kind of source files in this directory. This is usually {@link JavaFileObject.Kind#SOURCE}.
     * This information is used for building a default include filter such as {@code "glob:*.java}
     * if the user didn't specified an explicit filter. The default include filter may change for
     * each root directory.
     */
    final JavaFileObject.Kind fileKind;

    /**
     * Name of the module for which source directories are provided, or {@code null} if none.
     * This name is supplied to the constructor instead of parsed from {@code module-info.java}
     * file because the latter may not exist in this directory. For example, in a multi-release
     * project the module-info may be declared in another directory for the base version.
     */
    final String moduleName;

    /**
     * Path to the {@code module-info} file, or {@code null} if none. This flag is set when
     * walking through the directory content. This is related, but not strictly equivalent,
     * to whether the {@link #moduleName} is non-null.
     */
    private Path moduleInfo;

    /**
     * The Java release for which source directories are provided, or {@code null} for the default release.
     * This is used for multi-versions JAR files.
     */
    final SourceVersion release;

    /**
     * The directory where to store the compilation results.
     * This is the MOJO output directory with sub-directories appended according the following rules, in that order:
     *
     * <ol>
     *   <li>If {@link #moduleName} is non-null, then the module name is appended.</li>
     *   <li>If {@link #release} is non-null, then the next elements in the paths are
     *       {@code "META-INF/versions/<n>"} where {@code <n>} is the release number.</li>
     * </ol>
     */
    final Path outputDirectory;

    /**
     * Kind of output files in the output directory.
     * This is usually {@link JavaFileObject.Kind#CLASS}.
     */
    final JavaFileObject.Kind outputFileKind;

    /**
     * Creates a new source directory.
     *
     * @param root the root directory of all source files
     * @param fileKind kind of source files in this directory (usually {@code SOURCE})
     * @param moduleName name of the module for which source directories are provided, or {@code null} if none
     * @param release Java release for which source directories are provided, or {@code null} for the default release
     * @param outputDirectory the directory where to store the compilation results
     * @param outputFileKind Kind of output files in the output directory (usually {@ codeCLASS})
     */
    private SourceDirectory(
            Path root,
            JavaFileObject.Kind fileKind,
            String moduleName,
            SourceVersion release,
            Path outputDirectory,
            JavaFileObject.Kind outputFileKind) {
        this.root = Objects.requireNonNull(root);
        this.fileKind = Objects.requireNonNull(fileKind);
        this.moduleName = moduleName;
        this.release = release;
        if (release != null) {
            String version = release.name();
            version = version.substring(version.lastIndexOf('_') + 1);
            FileSystem fs = outputDirectory.getFileSystem();
            Path subdir;
            if (moduleName != null) {
                subdir = fs.getPath(moduleName, "META-INF", "versions", version);
            } else {
                subdir = fs.getPath("META-INF", "versions", version);
            }
            outputDirectory = outputDirectory.resolve(subdir);
        } else if (moduleName != null) {
            outputDirectory = outputDirectory.resolve(moduleName);
        }
        this.outputDirectory = outputDirectory;
        this.outputFileKind = outputFileKind;
    }

    /**
     * Converts the given list of paths to a list of source directories.
     * The returned list includes only the directories that exist.
     *
     * @param compileSourceRoots the root paths to source files
     * @param outputDirectory the directory where to store the compilation results
     * @return the given list of paths wrapped as source directory objects
     */
    static List<SourceDirectory> fromPaths(List<Path> compileSourceRoots, Path outputDirectory) {
        var roots = new ArrayList<SourceDirectory>(compileSourceRoots.size());
        for (Path p : compileSourceRoots) {
            if (Files.exists(p)) {
                // TODO: specify file kind, module name and release version.
                roots.add(new SourceDirectory(
                        p, JavaFileObject.Kind.SOURCE, null, null, outputDirectory, JavaFileObject.Kind.CLASS));
            }
        }
        return roots;
    }

    /**
     * Returns whether the given file is a {@code module-info.java} file.
     * TODO: we could make this method non-static and verify that the given
     * file is in the root of this directory.
     */
    static boolean isModuleInfoSource(Path file) {
        return (MODULE_INFO + JAVA_FILE_SUFFIX).equals(file.getFileName().toString());
    }

    /**
     * Invoked for each source files in this directory.
     */
    void visit(Path sourceFile) {
        if (isModuleInfoSource(sourceFile)) {
            // Paranoiac check: only one file should exist, but if many, keep the one closest to the root.
            if (moduleInfo == null || moduleInfo.getNameCount() >= sourceFile.getNameCount()) {
                moduleInfo = sourceFile;
            }
        }
    }

    /**
     * Path to the {@code module-info.java} source file, or empty if none.
     * This information is accurate only after {@link PathFilter} finished
     * to walk through all source files in a directory.
     */
    public Optional<Path> getModuleInfo() {
        return Optional.ofNullable(moduleInfo);
    }

    /**
     * Compares the given object with this source directory for equality.
     *
     * @param obj the object to compare
     * @return whether the two objects have the same path, module name and release version
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof SourceDirectory other) {
            return release == other.release
                    && Objects.equals(moduleName, other.moduleName)
                    && root.equals(other.root)
                    && outputDirectory.equals(other.outputDirectory);
        }
        return false;
    }

    /**
     * {@return a hash code value for this root directory}.
     */
    @Override
    public int hashCode() {
        return root.hashCode() + 7 * Objects.hash(moduleName, release);
    }

    /**
     * {@return a string representation of this root directory for debugging purposes}.
     */
    @Override
    public String toString() {
        var sb = new StringBuilder(100).append('"').append(root).append('"');
        if (moduleName != null) {
            sb.append(" for module \"").append(moduleName).append('"');
        }
        if (release != null) {
            sb.append(" on Java release ").append(release);
        }
        return sb.toString();
    }
}
