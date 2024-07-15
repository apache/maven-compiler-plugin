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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.api.plugin.MojoException;

/**
 * Helper methods to support incremental builds.
 */
final class IncrementalBuild {
    /**
     * Elements to take in consideration when deciding whether to recompile a file.
     *
     * @see AbstractCompilerMojo#incrementalCompilation
     */
    enum Aspect {
        /**
         * Recompile all source files if the compiler options changed.
         * Changes are detected on a <i>best-effort</i> basis only.
         */
        OPTIONS(Set.of()),

        /**
         * Recompile all source files if at least one dependency (JAR file) changed since the last build.
         * This check is based on the last modification times of JAR files.
         *
         * <h4>Implementation note</h4>
         * The checks use information about the previous build saved in {@code target/…/*.cache} files.
         * Deleting those files cause a recompilation of all sources.
         */
        DEPENDENCIES(Set.of()),

        /**
         * Recompile source files modified since the last build.
         * In addition, if a source file has been deleted, then all source files are recompiled.
         * This check is based on the last modification times of source files,
         * not on the existence or modification times of the {@code *.class} files.
         *
         * <p>It is usually not needed to specify both {@code SOURCES} and {@link #CLASSES}.
         * But doing so it not forbidden.</p>
         *
         * <h4>Implementation note</h4>
         * The checks use information about the previous build saved in {@code target/…/*.cache} files.
         * Deleting those files cause a recompilation of all sources.
         */
        SOURCES(Set.of()),

        /**
         * Recompile source files ({@code *.java}) associated to no output file ({@code *.class})
         * or associated to an output file older than the source. This algorithm does not check
         * if a source file has been removed, potentially leaving non-recompiled classes with
         * references to classes that no longer exist.
         *
         * <p>It is usually not needed to specify both {@link #SOURCES} and {@code CLASSES}.
         * But doing so it not forbidden.</p>
         *
         * <h4>Implementation note</h4>
         * This check does not use or generate any {@code *.cache} file.
         */
        CLASSES(Set.of()),

        /**
         * Recompile all source files when the addition of a new file is detected.
         * This aspect should be used together with {@link #SOURCES} or {@link #CLASSES}.
         * When used with {@link #CLASSES}, it provides a way to detect class renaming
         * (this is not needed with {@link #SOURCES}).
         */
        ADDITIONS(Set.of()),

        /**
         * Recompile modules and let the compiler decides which individual files to recompile.
         * The compiler plugin does not enumerate the source files to recompile (actually, it does not scan at all the
         * source directories). Instead, it only specifies the module to recompile using the {@code --module} option.
         * The Java compiler will scan the source directories itself and compile only those source files that are newer
         * than the corresponding files in the output directory.
         *
         * <p>This option is available only at the following conditions:</p>
         * <ul>
         *   <li>All sources of the project to compile are modules in the Java sense.</li>
         *   <li>{@link #SOURCES}, {@link #CLASSES} and {@link #ADDITIONS} aspects are not used.</li>
         *   <li>There is no include/exclude filter.</li>
         * </ul>
         */
        MODULES(Set.of(SOURCES, CLASSES, ADDITIONS)),

        /**
         * The compiler plugin unconditionally specifies all sources to the Java compiler.
         * This aspect is mutually exclusive with all other aspects.
         */
        NONE(Set.of(OPTIONS, DEPENDENCIES, SOURCES, CLASSES, ADDITIONS, MODULES));

        /**
         * If this aspect is mutually exclusive with other aspects, the excluded aspects.
         */
        private final Set<Aspect> excludes;

        /**
         * Creates a new enumeration value.
         *
         * @param excludes the aspects that are mutually exclusive with this aspect
         */
        Aspect(Set<Aspect> excludes) {
            this.excludes = excludes;
        }

        /**
         * Returns the name in lower-case, for producing error message.
         */
        @Override
        public String toString() {
            return name().toLowerCase(Locale.US);
        }

        /**
         * Parses a comma-separated list of aspects.
         *
         * @param values the plugin parameter to parse as a comma-separated list
         * @return the aspect
         * @throws MojoException if a value is not recognized, or if mutually exclusive values are specified
         */
        static EnumSet<Aspect> parse(final String values) {
            var aspects = EnumSet.noneOf(Aspect.class);
            for (String value : values.split(",")) {
                value = value.trim();
                try {
                    aspects.add(valueOf(value.toUpperCase(Locale.US)));
                } catch (IllegalArgumentException e) {
                    var sb = new StringBuilder(256)
                            .append("Illegal incremental build setting: \"")
                            .append(value);
                    String s = "\". Valid values are ";
                    for (Aspect aspect : values()) {
                        sb.append(s).append(aspect);
                        s = ", ";
                    }
                    throw new CompilationFailureException(sb.append('.').toString(), e);
                }
            }
            for (Aspect aspect : aspects) {
                for (Aspect exclude : aspect.excludes) {
                    if (aspects.contains(exclude)) {
                        throw new CompilationFailureException("Illegal incremental build setting: \"" + aspect
                                + "\" and \"" + exclude + "\" are mutually exclusive.");
                    }
                }
            }
            if (aspects.isEmpty()) {
                throw new CompilationFailureException("Incremental build setting cannot be empty.");
            }
            return aspects;
        }
    }

    /**
     * The options for following links. An empty array means that links will be followed.
     */
    private static final LinkOption[] LINK_OPTIONS = new LinkOption[0];

    /**
     * Magic number, generated randomly, to store in the header of the binary file.
     * This number shall be changed every times that the binary file format is modified.
     * The file format is described in {@link #writeCache()}.
     *
     * @see #writeCache()
     */
    private static final long MAGIC_NUMBER = -8163803035240576921L;

    /**
     * Flags in the binary output file telling whether the source and/or target directory changed.
     * Those flags are stored as a byte before each entry. They can be combined as bit mask.
     * Those flags are for compressing the binary file, not for detecting if something changed
     * since the last build.
     */
    private static final byte NEW_SOURCE_DIRECTORY = 1, NEW_TARGET_DIRECTORY = 2;

    /**
     * Flag in the binary output file telling that the output file of a source is different
     * than the one inferred by heuristic rules. For performance reason, we store the output
     * files explicitly only when it cannot be inferred.
     *
     * @see SourceInfo#toOutputFile(Path, Path, Path)
     * @see javax.tools.JavaFileManager#getFileForOutput
     */
    private static final byte EXPLICIT_OUTPUT_FILE = 4;

    /**
     * Name of the file where to store the list of source files and the list of files created by the compiler.
     * This is a binary format used for detecting changes. The file is stored in the {@code target} directory.
     * If the file is absent of corrupted, it will be ignored and recreated.
     *
     * @see AbstractCompilerMojo#mojoStatusPath
     */
    private final Path cacheFile;

    /**
     * Whether the cache file has been loaded.
     */
    private boolean cacheLoaded;

    /**
     * All source files together with their last modification time.
     * This list is specified at construction time and is not modified by this class.
     *
     * @see #getModifiedSources()
     */
    private final List<SourceFile> sourceFiles;

    /**
     * The build time in milliseconds since January 1st, 1970.
     * This is used for detecting if a dependency changed since the previous build.
     */
    private final long buildTime;

    /**
     * Time of the previous build. This value is initialized by {@link #loadCache()}.
     * If the cache cannot be loaded, then this field is conservatively set to the same value
     * as {@link #buildTime}, but it shouldn't matter because a full build will be done anyway.
     */
    private long previousBuildTime;

    /**
     * Hash code value of the compiler options during the previous build.
     * This value is initialized by {@link #loadCache()}.
     */
    private int previousOptionsHash;

    /**
     * Whether to provide more details about why a module is rebuilt.
     */
    private final boolean showCompilationChanges;

    /**
     * Creates a new helper for an incremental build.
     *
     * @param mojo the MOJO which is compiling source code
     * @param sourceFiles all source files
     * @throws IOException if the parent directory cannot be created
     */
    IncrementalBuild(AbstractCompilerMojo mojo, List<SourceFile> sourceFiles) throws IOException {
        this.sourceFiles = sourceFiles;
        Path file = mojo.mojoStatusPath;
        cacheFile = Files.createDirectories(file.getParent()).resolve(file.getFileName());
        showCompilationChanges = mojo.showCompilationChanges;
        buildTime = System.currentTimeMillis();
        previousBuildTime = buildTime;
    }

    /**
     * Saves the list of source files in the cache file. The cache is a binary file
     * and its format may change in any future version. The current format is as below:
     *
     * <ul>
     *   <li>The magic number (while change when the format changes).</li>
     *   <li>The build time in milliseconds since January 1st, 1970.</li>
     *   <li>Hash code value of the {@link Options#options} list.</li>
     *   <li>Number of source files, or 0 if {@code sources} is {@code false}.</li>
     *   <li>If {@code sources} is {@code true}, then for each source file:<ul>
     *     <li>A bit mask of {@link #NEW_SOURCE_DIRECTORY}, {@link #NEW_TARGET_DIRECTORY} and {@link #EXPLICIT_OUTPUT_FILE}.</li>
     *     <li>If {@link #NEW_SOURCE_DIRECTORY} is set, the new root directory of source files.</li>
     *     <li>If {@link #NEW_TARGET_DIRECTORY} is set, the new root directory of output files.</li>
     *     <li>If {@link #EXPLICIT_OUTPUT_FILE} is set, the output file.</li>
     *     <li>The file path relative to the parent of the previous file.</li>
     *     <li>Last modification time of the source file, in milliseconds since January 1st.</li>
     *   </ul></li>
     * </ul>
     *
     * The "is sibling" Boolean is for avoiding to repeat the parent directory. If that flag is {@code true},
     * then only the filename is stored and the parent is the same as the previous file.
     *
     * @param optionsHash hash code value of the {@link Options#options} list
     * @param sources whether to save also the list of source files
     * @throws IOException if an error occurred while writing the cache file
     */
    @SuppressWarnings({"checkstyle:InnerAssignment", "checkstyle:NeedBraces"})
    public void writeCache(final int optionsHash, final boolean sources) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                cacheFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)))) {
            out.writeLong(MAGIC_NUMBER);
            out.writeLong(buildTime);
            out.writeInt(optionsHash);
            out.writeInt(sources ? sourceFiles.size() : 0);
            if (sources) {
                Path srcDir = null;
                Path tgtDir = null;
                Path previousParent = null;
                for (SourceFile source : sourceFiles) {
                    final Path sourceFile = source.file;
                    final Path outputFile = source.getOutputFile(false);
                    boolean sameSrcDir = Objects.equals(srcDir, srcDir = source.directory.root);
                    boolean sameTgtDir = Objects.equals(tgtDir, tgtDir = source.directory.outputDirectory);
                    boolean sameOutput = (outputFile == null)
                            || outputFile.equals(SourceInfo.toOutputFile(srcDir, tgtDir, sourceFile));

                    out.writeByte((sameSrcDir ? 0 : NEW_SOURCE_DIRECTORY)
                            | (sameTgtDir ? 0 : NEW_TARGET_DIRECTORY)
                            | (sameOutput ? 0 : EXPLICIT_OUTPUT_FILE));

                    if (!sameSrcDir) out.writeUTF((previousParent = srcDir).toString());
                    if (!sameTgtDir) out.writeUTF(tgtDir.toString());
                    if (!sameOutput) out.writeUTF(outputFile.toString());
                    out.writeUTF(previousParent.relativize(sourceFile).toString());
                    out.writeLong(source.lastModified);
                    previousParent = sourceFile.getParent();
                }
            }
        }
    }

    /**
     * Loads the list of source files and their modification times from the previous build.
     * The binary file format reads by this method is described in {@link #writeCache()}.
     * The keys are the source files. The returned map is modifiable.
     *
     * @return the source files of previous build
     * @throws IOException if an error occurred while reading the cache file
     */
    @SuppressWarnings("checkstyle:NeedBraces")
    private Map<Path, SourceInfo> loadCache() throws IOException {
        final Map<Path, SourceInfo> previousBuild;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(cacheFile, StandardOpenOption.READ)))) {
            if (in.readLong() != MAGIC_NUMBER) {
                throw new IOException("Invalid cache file.");
            }
            previousBuildTime = in.readLong();
            previousOptionsHash = in.readInt();
            int remaining = in.readInt();
            previousBuild = new HashMap<>(remaining + remaining / 3);
            Path srcDir = null;
            Path tgtDir = null;
            Path srcFile = null;
            while (--remaining >= 0) {
                final byte flags = in.readByte();
                if ((flags & ~(NEW_SOURCE_DIRECTORY | NEW_TARGET_DIRECTORY | EXPLICIT_OUTPUT_FILE)) != 0) {
                    throw new IOException("Invalid cache file.");
                }
                boolean newSrcDir = (flags & NEW_SOURCE_DIRECTORY) != 0;
                boolean newTgtDir = (flags & NEW_TARGET_DIRECTORY) != 0;
                boolean newOutput = (flags & EXPLICIT_OUTPUT_FILE) != 0;
                Path output = null;
                if (newSrcDir) srcDir = Path.of(in.readUTF());
                if (newTgtDir) tgtDir = Path.of(in.readUTF());
                if (newOutput) output = Path.of(in.readUTF());
                String path = in.readUTF();
                srcFile = newSrcDir ? srcDir.resolve(path) : srcFile.resolveSibling(path);
                srcFile = srcFile.normalize();
                if (previousBuild.put(srcFile, new SourceInfo(srcDir, tgtDir, output, in.readLong())) != null) {
                    throw new IOException("Duplicated source file declared in the cache: " + srcFile);
                }
            }
        }
        cacheLoaded = true;
        return previousBuild;
    }

    /**
     * Information about a source file from a previous build.
     *
     * @param sourceDirectory root directory of the source file
     * @param outputDirectory output directory of the compiled file
     * @param outputFile the output file if it was explicitly specified, or {@code null} if it can be inferred
     * @param lastModified last modification times of the source file during the previous build
     */
    private static record SourceInfo(Path sourceDirectory, Path outputDirectory, Path outputFile, long lastModified) {
        /**
         * The default output extension used in heuristic rules. It is okay if the actual output file does not use
         * this extension, because the heuristic rules should be applied only when we have detected that they apply.
         */
        private static final String OUTPUT_EXTENSION = SourceDirectory.CLASS_FILE_SUFFIX;

        /**
         * Infers the path to the output file using heuristic rules. This method is used for saving space in the
         * common space where the heuristic rules work. If the heuristic rules do not work, the full output path
         * will be stored in the {@link #cacheFile}.
         *
         * @param sourceDirectory root directory of the source file
         * @param outputDirectory output directory of the compiled file
         * @param sourceFile path to the source file
         * @return path to the target file
         */
        static Path toOutputFile(Path sourceDirectory, Path outputDirectory, Path sourceFile) {
            return SourceFile.toOutputFile(
                    sourceDirectory, outputDirectory, sourceFile, SourceDirectory.JAVA_FILE_SUFFIX, OUTPUT_EXTENSION);
        }

        /**
         * Delete all output files associated to the given source file. If the output file is a {@code .class} file,
         * then this method deletes also the output files for all inner classes (e.g. {@code "Foo$0.class"}).
         *
         * @param sourceFile the source file for which to delete output files
         * @throws IOException if an error occurred while scanning the output directory or deleting a file
         */
        void deleteClassFiles(final Path sourceFile) throws IOException {
            Path output = outputFile;
            if (output == null) {
                output = toOutputFile(sourceDirectory, outputDirectory, sourceFile);
            }
            String filename = output.getFileName().toString();
            if (filename.endsWith(OUTPUT_EXTENSION)) {
                String prefix = filename.substring(0, filename.length() - OUTPUT_EXTENSION.length());
                List<Path> outputs;
                try (Stream<Path> files = Files.walk(output.getParent(), 1)) {
                    outputs = files.filter((f) -> {
                                String name = f.getFileName().toString();
                                return name.startsWith(prefix)
                                        && name.endsWith(OUTPUT_EXTENSION)
                                        && (name.equals(filename) || name.charAt(prefix.length()) == '$');
                            })
                            .toList();
                }
                for (Path p : outputs) {
                    Files.delete(p);
                }
            } else {
                Files.deleteIfExists(output);
            }
        }
    }

    /**
     * Detects whether the list of detected files has changed since the last build.
     * This method loads the list of files of the previous build from a status file
     * and compare it with the new list. If the file cannot be read, then this method
     * conservatively assumes that the file tree changed.
     *
     * <p>If this method returns {@code null}, the caller can check the {@link SourceFile#isNewOrModified} flag
     * for deciding which files to recompile. If this method returns non-null value, then the {@code isModified}
     * flag should be ignored and all files recompiled unconditionally. The returned non-null value is a message
     * saying why the project needs to be rebuilt.</p>
     *
     * @param staleMillis the granularity in milliseconds to use for comparing modification times
     * @param rebuildOnAdd whether to recompile all source files if a file addition is detected
     * @return {@code null} if the project does not need to be rebuilt, otherwise a message saying why to rebuild
     * @throws IOException if an error occurred while deleting output files of the previous build
     *
     * @see Aspect#SOURCES
     */
    String inputFileTreeChanges(final long staleMillis, final boolean rebuildOnAdd) throws IOException {
        final Map<Path, SourceInfo> previousBuild;
        try {
            previousBuild = loadCache();
        } catch (NoSuchFileException e) {
            return "Compiling all files.";
        } catch (IOException e) {
            return causeOfRebuild("information about the previous build cannot be read", true)
                    .append(System.lineSeparator())
                    .append(e)
                    .toString();
        }
        boolean rebuild = false;
        boolean allChanged = true;
        List<Path> added = new ArrayList<>();
        for (SourceFile source : sourceFiles) {
            SourceInfo previous = previousBuild.remove(source.file);
            if (previous != null) {
                if (source.lastModified - previous.lastModified <= staleMillis) {
                    /*
                     * Source file has not been modified. But we still need to check if the output file exists.
                     * It may be, for example, because the compilation failed during the previous build because
                     * of another class.
                     */
                    allChanged = false;
                    Path output = source.getOutputFile(true);
                    if (Files.exists(output, LINK_OPTIONS)) {
                        continue; // Source file has not been modified and output file exists.
                    }
                }
            } else if (!source.ignoreModification) {
                if (showCompilationChanges) {
                    added.add(source.file);
                }
                rebuild |= rebuildOnAdd;
            }
            source.isNewOrModified = true;
        }
        /*
         * The files remaining in `previousBuild` are files that have been removed since the last build.
         * If no file has been removed, then there is no need to rebuild the whole project (added files
         * do not require a full build).
         */
        if (previousBuild.isEmpty()) {
            if (allChanged) {
                return causeOfRebuild("all source files changed", false).toString();
            }
            if (!rebuild) {
                return null;
            }
        }
        /*
         * If some files have been removed, we need to delete the corresponding output files.
         * If the output file extension is ".class", then many files may be deleted because
         * the output file may be accompanied by inner classes (e.g. {@code "Foo$0.class"}).
         */
        for (Map.Entry<Path, SourceInfo> removed : previousBuild.entrySet()) {
            removed.getValue().deleteClassFiles(removed.getKey());
        }
        /*
         * At this point, it has been decided that all source files will be recompiled.
         * Format a message saying why.
         */
        StringBuilder causeOfRebuild = causeOfRebuild("of added or removed source files", showCompilationChanges);
        if (showCompilationChanges) {
            for (Path fileAdded : added) {
                causeOfRebuild.append(System.lineSeparator()).append("  + ").append(fileAdded);
            }
            for (Path fileRemoved : previousBuild.keySet()) {
                causeOfRebuild.append(System.lineSeparator()).append("  - ").append(fileRemoved);
            }
        }
        return causeOfRebuild.toString();
    }

    /**
     * Returns whether at least one dependency file is more recent than the given build start time.
     * This method should be invoked only after {@link #inputFileTreeChanges} returned {@code null}.
     * Each given root can be either a regular file (typically a JAR file) or a directory.
     * Directories are scanned recursively.
     *
     * @param directories files or directories to scan
     * @param fileExtensions extensions of the file to check (usually "jar" and "class")
     * @param changeTime the time at which a file is considered as changed
     * @return {@code null} if the project does not need to be rebuilt, otherwise a message saying why to rebuild
     * @throws IOException if an error occurred while scanning the directories
     *
     * @see Aspect#DEPENDENCIES
     */
    String dependencyChanges(Iterable<List<Path>> dependencies, Collection<String> fileExtensions) throws IOException {
        if (!cacheLoaded) {
            loadCache();
        }
        final FileTime changeTime = FileTime.fromMillis(previousBuildTime);
        List<Path> updated = new ArrayList<>();
        for (List<Path> roots : dependencies) {
            for (Path root : roots) {
                try (Stream<Path> files = Files.walk(root)) {
                    files.filter((f) -> {
                                String name = f.getFileName().toString();
                                int s = name.lastIndexOf('.');
                                if (s < 0 || !fileExtensions.contains(name.substring(s + 1))) {
                                    return false;
                                }
                                try {
                                    return Files.isRegularFile(f)
                                            && Files.getLastModifiedTime(f).compareTo(changeTime) >= 0;
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            })
                            .forEach(updated::add);
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            }
        }
        if (updated.isEmpty()) {
            return null;
        }
        StringBuilder causeOfRebuild = causeOfRebuild("some dependencies changed", showCompilationChanges);
        if (showCompilationChanges) {
            for (Path file : updated) {
                causeOfRebuild.append(System.lineSeparator()).append("    ").append(file);
            }
        }
        return causeOfRebuild.toString();
    }

    /**
     * Returns whether the compilar options have changed.
     * This method should be invoked only after {@link #inputFileTreeChanges} returned {@code null}.
     *
     * @param optionsHash hash code value of the {@link Options#options} list
     * @return {@code null} if the project does not need to be rebuilt, otherwise a message saying why to rebuild
     * @throws IOException if an error occurred while loading the cache file
     *
     * @see Aspect#OPTIONS
     */
    String optionChanges(int optionsHash) throws IOException {
        if (!cacheLoaded) {
            loadCache();
        }
        if (optionsHash == previousOptionsHash) {
            return null;
        }
        return causeOfRebuild("of changes in compiler options", false).toString();
    }

    /**
     * Prepares a message saying why a full rebuild is done. A colon character will be added
     * if showing compilation changes is enabled, otherwise a period is added.
     *
     * @param cause the cause of the rebuild, without trailing colon or period
     * @param colon whether to append a colon instead of a period after the message
     * @return a buffer where more details can be appended for reporting the cause
     */
    private static StringBuilder causeOfRebuild(String cause, boolean colon) {
        return new StringBuilder(128)
                .append("Recompiling all files because ")
                .append(cause)
                .append(colon ? ':' : '.');
    }

    /**
     * Compares the modification time of all source files with the modification time of output files.
     * The files identified as in need to be recompiled have their {@link SourceFile#isNewOrModified}
     * flag set to {@code true}. This method does not use the cache file.
     *
     * @param staleMillis the granularity in milliseconds to use for comparing modification times
     * @param rebuildOnAdd whether to recompile all source files if a file addition is detected
     * @return {@code null} if the project does not need to be rebuilt, otherwise a message saying why to rebuild
     * @throws IOException if an error occurred while reading the time stamp of an output file
     *
     * @see Aspect#CLASSES
     */
    String markNewOrModifiedSources(long staleMillis, boolean rebuildOnAdd) throws IOException {
        for (SourceFile source : sourceFiles) {
            if (!source.isNewOrModified) {
                // Check even if `source.ignoreModification` is true.
                Path output = source.getOutputFile(true);
                if (Files.exists(output, LINK_OPTIONS)) {
                    FileTime t = Files.getLastModifiedTime(output, LINK_OPTIONS);
                    if (source.lastModified - t.toMillis() <= staleMillis) {
                        continue;
                    }
                } else if (rebuildOnAdd) {
                    StringBuilder causeOfRebuild = causeOfRebuild("of added source files", showCompilationChanges);
                    if (showCompilationChanges) {
                        causeOfRebuild
                                .append(System.lineSeparator())
                                .append("  + ")
                                .append(source.file);
                    }
                    return causeOfRebuild.toString();
                }
                source.isNewOrModified = true;
            }
        }
        return null;
    }

    /**
     * Returns the source files that are marked as new or modified. The returned list may contain files
     * that are new or modified, but should nevertheless be ignored in the decision to recompile or not.
     * In order to decide if a compilation is needed, invoke {@link #isEmptyOrIgnorable(List)} instead
     * of {@link List#isEmpty()}.
     *
     * @return new or modified source files, or an empty list if none
     */
    List<SourceFile> getModifiedSources() {
        return sourceFiles.stream().filter((s) -> s.isNewOrModified).toList();
    }

    /**
     * {@return whether the given list of modified files should not cause a recompilation}.
     * This method returns {@code true} if the given list is empty or contains only files
     * with the {@link SourceFile#ignoreModification} set to {@code true}.
     *
     * @param sourceFiles return value of {@link #getModifiedSources()}.
     */
    static boolean isEmptyOrIgnorable(List<SourceFile> sourceFiles) {
        return !sourceFiles.stream().anyMatch((s) -> !s.ignoreModification);
    }
}
