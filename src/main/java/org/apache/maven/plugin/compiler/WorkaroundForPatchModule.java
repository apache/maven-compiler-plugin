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

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
final class WorkaroundForPatchModule extends ForwardingJavaFileManager<StandardJavaFileManager>
        implements StandardJavaFileManager {
    /**
     * Set this flag to {@code false} for testing if this workaround is still necessary.
     */
    static final boolean ENABLED = true;

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
    WorkaroundForPatchModule(final StandardJavaFileManager fileManager) {
        super(fileManager);
        definedLocations = new HashSet<>();
        patchesAsOption = new HashMap<>();
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
     * Invoked when a module path cannot be specified through the API
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
            JavaFileManager.Location location, String moduleName, Collection<? extends Path> paths) throws IOException {

        if (paths.isEmpty()) {
            return;
        }
        final boolean isPatch = (location == StandardLocation.PATCH_MODULE_PATH);
        if (isPatch && patchesAsOption.replace(moduleName, paths) != null) {
            /*
             * The patch was already specified by formatting the `--patch-module` option.
             * We cannot do that again, because that option can appear only once per module.
             */
            needsNewFileManager = true;
            return;
        }
        try {
            fileManager.setLocationForModule(location, moduleName, paths);
        } catch (UnsupportedOperationException e) {
            if (isPatch) {
                specifyAsOption(fileManager, JavaPathType.patchModule(moduleName), paths, e);
                patchesAsOption.put(moduleName, paths);
                return;
            }
            throw e;
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
