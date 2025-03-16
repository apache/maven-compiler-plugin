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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.maven.api.Language;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.Version;

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
     * The root directory of all source files. Whether the path is relative or absolute depends on the paths given to
     * the {@link #fromProject fromProject(…)} or {@link #fromPluginConfiguration fromPluginConfiguration(…)} methods.
     * This class preserves the relative/absolute characteristic of the user-specified directories in order to behave
     * as intended by users in operations such as {@linkplain Path#relativize relativization}, especially in regard of
     * symbolic links. In practice, this path is often an absolute path.
     */
    final Path root;

    /**
     * Filter for selecting files below the {@linkplain #root} directory, or an empty list for the default filter.
     * For the Java language, the default filter is {@code "*.java"}. The filters are used by {@link PathFilter}.
     *
     * <p>This field differs from {@link PathFilter#includes} in that it is specified in the {@code <source>} element,
     * while the latter is specified in the plugin configuration. The filter specified here can be different for each
     * source directory, while the plugin configuration applies to all source directories.</p>
     *
     * @see PathFilter#includes
     */
    final List<PathMatcher> includes;

    /**
     * Filter for excluding files below the {@linkplain #root} directory, or an empty list for no exclusion.
     * See {@link #includes} for the difference between this field and {@link PathFilter#excludes}.
     *
     * @see PathFilter#excludes
     */
    final List<PathMatcher> excludes;

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
    @SuppressWarnings("checkstyle:ParameterNumber")
    private SourceDirectory(
            Path root,
            List<PathMatcher> includes,
            List<PathMatcher> excludes,
            JavaFileObject.Kind fileKind,
            String moduleName,
            SourceVersion release,
            Path outputDirectory,
            JavaFileObject.Kind outputFileKind) {
        this.root = Objects.requireNonNull(root);
        this.includes = Objects.requireNonNull(includes);
        this.excludes = Objects.requireNonNull(excludes);
        this.fileKind = Objects.requireNonNull(fileKind);
        this.moduleName = moduleName;
        this.release = release;
        if (moduleName != null) {
            outputDirectory = outputDirectory.resolve(moduleName);
        }
        if (release != null) {
            String version = release.name(); // TODO: replace by runtimeVersion() in Java 18.
            version = version.substring(version.lastIndexOf('_') + 1);
            outputDirectory = outputDirectoryForReleases(outputDirectory).resolve(version);
        }
        this.outputDirectory = outputDirectory;
        this.outputFileKind = outputFileKind;
    }

    /**
     * Returns the directory where to write the compilation for a specific Java release.
     * The caller shall add the version number to the returned path.
     */
    static Path outputDirectoryForReleases(Path outputDirectory) {
        // TODO: use Path.resolve(String, String...) with Java 22.
        return outputDirectory.resolve("META-INF").resolve("versions");
    }

    /**
     * Converts a version from Maven API to Java tools API.
     * This method parses the version with {@link Runtime.Version#parse(String)}.
     * Therefore, for Java 8, the version shall be "8", not "1.8".
     */
    private static SourceVersion parse(final Version version) {
        String text = version.asString();
        try {
            var parsed = Runtime.Version.parse(text);
            return SourceVersion.valueOf("RELEASE_" + parsed.feature());
            // TODO: Replace by return SourceVersion.valueOf(v) after upgrade to Java 18.
        } catch (IllegalArgumentException e) {
            throw new CompilationFailureException("Illegal version number: \"" + text + '"', e);
        }
    }

    /**
     * Gets the list of source directories from the project manager.
     * The returned list includes only the directories that exist.
     *
     * @param compileSourceRoots the root paths to source files
     * @param outputDirectory the directory where to store the compilation results
     * @return the given list of paths wrapped as source directory objects
     */
    static List<SourceDirectory> fromProject(Stream<SourceRoot> compileSourceRoots, Path outputDirectory) {
        var roots = new ArrayList<SourceDirectory>();
        compileSourceRoots.forEach((SourceRoot source) -> {
            Path directory = source.directory();
            if (Files.exists(directory)) {
                var fileKind = JavaFileObject.Kind.OTHER;
                var outputFileKind = JavaFileObject.Kind.OTHER;
                if (Language.JAVA_FAMILY.equals(source.language())) {
                    fileKind = JavaFileObject.Kind.SOURCE;
                    outputFileKind = JavaFileObject.Kind.CLASS;
                }
                roots.add(new SourceDirectory(
                        directory,
                        source.includes(),
                        source.excludes(),
                        fileKind,
                        source.module().orElse(null),
                        source.targetVersion().map(SourceDirectory::parse).orElse(null),
                        outputDirectory,
                        outputFileKind));
            }
        });
        return roots;
    }

    /**
     * Converts the given list of paths to a list of source directories.
     * The returned list includes only the directories that exist.
     * Used only when the compiler plugin is configured with the {@code compileSourceRoots} option.
     *
     * @param compileSourceRoots the root paths to source files
     * @param outputDirectory the directory where to store the compilation results
     * @return the given list of paths wrapped as source directory objects
     */
    static List<SourceDirectory> fromPluginConfiguration(List<String> compileSourceRoots, Path outputDirectory) {
        var roots = new ArrayList<SourceDirectory>(compileSourceRoots.size());
        for (String file : compileSourceRoots) {
            Path directory = Path.of(file);
            if (Files.exists(directory)) {
                roots.add(new SourceDirectory(
                        directory,
                        List.of(),
                        List.of(),
                        JavaFileObject.Kind.SOURCE,
                        null,
                        null,
                        outputDirectory,
                        JavaFileObject.Kind.CLASS));
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
            return root.equals(other.root)
                    && includes.equals(other.includes)
                    && excludes.equals(other.excludes)
                    && fileKind == other.fileKind
                    && Objects.equals(moduleName, other.moduleName)
                    && release == other.release
                    && outputDirectory.equals(other.outputDirectory)
                    && outputFileKind == other.outputFileKind;
        }
        return false;
    }

    /**
     * {@return a hash code value for this root directory}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(root, moduleName, release);
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
