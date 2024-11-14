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
package org.apache.maven.plugin.compiler.stubs;

import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A dummy implementation of the {@code JavaCompiler} interface for testing the Maven compiler plugin
 * with alternative compilers. This dummy compiler actually ignores all source files and always writes
 * exactly one output file, namely {@value #OUTPUT_FILE}.
 *
 * <h2>Instantiation</h2>
 * This stub is not instantiated directly. Instead, the fully-qualified class name must be declared
 * in the {@code META-INF/services/javax.tools.Tool} file. Then, an instance is requested by setting
 * the {@code <compilerId>} element to {@value #COMPILER_ID} in the {@code plugin-config.xml} file
 * of the test.
 *
 * @author Edwin Punzalan
 * @author Martin Desruisseaux
 */
public class CompilerStub implements JavaCompiler, StandardJavaFileManager {
    /**
     * The name returned by {@link #name()}. Used for identifying this stub.
     * This is the value to specify in the {@code <compilerId>} element of the POM test file.
     *
     * @see #name()
     */
    public static final String COMPILER_ID = "maven-compiler-stub";

    /**
     * Name of the dummy file created as output by this compiler stub.
     *
     * @see #inferBinaryName(JavaFileManager.Location, JavaFileObject)
     */
    public static final String OUTPUT_FILE = "compiled.class";

    /**
     * The output directory, or {@code null} if not yet set.
     *
     * @see #setLocation(JavaFileManager.Location, Iterable)
     */
    private File outputDir;

    /**
     * Options given to the compiler when executed.
     *
     * @see #getOptions()
     */
    private static final ThreadLocal<Iterable<String>> arguments = new ThreadLocal<>();

    /**
     * Invoked by reflection by {@link java.util.ServiceLoader}.
     */
    public CompilerStub() {}

    /**
     * {@return the compiler idenitifer of this stub}.
     */
    @Override
    public String name() {
        return COMPILER_ID;
    }

    /**
     * {@return an arbitrary Java release number}. This is not used by the tests.
     */
    @Override
    public Set<SourceVersion> getSourceVersions() {
        return Set.of(SourceVersion.RELEASE_17);
    }

    /**
     * {@return the number of arguments expected by the given option}.
     * This method is implemented by a hard-coded list of options that
     * are known to be used in some tests.
     */
    @Override
    public int isSupportedOption(String option) {
        if (option.startsWith("-my&")) {
            return 0;
        }
        switch (option) {
            case "-Xlint":
                return 0;
            default:
                return 1;
        }
    }

    /**
     * {@return the object where source and destination directories will be specified by the Maven compiler plugin}.
     */
    @Override
    public StandardJavaFileManager getStandardFileManager(
            DiagnosticListener<? super JavaFileObject> diagnosticListener, Locale locale, Charset charset) {
        return this;
    }

    /**
     * {@return whether the two given objects are for the same file}.
     * This method is not seriously implemented, as it is not needed for the tests.
     */
    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return a.equals(b);
    }

    /**
     * Source or target directory, or source file to compile.
     * For this test, we do not bother to identify the exact purpose of the wrapped file.
     */
    private static final class UnknownFile extends SimpleJavaFileObject {
        UnknownFile(final File file) {
            super(file.toURI(), JavaFileObject.Kind.OTHER);
        }

        UnknownFile(final String name) {
            this(new File(name));
        }
    }

    /**
     * {@return the given files or directories wrapped in a dummy implementation of {@code FileObject}}.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        var objects = new ArrayList<JavaFileObject>();
        files.forEach(UnknownFile::new);
        return objects;
    }

    /**
     * {@return the given files or directories wrapped in a dummy implementation of {@code FileObject}}.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return getJavaFileObjectsFromFiles(Arrays.asList(files));
    }

    /**
     * {@return the given files or directories wrapped in a dummy implementation of {@code FileObject}}.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        var objects = new ArrayList<JavaFileObject>();
        names.forEach(UnknownFile::new);
        return objects;
    }

    /**
     * {@return the given files or directories wrapped in a dummy implementation of {@code FileObject}}.
     */
    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return getJavaFileObjectsFromStrings(Arrays.asList(names));
    }

    /**
     * {@return whether the given location is known to this file manager}.
     */
    @Override
    public boolean hasLocation(Location location) {
        return location == StandardLocation.CLASS_OUTPUT;
    }

    /**
     * Sets a directory for the given type of location. This simple stubs accepts a single
     * directory for {@link StandardLocation#CLASS_OUTPUT} and ignores all other locations.
     */
    @Override
    public void setLocation(Location location, Iterable<? extends File> files) {
        if (location == StandardLocation.CLASS_OUTPUT) {
            outputDir = null;
            Iterator<? extends File> it = files.iterator();
            if (it.hasNext()) {
                outputDir = it.next();
                if (it.hasNext()) {
                    throw new IllegalArgumentException("This simple stub accepts a maximum of one output directory.");
                }
            }
        }
    }

    /**
     * {@return the directory for the given type of location}.
     */
    @Override
    public Iterable<? extends File> getLocation(Location location) {
        if (location == StandardLocation.CLASS_OUTPUT && outputDir != null) {
            return Set.of(outputDir);
        }
        return Set.of();
    }

    /**
     * Not used by the tests.
     */
    @Override
    public ClassLoader getClassLoader(Location location) {
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * Not used by the tests.
     */
    @Override
    public Iterable<JavaFileObject> list(
            Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) {
        return Set.of();
    }

    /**
     * {@returns the name of the single file created by this dummy compiler}.
     */
    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return OUTPUT_FILE;
    }

    /**
     * Not used by the tests.
     */
    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        return false;
    }

    /**
     * Not used by the tests.
     */
    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) {
        return null;
    }

    /**
     * Not used by the tests.
     */
    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
        return null;
    }

    /**
     * Not used by the tests.
     */
    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) {
        return null;
    }

    /**
     * Not used by the tests.
     */
    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) {
        return null;
    }

    /**
     * {@return a compilation task}.
     */
    @Override
    public CompilationTask getTask(
            Writer out,
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options,
            Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {

        arguments.set(options);
        return new CompilationTask() {
            @Override
            public void addModules(Iterable<String> moduleNames) {}

            @Override
            public void setProcessors(Iterable<? extends Processor> processors) {}

            @Override
            public void setLocale(Locale locale) {}

            /**
             * Executes the pseudo-compilation.
             *
             * @return true for success, false otherwise
             */
            @Override
            public Boolean call() {
                return run(null, null, null, (String[]) null) == 0;
            }
        };
    }

    /**
     * Executes the pseudo-compilation.
     *
     * @return 0 for success, nonzero otherwise
     */
    @Override
    public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
        try {
            outputDir.mkdirs();
            File outputFile = new File(outputDir, OUTPUT_FILE);
            outputFile.createNewFile();
        } catch (IOException e) {
            throw new UncheckedIOException("An exception occurred while creating output file.", e);
        }
        return 0;
    }

    /**
     * {@return the options given to the compiler when the compilation tasks was created}.
     */
    public static List<String> getOptions() {
        var options = new ArrayList<String>();
        Iterable<String> args = arguments.get();
        if (args != null) {
            args.forEach(options::add);
        }
        return options;
    }

    /**
     * Nothing to do.
     */
    @Override
    public void flush() {}

    /**
     * Nothing to do.
     */
    @Override
    public void close() {}
}
