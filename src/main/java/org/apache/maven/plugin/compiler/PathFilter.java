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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * Applies inclusion and exclusion filters on paths, and builds a list of files in a directory tree.
 * The set of allowed syntax contains at least "glob" and "regex".
 * See {@link FileSystem#getPathMatcher(String)} Javadoc for a description of the "glob" syntax.
 * If no syntax is specified, then the default syntax is a derivative of the "glob" syntax which
 * reproduces the behavior of Maven 3.
 *
 * <p>The list of files to process is built by applying the path matcher on each regular (non directory) files.
 * The walk in file trees has the following characteristics:</p>
 *
 * <ul>
 *   <li>Symbolic links are followed.</li>
 *   <li>Hidden files and hidden directories are ignored.</li>
 * </ul>
 *
 * Instances of this class can be reused for filtering many directories, but is not thread safe.
 * Each instance shall be used by a single thread only.
 *
 * @author Martin Desruisseaux
 */
final class PathFilter extends SimpleFileVisitor<Path> {
    /**
     * Whether to use the default include pattern.
     * The pattern depends on the type of source file.
     *
     * @see javax.tools.JavaFileObject.Kind#extension
     */
    private final boolean useDefaultInclude;

    /**
     * Inclusion filters for the files in the directories to walk as specified in the plugin configuration.
     * The array should contain at least one element. If {@link #useDefaultInclude} is {@code true}, then
     * this array length shall be exactly 1 and the single element is overwritten for each directory to walk.
     *
     * @see SourceDirectory#includes
     */
    private final String[] includes;

    /**
     * Exclusion filters for the files in the directories to walk as specified in the plugin configuration.
     *
     * @see SourceDirectory#excludes
     */
    private final String[] excludes;

    /**
     * Combination of include and exclude filters. This is an instance of {@link PathSelector},
     * unless the includes/excludes can be simplified to a single standard matcher instance.
     */
    private PathMatcher matchers;

    /**
     * All exclusion filters for incremental build calculation, or an empty list if none.
     * Updated files, if excluded by a pattern, will not cause the project to be rebuilt.
     */
    private final Collection<String> incrementalExcludes;

    /**
     * The matchers for exclusion filters for incremental build calculation.
     * May be an instance of {@link PathSelector}, or {@code null} if none.
     */
    private PathMatcher incrementalExcludeMatchers;

    /**
     * The result of listing all files, or {@code null} if no walking is in progress.
     * This field is temporarily assigned a value when walking in a tree of directories,
     * then reset to {@code null} after the walk finished.
     */
    private List<SourceFile> sourceFiles;

    /**
     * The root directory of files being scanned.
     * This field is temporarily assigned a value when walking in a tree of directories,
     * then reset to {@code null} after the walk finished.
     */
    private SourceDirectory sourceRoot;

    /**
     * Creates a new filter.
     *
     * @param mojo the <abbr>MOJO</abbr> from which to take the includes/excludes configuration
     */
    PathFilter(AbstractCompilerMojo mojo) {
        Collection<String> specified = mojo.getIncludes();
        useDefaultInclude = specified.isEmpty();
        if (useDefaultInclude) {
            specified = List.of("**"); // Place-holder replaced by "**/*.java" in `test(…)`.
        }
        includes = specified.toArray(String[]::new);
        excludes = mojo.getExcludes().toArray(String[]::new);
        incrementalExcludes = mojo.getIncrementalExcludes();
    }

    /**
     * Invoked for a file in a directory. If the given file passes the include/exclude filters,
     * then it is added to the list of source files.
     *
     * @param  file  the source file to test
     * @param  attrs the file basic attributes
     * @return {@link FileVisitResult#CONTINUE}
     */
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (matchers.matches(file)) {
            sourceFiles.add(new SourceFile(
                    sourceRoot,
                    file,
                    attrs,
                    (incrementalExcludeMatchers != null) && incrementalExcludeMatchers.matches(file)));
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Invoked for a directory before entries in the directory are visited.
     * If the directory is hidden, then it is skipped.
     */
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        return Files.isHidden(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
    }

    /**
     * {@return all source files found in the given root directories}.
     * The include and exclude filters specified at construction time are applied.
     * Hidden directories are ignored, and symbolic links are followed.
     *
     * @param rootDirectories the root directories to scan
     * @throws IOException if a root directory cannot be walked
     */
    public List<SourceFile> walkSourceFiles(Iterable<SourceDirectory> rootDirectories) throws IOException {
        final var result = new ArrayList<SourceFile>();
        try {
            sourceFiles = result;
            for (SourceDirectory directory : rootDirectories) {
                if (!incrementalExcludes.isEmpty()) {
                    incrementalExcludeMatchers = new PathSelector(directory.root, incrementalExcludes, null).simplify();
                }
                String[] includesOrDefault = includes;
                if (useDefaultInclude) {
                    if (directory.includes.isEmpty()) {
                        includesOrDefault[0] = "glob:**" + directory.fileKind.extension;
                    } else {
                        includesOrDefault = null;
                    }
                }
                sourceRoot = directory;
                matchers = new PathSelector(
                                directory.root,
                                concat(directory.includes, includesOrDefault),
                                concat(directory.excludes, excludes))
                        .simplify();
                Files.walkFileTree(directory.root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, this);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            sourceRoot = null;
            sourceFiles = null;
            matchers = null;
        }
        return result;
    }

    /**
     * Returns the concatenation of patterns specified in the source with the patterns specified in the plugin.
     * As a side-effect, this method set the {@link #needRelativize} flag to {@code true} if at least one pattern
     * does not start with {@code "**"}. The latter is a slight optimization for avoiding the need to relativize
     * each path before to give it to a matcher when this relativization is not necessary.
     *
     * @param source  the patterns specified in the {@code <source>} element
     * @param plugin  the patterns specified in the {@code <plugin>} element, or null if none
     */
    private static List<String> concat(List<String> source, String[] plugin) {
        if (plugin == null || plugin.length == 0) {
            return source;
        }
        var patterns = new ArrayList<String>(source);
        patterns.addAll(Arrays.asList(plugin));
        return patterns;
    }
}
