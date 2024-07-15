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

import javax.tools.JavaFileObject;

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
 * If no syntax is specified, then the default syntax is "glob" with the following modification:
 *
 * <ul>
 *   <li>Unless escaped by {@code '\'}, all occurrences of the {@code '/'} character are replaced
 *       by the file system specific separator.</li>
 * </ul>
 *
 * The list of files to process is built by applying the path matcher on each regular (non directory) files.
 * The walk in file trees has the following characteristics:
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
    private final boolean defaultInclude;

    /**
     * All inclusion filters for the files in the directories to walk. The array shall contain at least one element.
     * If {@link #defaultInclude} is {@code true}, then this array length shall be exactly 1 and the single element
     * is overwritten for each directory to walk.
     */
    private final String[] includes;

    /**
     * All exclusion filters for the files in the directories to walk, or an empty array if none.
     */
    private final String[] excludes;

    /**
     * All exclusion filters for incremental build calculation, or an empty array if none.
     * Updated files, if excluded by this filter, will not cause the project to be rebuilt.
     */
    private final String[] incrementalExcludes;

    /**
     * The matchers for inclusion filters (never empty).
     * The array length shall be equal to the {@link #includes} array length.
     * The values are initially null and overwritten when first needed, then when the file system changes.
     */
    private final PathMatcher[] includeMatchers;

    /**
     * The matchers for exclusion filters (potentially empty).
     * The array length shall be equal to the {@link #excludes} array length.
     * The values are initially null and overwritten when first needed, then when the file system changes.
     */
    private final PathMatcher[] excludeMatchers;

    /**
     * The matchers for exclusion filters for incremental build calculation.
     * The array length shall be equal to the {@link #incrementalExcludes} array length.
     * The values are initially null and overwritten when first needed, then when the file system changes.
     */
    private final PathMatcher[] incrementalExcludeMatchers;

    /**
     * Whether paths must be relativized before to be given to a matcher. If {@code true} (the default),
     * then every paths will be made relative to the source root directory for allowing patterns like
     * {@code "foo/bar/*.java"} to work. As a slight optimization, we can skip this step if all patterns
     * start with {@code "**"}.
     */
    private final boolean needRelativize;

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
        defaultInclude = includes.isEmpty();
        if (defaultInclude) {
            includes = List.of("**");
        }
        this.includes = includes.toArray(String[]::new);
        this.excludes = excludes.toArray(String[]::new);
        this.incrementalExcludes = incrementalExcludes.toArray(String[]::new);
        includeMatchers = new PathMatcher[this.includes.length];
        excludeMatchers = new PathMatcher[this.excludes.length];
        incrementalExcludeMatchers = new PathMatcher[this.incrementalExcludes.length];
        needRelativize = needRelativize(this.includes) || needRelativize(this.excludes);
    }

    /**
     * Returns {@code true} if at least one pattern does not start with {@code "**"}.
     * This is a slight optimization for avoiding the need to relativize each path
     * before to give it to a matcher.
     */
    private static boolean needRelativize(String[] patterns) {
        for (String pattern : patterns) {
            if (!pattern.startsWith("**")) {
                return true;
            }
        }
        return false;
    }

    /**
     * If the default include patterns is used, updates it for the given kind of source files.
     *
     * @param sourceFileKind the kind of files to compile
     */
    private void updateDefaultInclude(JavaFileObject.Kind sourceFileKind) {
        if (defaultInclude) {
            String pattern = "glob:**" + sourceFileKind.extension;
            if (!pattern.equals(includes[0])) {
                includes[0] = pattern;
                if (fs != null) {
                    createMatchers(includes, includeMatchers, fs);
                }
            }
        }
    }

    /**
     * Fills the target array with path matchers created from the given patterns.
     * If a pattern does not specify a syntax, then the "glob" syntax is used by default
     * but with the {@code '/'} characters replaced by the file system specific separator.
     *
     * <p>This method should be invoked only once, unless different paths are on different file systems.</p>
     */
    private static void createMatchers(String[] patterns, PathMatcher[] target, FileSystem fs) {
        for (int i = 0; i < patterns.length; i++) {
            String pattern = patterns[i];
            if (pattern.indexOf(':') < 0) {
                var sb = new StringBuilder(pattern);
                String separator = fs.getSeparator();
                if (!separator.equals("/")) {
                    int j = pattern.length();
                    while ((j = pattern.lastIndexOf('/', j)) >= 0) {
                        /*
                         * Count the number of occurrences of the escape character, because that character
                         * can itself be escaped. The '/' character is escaped if the number of '\' before
                         * '/' is odd. The count is (s-j)-1, so testing if the count is odd is equivalent
                         * to testing if (s-j) is even (rightmost bit equals to 0). Since we want to do
                         * the replacement if non-escaped, the condition in inverted again.
                         */
                        final int s = j;
                        while (--j >= 0) {
                            if (pattern.charAt(j) != '\\') {
                                break;
                            }
                        }
                        if (((s - j) & 1) != 0) { // See above comment for explanation.
                            sb.replace(s, s + 1, separator);
                        }
                    }
                }
                pattern = sb.insert(0, "glob:").toString();
            }
            target[i] = fs.getPathMatcher(pattern);
        }
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
        FileSystem pfs = path.getFileSystem();
        if (pfs != fs) {
            createMatchers(includes, includeMatchers, pfs);
            createMatchers(excludes, excludeMatchers, pfs);
            createMatchers(incrementalExcludes, incrementalExcludeMatchers, pfs);
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
                updateDefaultInclude(directory.fileKind);
                Files.walkFileTree(directory.root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, this);
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
