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
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Applies inclusion and exclusion filters on paths, and builds a list of files in a directory tree.
 * The set of allowed syntax contains at least "glob" and "regex".
 * See {@link FileSystem#getPathMatcher(String)} Javadoc for a description of the "glob" syntax.
 * If no syntax is specified, then the default syntax is "glob".
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
final class PathFilter extends SimpleFileVisitor<Path> implements Predicate<Path> {
    /**
     * Whether to use the default include pattern.
     * The pattern depends on the type of source file.
     */
    private final boolean useDefaultInclude;

    /**
     * Inclusion filters for the files in the directories to walk as specified in the plugin configuration.
     * The array should contain at least one element, unless {@link SourceDirectory#includes} is non-empty.
     * If {@link #useDefaultInclude} is {@code true}, then this array length shall be exactly 1 and the
     * single element is overwritten for each directory to walk.
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
     * All exclusion filters for incremental build calculation, or an empty array if none.
     * Updated files, if excluded by this filter, will not cause the project to be rebuilt.
     */
    private final String[] incrementalExcludes;

    /**
     * The matchers for inclusion filters (never empty).
     * The array length shall be equal or greater than the {@link #includes} array length.
     * The array is initially null and created when first needed, then when the file system changes.
     */
    private PathMatcher[] includeMatchers;

    /**
     * The matchers for exclusion filters (potentially empty).
     * The array length shall be equal or greater than the {@link #excludes} array length.
     * The array is initially null and created when first needed, then when the file system changes.
     */
    private PathMatcher[] excludeMatchers;

    /**
     * The matchers for exclusion filters for incremental build calculation.
     * The array length shall be equal to the {@link #incrementalExcludes} array length.
     * The array is initially null and created when first needed, then when the file system changes.
     */
    private PathMatcher[] incrementalExcludeMatchers;

    /**
     * Whether paths must be relativized before to be given to a matcher. If {@code true} (the default),
     * then every paths will be made relative to the source root directory for allowing patterns like
     * {@code "foo/bar/*.java"} to work. As a slight optimization, we can skip this step if all patterns
     * start with {@code "**"}.
     */
    private boolean needRelativize;

    /**
     * The file system of the path matchers, or {@code null} if not yet determined.
     * This is used in case not all paths are on the same file system.
     */
    private FileSystem fs;

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
     * @param includes inclusion filters for the compiler, or empty for all source files
     * @param excludes exclusion filters for the compiler
     * @param incrementalExcludes exclusion filters for incremental build calculation
     */
    PathFilter(Collection<String> includes, Collection<String> excludes, Collection<String> incrementalExcludes) {
        useDefaultInclude = includes.isEmpty();
        if (useDefaultInclude) {
            includes = List.of("**"); // Place-holder replaced by "**/*.java" in `test(…)`.
        }
        this.includes = includes.toArray(String[]::new);
        this.excludes = excludes.toArray(String[]::new);
        this.incrementalExcludes = incrementalExcludes.toArray(String[]::new);
    }

    /**
     * Returns {@code true} if at least one pattern does not start with {@code "**"}.
     * This is a slight optimization for avoiding the need to relativize each path
     * before to give it to a matcher.
     */
    private static boolean needRelativize(String[] patterns) {
        for (String pattern : patterns) {
            if (!pattern.startsWith("**", pattern.indexOf(':') + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the matchers for the given patterns.
     * If a pattern does not specify a syntax, then the "glob" syntax is used by default.
     * If the {@code forDirectory} list contains at least one element and {@code patterns}
     * is the default pattern, then the latter is ignored in favor of the former.
     *
     * <p>This method should be invoked only once, unless different paths are on different file systems.</p>
     *
     * @param forDirectory the matchers declared in the {@code <source>} element for the current {@link #sourceRoot}
     * @param patterns the matterns declared in the compiler plugin configuration
     * @param hasDefault whether the first element of {@code patterns} is the default pattern
     * @param fs the file system
     * @return all matchers from the source, followed by matchers from the given patterns
     */
    private static PathMatcher[] createMatchers(
            List<PathMatcher> forDirectory, String[] patterns, boolean hasDefault, FileSystem fs) {
        final int base = forDirectory.size();
        final int skip = (hasDefault && base != 0) ? 1 : 0;
        final var target = forDirectory.toArray(new PathMatcher[base + patterns.length - skip]);
        for (int i = skip; i < patterns.length; i++) {
            String pattern = patterns[i];
            if (pattern.indexOf(':') < 0) {
                pattern = "glob:" + pattern;
            }
            target[base + i] = fs.getPathMatcher(pattern);
        }
        return target;
    }

    /**
     * Tests whether the given path should be included according the include/exclude patterns.
     * This method does not perform any I/O operation. For example, it does not check if the file is hidden.
     *
     * @param  path  the source file to test
     * @return whether the given source file should be included
     */
    @Override
    public boolean test(Path path) {
        @SuppressWarnings("LocalVariableHidesMemberVariable")
        final SourceDirectory sourceRoot = this.sourceRoot; // Protect from changes.
        FileSystem pfs = path.getFileSystem();
        if (pfs != fs) {
            if (useDefaultInclude) {
                includes[0] = "glob:**" + sourceRoot.fileKind.extension;
            }
            includeMatchers = createMatchers(sourceRoot.includes, includes, useDefaultInclude, pfs);
            excludeMatchers = createMatchers(sourceRoot.excludes, excludes, false, pfs);
            incrementalExcludeMatchers = createMatchers(List.of(), incrementalExcludes, false, pfs);
            needRelativize = !(sourceRoot.includes.isEmpty() && sourceRoot.excludes.isEmpty())
                    || needRelativize(includes)
                    || needRelativize(excludes);
            fs = pfs;
        }
        if (needRelativize) {
            path = sourceRoot.root.relativize(path);
        }
        for (PathMatcher include : includeMatchers) {
            if (include.matches(path)) {
                for (PathMatcher exclude : excludeMatchers) {
                    if (exclude.matches(path)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether to ignore the given file for incremental build calculation}.
     * This method shall be invoked only after {@link #test(Path)} for the same file,
     * because it depends on matcher updates performed by the {@code test} method.
     */
    private boolean ignoreModification(Path path) {
        for (PathMatcher exclude : incrementalExcludeMatchers) {
            if (exclude.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Invoked for a file in a directory. If the given file is not hidden and pass the include/exclude filters,
     * then it is added to the list of source files.
     */
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!isHidden(file, attrs) && test(file)) {
            sourceFiles.add(new SourceFile(sourceRoot, file, attrs, ignoreModification(file)));
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Invoked for a directory before entries in the directory are visited.
     * If the directory is hidden, then it is skipped.
     */
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        return isHidden(dir, attrs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
    }

    /**
     * {@return whether the given file is hidden}. This method is used instead of {@link Files#isHidden(Path)}
     * because it opportunistically uses the available attributes instead of making another access to the file system.
     */
    private static boolean isHidden(Path file, BasicFileAttributes attrs) {
        if (attrs instanceof DosFileAttributes dos) {
            return dos.isHidden();
        } else {
            return file.getFileName().toString().startsWith(".");
        }
    }

    /**
     * {@return all source files found in the given root directories}.
     * The include and exclude filters specified at construction time are applied.
     * Hidden files and directories are ignored, and symbolic links are followed.
     *
     * @param rootDirectories the root directories to scan
     * @throws IOException if a root directory cannot be walked
     */
    public List<SourceFile> walkSourceFiles(Iterable<SourceDirectory> rootDirectories) throws IOException {
        final var result = new ArrayList<SourceFile>();
        try {
            sourceFiles = result;
            for (SourceDirectory directory : rootDirectories) {
                sourceRoot = directory;
                Files.walkFileTree(directory.root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, this);
                fs = null; // Will force a recalculation of matchers in next iteration.
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            sourceRoot = null;
            sourceFiles = null;
        }
        return result;
    }
}
