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

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.PathType;

/**
 * Source files for a call to {@code javac} or {@code javadoc} command to be executed as a separated process.
 *
 * @author Martin Desruisseaux
 *
 * @see ForkedCompiler
 */
final class ForkedToolSources implements StandardJavaFileManager {
    /**
     * Option for source files. These options are not declared in
     * {@link JavaPathType} because they are not about dependencies.
     */
    private record OtherPathType(String name, String optionString, String moduleName) implements PathType {
        /**
         * An option for the directory of source files of a module.
         *
         * @param   moduleName  name of the module
         * @return  option for the directory of source files of the specified module
         */
        static OtherPathType moduleSources(String moduleName) {
            return new OtherPathType("MODULE_SOURCE_PATH", "--module-source-path", moduleName);
        }

        /**
         * The option for the directory of source files.
         */
        static final OtherPathType SOURCES = new OtherPathType("SOURCES", "--source-path", null);

        /**
         * The option for the directory of generated sources.
         */
        static final OtherPathType GENERATED_SOURCES = new OtherPathType("GENERATED_SOURCES", "-s", null);

        /**
         * The option for the directory of compiled class files.
         */
        static final OtherPathType OUTPUT = new OtherPathType("OUTPUT", "-d", null);

        @Override
        public String id() {
            return name;
        }

        @Override
        public Optional<String> option() {
            return Optional.of(optionString);
        }

        /**
         * Formats the option with the paths, <em>without quotes</em>.
         * The quotes are omitted because the paths will be given to
         * {@link java.lang.ProcessBuilder}, not to a command-line.
         */
        @Override
        public String[] option(Iterable<? extends Path> paths) {
            String prefix = (moduleName == null) ? "" : (moduleName + '=');
            var builder = new StringJoiner(File.pathSeparator, prefix, "");
            paths.forEach((path) -> builder.add(path.toString()));
            return new String[] {optionString, builder.toString()};
        }
    }

    /**
     * Search paths associated to locations.
     * This map only stores verbatim the collections provided by callers.
     *
     * @see #setLocationFromPaths(Location, Collection)
     * @see #getLocationAsPaths(Location)
     */
    private final Map<PathType, Collection<? extends Path>> locations;

    /**
     * The encoding of the files to read.
     */
    final Charset encoding;

    /**
     * Creates an initially empty collection of files.
     */
    ForkedToolSources(Charset encoding) {
        if (encoding == null) {
            encoding = Charset.defaultCharset();
        }
        this.encoding = encoding;
        locations = new HashMap<>();
    }

    /**
     * Unconditionally returns -1, meaning that the given option is unsupported.
     * Required by the interface, but not used by the Maven plugin.
     */
    @Override
    public int isSupportedOption(String option) {
        return -1;
    }

    /**
     * Nevers handle the given option.
     */
    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        return false;
    }

    /**
     * Returns the path to the source file represented by the given object.
     */
    @Override
    public Path asPath(FileObject file) {
        return (file instanceof Item) ? ((Item) file).path : Path.of(file.toUri());
    }

    /**
     * Checks if the given objects represents the same canonical file.
     * Required by the interface, but not used by the Maven plugin.
     */
    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return asPath(a).equals(asPath(b));
    }

    /**
     * Returns {@code JavaFileObject} instances representing the given filenames.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return fromNames(Arrays.stream(names));
    }

    /**
     * Returns {@code JavaFileObject} instances representing the given {@code File} instances.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return fromFiles(Arrays.stream(files));
    }

    /**
     * Returns {@code JavaFileObject} instances representing the given filenames.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        return fromNames(StreamSupport.stream(names.spliterator(), false));
    }

    /**
     * Returns {@code JavaFileObject} instances representing the given {@code File} instances.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        return fromFiles(StreamSupport.stream(files.spliterator(), false));
    }

    /**
     * Returns {@code JavaFileObject} instances representing the given {@code Path} instances.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromPaths(Collection<? extends Path> paths) {
        return paths.stream().map(Item::new).toList();
    }

    /**
     * Helper method for the construction of {@code JavaFileObject} instances from {@code File} instances.
     */
    private Iterable<? extends JavaFileObject> fromFiles(Stream<? extends File> files) {
        return files.map((file) -> new Item(file.toPath())).toList();
    }

    /**
     * Helper method for the construction of {@code JavaFileObject} instances from filenames.
     */
    private Iterable<? extends JavaFileObject> fromNames(Stream<? extends String> names) {
        return names.map((name) -> new Item(Path.of(name))).toList();
    }

    /**
     * A simple implementation of Java file as a wrapper around a path. This class implements some methods
     * as a matter of principle, but those methods should not be invoked because the file will not be opened
     * in this Java Virtual Machine. We only need a container for a {@link Path} instance.
     */
    private final class Item implements JavaFileObject {
        /**
         * Path to the source file.
         */
        final Path path;

        /**
         * Creates a new object for the given path to a Java source file.
         */
        Item(Path path) {
            this.path = path;
        }

        /**
         * Returns the path to the source file.
         */
        @Override
        public String getName() {
            return path.toString();
        }

        /**
         * Returns the path to the source file.
         */
        @Override
        public String toString() {
            return getName();
        }

        /**
         * Returns the path as an URI.
         */
        @Override
        public URI toUri() {
            return path.toUri();
        }

        /**
         * Returns whether the file is a source, a class or other kind of object.
         */
        @Override
        public Kind getKind() {
            String filename = path.getFileName().toString();
            for (Kind k : Kind.values()) {
                if (filename.endsWith(k.extension)) {
                    return k;
                }
            }
            return Kind.OTHER;
        }

        /**
         * Returns whether this object is compatible with the given non-qualified name and the given type.
         */
        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            return path.getFileName().toString().equals(simpleName.concat(kind.extension));
        }

        /**
         * Returns {@code null}, meaning that this object as no information about nesting kind.
         */
        @Override
        public NestingKind getNestingKind() {
            return null;
        }

        /**
         * Returns {@code null}, meaning that this object as no information about access level.
         */
        @Override
        public Modifier getAccessLevel() {
            return null;
        }

        /**
         * Returns the time this file object was last modified.
         */
        @Override
        public long getLastModified() {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Deletes the source file if it exists.
         */
        @Override
        public boolean delete() {
            try {
                return Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Opens the file an an input stream.
         * Implemented as a matter of principle, but should not be invoked.
         */
        @Override
        public InputStream openInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        /**
         * Opens the file an an output stream.
         * Implemented as a matter of principle, but should not be invoked.
         */
        @Override
        public OutputStream openOutputStream() throws IOException {
            return Files.newOutputStream(path);
        }

        /**
         * Opens the file a character reader.
         * Implemented as a matter of principle, but should not be invoked.
         */
        @Override
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            return Files.newBufferedReader(path, encoding);
        }

        /**
         * Returns the file content.
         * Implemented as a matter of principle, but should not be invoked.
         */
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return Files.readString(path, encoding);
        }

        /**
         * Opens the file a character writer.
         * Implemented as a matter of principle, but should not be invoked.
         */
        @Override
        public Writer openWriter() throws IOException {
            return Files.newBufferedWriter(path, encoding);
        }
    }

    /**
     * Converts the {@code File} instances to {@code Path} instances and delegate.
     * This is defined as a matter of principle but is not used by the Maven compiler plugin.
     *
     * @see #setLocationFromPaths(Location, Collection)
     */
    @Override
    public void setLocation(Location location, Iterable<? extends File> files) {
        List<Path> paths = null;
        if (files != null) {
            paths = StreamSupport.stream(files.spliterator(), false)
                    .map(File::toPath)
                    .toList();
        }
        setLocationFromPaths(location, paths);
    }

    /**
     * Converts the {@code Path} instances to {@code file} instances for the given location.
     * This is defined as a matter of principle but is not used by the Maven compiler plugin.
     *
     * @see #setLocationFromPaths(Location, Collection)
     */
    @Override
    public Iterable<? extends File> getLocation(Location location) {
        var paths = getLocationAsPaths(location);
        if (paths != null) {
            return paths.stream().map(Path::toFile).toList();
        }
        return null;
    }

    /**
     * Associates the given search paths with the given location.
     * The location may identify the class-path, module-path, doclet-path, etc.
     * Any previous value will be discarded.
     */
    @Override
    public void setLocationFromPaths(Location location, Collection<? extends Path> paths) {
        PathType type = JavaPathType.valueOf(location).orElse(null);
        if (type == null) {
            if (location == StandardLocation.SOURCE_OUTPUT) {
                type = OtherPathType.GENERATED_SOURCES;
            } else if (location == StandardLocation.SOURCE_PATH) {
                type = OtherPathType.SOURCES;
            } else if (location == StandardLocation.CLASS_OUTPUT) {
                type = OtherPathType.OUTPUT;
            } else {
                throw new IllegalArgumentException("Unsupported location: " + location);
            }
        }
        if (isAbsent(paths)) {
            locations.remove(type);
        } else {
            locations.put(type, paths);
        }
    }

    /**
     * Associates the given search paths for a module with the given location.
     * Any previous value will be discarded.
     */
    @Override
    public void setLocationForModule(Location location, String moduleName, Collection<? extends Path> paths)
            throws IOException {
        PathType type;
        if (location == StandardLocation.PATCH_MODULE_PATH) {
            type = JavaPathType.patchModule(moduleName);
        } else if (location == StandardLocation.MODULE_SOURCE_PATH) {
            type = OtherPathType.moduleSources(moduleName);
        } else {
            throw new IllegalArgumentException("Unsupported location: " + location);
        }
        if (isAbsent(paths)) {
            locations.remove(type);
        } else {
            locations.put(type, paths);
        }
    }

    /**
     * Returns whether the given collection is null or empty.
     */
    private static boolean isAbsent(Collection<?> c) {
        return (c == null) || c.isEmpty();
    }

    /**
     * Returns the search path associated with the given location, or {@code null} if none.
     */
    @Override
    public Collection<? extends Path> getLocationAsPaths(Location location) {
        return locations.get(JavaPathType.valueOf(location).orElse(null));
    }

    /**
     * Returns whether a location is known to this file manager.
     * This is defined as a matter of principle but is not used by the Maven compiler plugin.
     */
    @Override
    public boolean hasLocation(Location location) {
        return getLocationAsPaths(location) != null;
    }

    /**
     * Adds class-path, module-path and other paths to the given command.
     *
     * @param command the list where to add the options
     */
    void addAllLocations(List<String> command) {
        for (Map.Entry<PathType, Collection<? extends Path>> entry : locations.entrySet()) {
            command.addAll(Arrays.asList(entry.getKey().option(entry.getValue())));
        }
    }

    /**
     * Not yet implemented (not needed for forked tools).
     */
    @Override
    public Iterable<JavaFileObject> list(
            Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Not yet implemented (not needed for forked tools).
     */
    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Not yet implemented (not needed for forked tools).
     */
    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind)
            throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Not yet implemented (not needed for forked tools).
     */
    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Not yet implemented (not needed for forked tools).
     */
    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Not yet implemented (not needed for forked tools).
     */
    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling)
            throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Returns a class loader for loading plug-ins, or {@code null} if disabled.
     */
    @Override
    public ClassLoader getClassLoader(Location location) {
        return null;
    }

    /**
     * Flushes any resources opened for output by this file manager.
     */
    @Override
    public void flush() {}

    /**
     * Releases any resources opened by this file manager.
     */
    @Override
    public void close() {
        locations.clear();
    }
}
