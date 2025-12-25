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

import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.JavaPathType;

/**
 * Workaround for a {@code javax.tools} method which seems not yet supported on all compilers.
 * At least with OpenJDK 24, an {@link UnsupportedOperationException} may occur during the call to
 * {@code fileManager.setLocationForModule(StandardLocation.PATCH_MODULE_PATH, moduleName, paths)}.
 * The workaround is to format the paths in a {@code --patch-module} option instead.
 * The problem is that we can specify this option only once per file manager instance.
 *
 * <p>We may remove this workaround in a future version of the Maven Compiler Plugin
 * if the {@code UnsupportedOperationException} is fixed in a future Java release.
 * For checking if this workaround is still necessary, set {@link #ENABLED} to {@code false}
 * and run the JUnit tests.</p>
 *
 * @author Martin Desruisseaux
 */
final class WorkaroundForPatchModule implements JavaCompiler {
    /**
     * Set this flag to {@code false} for testing if this workaround is still necessary.
     */
    static final boolean ENABLED = true;

    /**
     * The actual compiler provided by {@link javax.tools}.
     */
    private final JavaCompiler compiler;

    /**
     * Creates a new workaround as a wrapper for the given compiler.
     *
     * @param compiler the actual compiler provided by {@link javax.tools}
     */
    WorkaroundForPatchModule(JavaCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Forwards the call to the wrapped compiler.
     *
     * @return the name of the compiler tool
     */
    @Override
    public String name() {
        return compiler.name();
    }

    /**
     * Forwards the call to the wrapped compiler.
     *
     * @return the source versions of the Java programming language supported by the compiler
     */
    @Override
    public Set<SourceVersion> getSourceVersions() {
        return compiler.getSourceVersions();
    }

    /**
     * Forwards the call to the wrapped compiler.
     *
     * @return whether the given option is supported and if so, the number of arguments the option takes
     */
    @Override
    public int isSupportedOption(String option) {
        return compiler.isSupportedOption(option);
    }

    /**
     * Forwards the call to the wrapped compiler and wraps the file manager in a workaround.
     *
     * @param diagnosticListener a listener for non-fatal diagnostics
     * @param locale the locale to apply when formatting diagnostics
     * @param charset the character set used for decoding bytes
     * @return a file manager with workaround
     */
    @Override
    public StandardJavaFileManager getStandardFileManager(
            DiagnosticListener<? super JavaFileObject> diagnosticListener, Locale locale, Charset charset) {
        return new FileManager(compiler.getStandardFileManager(diagnosticListener, locale, charset), locale, charset);
    }

    /**
     * Forwards the call to the wrapped compiler and wraps the task in a workaround.
     *
     * @param out destination of additional output from the compiler
     * @param fileManager a file manager created by {@code getStandardFileManager(…)}
     * @param diagnosticListener a listener for non-fatal diagnostics
     * @param options compiler options
     * @param classes names of classes to be processed by annotation processing
     * @param compilationUnits the compilation units to compile
     * @return an object representing the compilation
     */
    @Override
    public CompilationTask getTask(
            Writer out,
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options,
            Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {
        if (fileManager instanceof FileManager wp) {
            fileManager = wp.getFileManagerIfUsable();
            if (fileManager == null) {
                final StandardJavaFileManager workaround =
                        compiler.getStandardFileManager(diagnosticListener, wp.locale, wp.charset);
                try {
                    wp.copyTo(workaround);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                final CompilationTask task =
                        compiler.getTask(out, workaround, diagnosticListener, options, classes, compilationUnits);
                return new CompilationTask() {
                    @Override
                    public void setLocale(Locale locale) {
                        task.setLocale(locale);
                    }

                    @Override
                    public void setProcessors(Iterable<? extends Processor> processors) {
                        task.setProcessors(processors);
                    }

                    @Override
                    public void addModules(Iterable<String> moduleNames) {
                        task.addModules(moduleNames);
                    }

                    @Override
                    public Boolean call() {
                        final Boolean result = task.call();
                        try {
                            workaround.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return result;
                    }
                };
            }
        }
        return compiler.getTask(out, fileManager, diagnosticListener, options, classes, compilationUnits);
    }

    /**
     * Not used by the Maven Compiler Plugin.
     */
    @Override
    public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
        return compiler.run(in, out, err, arguments);
    }

    /**
     * A file manager which fallbacks on the {@code --patch-module}
     * option when it cannot use {@link StandardLocation#PATCH_MODULE_PATH}.
     * This is the class where the actual workaround is implemented.
     */
    private static final class FileManager extends ForwardingJavaFileManager<StandardJavaFileManager>
            implements StandardJavaFileManager {
        /**
         * The locale specified by the user when creating this file manager.
         * Saved for allowing the creation of other file managers.
         */
        final Locale locale;

        /**
         * The character set specified by the user when creating this file manager.
         * Saved for allowing the creation of other file managers.
         */
        final Charset charset;

        /**
         * All locations that have been successfully specified to the file manager through programmatic API.
         * This set excludes the {@code PATCH_MODULE_PATH} locations which were defined using the workaround
         * described in class Javadoc.
         */
        private final Set<JavaFileManager.Location> definedLocations;

        /**
         * The locations that we had to define by formatting a {@code --patch-module} option.
         * Keys are module names and values are the paths for the associated module.
         */
        private final Map<String, Collection<? extends Path>> patchesAsOption;

        /**
         * Whether the caller needs to create a new file manager.
         * It happens when we have been unable to set a {@code --patch-module} option on the current file manager.
         */
        private boolean needsNewFileManager;

        /**
         * Creates a new workaround for the given file manager.
         */
        FileManager(StandardJavaFileManager fileManager, Locale locale, Charset charset) {
            super(fileManager);
            this.locale = locale;
            this.charset = charset;
            definedLocations = new LinkedHashSet<>();
            patchesAsOption = new LinkedHashMap<>();
        }

        /**
         * {@return the original file manager, or {@code null} if the caller needs to create a new one}
         * The returned value is {@code null} when we have been unable to set a {@code --patch-module}
         * option on the current file manager. In such case, the caller should create a new file manager
         * and configure it with {@link #copyTo(StandardJavaFileManager)}.
         */
        StandardJavaFileManager getFileManagerIfUsable() {
            return needsNewFileManager ? null : fileManager;
        }

        /**
         * Copies the locations defined in this file manager to the given file manager.
         *
         * @param target where to copy the locations
         * @throws IOException if a location cannot be set on the target file manager
         */
        void copyTo(final StandardJavaFileManager target) throws IOException {
            for (JavaFileManager.Location location : definedLocations) {
                target.setLocation(location, fileManager.getLocation(location));
            }
            for (Map.Entry<String, Collection<? extends Path>> entry : patchesAsOption.entrySet()) {
                Collection<? extends Path> paths = entry.getValue();
                String moduleName = entry.getKey();
                try {
                    target.setLocationForModule(StandardLocation.PATCH_MODULE_PATH, moduleName, paths);
                } catch (UnsupportedOperationException e) {
                    specifyAsOption(target, JavaPathType.patchModule(moduleName), paths, e);
                }
            }
        }

        /**
         * Sets a module path by asking the file manager to parse an option formatted by this method.
         * Invoked when a module path cannot be specified through the standard <abbr>API</abbr>.
         * This is the workaround described in class Javadoc.
         *
         * @param fileManager the file manager on which an attempt to set the location has been made and failed
         * @param type the type of path together with the module name
         * @param paths the paths to set
         * @param cause the exception that occurred when invoking the standard API
         * @throws IllegalArgumentException if this workaround doesn't work neither
         */
        private static void specifyAsOption(
                StandardJavaFileManager fileManager,
                JavaPathType.Modular type,
                Collection<? extends Path> paths,
                UnsupportedOperationException cause)
                throws IOException {

            String message;
            Iterator<String> it = Arrays.asList(type.option(paths)).iterator();
            if (!fileManager.handleOption(it.next(), it)) {
                message = "Failed to set the %s option for module %s";
            } else if (it.hasNext()) {
                message = "Unexpected number of arguments after the %s option for module %s";
            } else {
                return;
            }
            JavaPathType rawType = type.rawType();
            throw new IllegalArgumentException(
                    String.format(message, rawType.option().orElse(rawType.name()), type.moduleName()), cause);
        }

        /**
         * Adds the given module path to the file manager.
         * If we cannot do that using the programmatic API, formats as a command-line option.
         */
        @Override
        public void setLocationForModule(
                JavaFileManager.Location location, String moduleName, Collection<? extends Path> paths)
                throws IOException {
            if (location == StandardLocation.PATCH_MODULE_PATH) {
                if (patchesAsOption.replace(moduleName, paths) != null) {
                    /*
                     * The patch was already specified by formatting the `--patch-module` option.
                     * We cannot do that again, because that option can appear only once per module.
                     * We nevertheless stored the new paths in `patchesAsOption` for use by `copyTo(…)`.
                     */
                    needsNewFileManager = true;
                    return;
                }
                try {
                    fileManager.setLocationForModule(location, moduleName, paths);
                } catch (UnsupportedOperationException e) {
                    specifyAsOption(fileManager, JavaPathType.patchModule(moduleName), paths, e);
                    patchesAsOption.put(moduleName, paths);
                    return;
                }
            } else {
                fileManager.setLocationForModule(location, moduleName, paths);
            }
            definedLocations.add(fileManager.getLocationForModule(location, moduleName));
        }

        /**
         * Adds the given path to the file manager.
         */
        @Override
        public void setLocationFromPaths(JavaFileManager.Location location, Collection<? extends Path> paths)
                throws IOException {
            fileManager.setLocationFromPaths(location, paths);
            definedLocations.add(location);
        }

        @Override
        public void setLocation(Location location, Iterable<? extends File> files) throws IOException {
            fileManager.setLocation(location, files);
            definedLocations.add(location);
        }

        @Override
        public Iterable<? extends File> getLocation(Location location) {
            return fileManager.getLocation(location);
        }

        @Override
        public Iterable<? extends Path> getLocationAsPaths(Location location) {
            return fileManager.getLocationAsPaths(location);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
            return fileManager.getJavaFileObjects(names);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
            return fileManager.getJavaFileObjects(files);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(Path... paths) {
            return fileManager.getJavaFileObjects(paths);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
            return fileManager.getJavaFileObjectsFromStrings(names);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
            return fileManager.getJavaFileObjectsFromFiles(files);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromPaths(Collection<? extends Path> paths) {
            return fileManager.getJavaFileObjectsFromPaths(paths);
        }

        @Override
        public Path asPath(FileObject file) {
            return fileManager.asPath(file);
        }
    }
}
