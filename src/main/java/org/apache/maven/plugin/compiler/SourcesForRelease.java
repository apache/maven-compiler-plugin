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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.PathType;

/**
 * Source files for a specific Java release. Instances of {@code SourcesForRelease} are created from
 * a list of {@link SourceFile} after the sources have been filtered according include and exclude filters.
 *
 * @author Martin Desruisseaux
 */
final class SourcesForRelease implements Closeable {
    /**
     * The release for this set of sources, or {@code null} if the user did not specified a release.
     *
     * @see #getReleaseString()
     * @see SourceDirectory#release
     */
    final SourceVersion release;

    /**
     * All source files. This is the union of all {@link SourceFile#file} for this {@linkplain #release}.
     *
     * @see SourceFile#file
     */
    final List<Path> files;

    /**
     * All source directories that are part of this compilation unit, grouped by module names.
     * The keys in the map are the module names, with the empty string standing for no module.
     * Values are the union of all {@link SourceDirectory#root} for this {@linkplain #release}.
     *
     * @see SourceDirectory#root
     */
    final Map<String, Set<Path>> roots;

    /**
     * The directories that contains a {@code module-info.java} file. If the set of source files
     * is for a Java release different than the base release, or if it is for the test sources,
     * then a non-empty map means that some modules overwrite {@code module-info.class}.
     */
    private final Map<SourceDirectory, ModuleInfoOverwrite> moduleInfos;

    /**
     * Last directory added to the {@link #roots} map. This is a small optimization for reducing
     * the number of accesses to the map. In most cases, only one element will be written there.
     */
    private SourceDirectory lastDirectoryAdded;

    /**
     * Snapshot of {@link ToolExecutor#dependencies}.
     * This information is saved in case a {@code target/javac.args} debug file needs to be written.
     */
    Map<PathType, Collection<Path>> dependencySnapshot;

    /**
     * The output directory for the release. This is either the base output directory or a sub-directory
     * in {@code META-INF/versions/}. This field is not used by this class, but made available for making
     * easier to write the {@code target/javac.args} debug file.
     */
    Path outputForRelease;

    /**
     * Creates an initially empty instance for the given Java release.
     *
     * @param release the release for this set of sources, or {@code null} if the user did not specified a release
     */
    SourcesForRelease(SourceVersion release) {
        this.release = release;
        files = new ArrayList<>();
        roots = new LinkedHashMap<>();
        moduleInfos = new LinkedHashMap<>();
    }

    /**
     * Returns the release as a string suitable for the {@code --release} compiler option.
     *
     * @return the release number as a string, or {@code null} if none
     */
    String getReleaseString() {
        if (release == null) {
            return null;
        }
        var version = release.name();
        return version.substring(version.lastIndexOf('_') + 1);
    }

    /**
     * Adds the given source file to this collection of source files.
     * The value of {@code source.directory.release}, if not null, must be equal to {@link #release}.
     *
     * @param source the source file to add.
     */
    void add(SourceFile source) {
        var directory = source.directory;
        if (lastDirectoryAdded != directory) {
            lastDirectoryAdded = directory;
            String moduleName = directory.moduleName;
            if (moduleName == null || moduleName.isBlank()) {
                moduleName = "";
            }
            roots.get(moduleName).add(directory.root);
            directory.getModuleInfo().ifPresent((path) -> moduleInfos.put(directory, null));
        }
        files.add(source.file);
    }

    /**
     * If there is any {@code module-info.class} in the main classes that are overwritten by this set of sources,
     * temporarily replace the main files by the test files. The {@link #close()} method must be invoked after
     * this method for resetting the original state.
     *
     * <p>This method is invoked when the test files overwrite the {@code module-info.class} from the main files.
     * This method should not be invoked during the compilation of main classes, as its behavior may be not well
     * defined.</p>
     */
    void substituteModuleInfos(final Path mainOutputDirectory, final Path testOutputDirectory) throws IOException {
        for (Map.Entry<SourceDirectory, ModuleInfoOverwrite> entry : moduleInfos.entrySet()) {
            Path main = mainOutputDirectory;
            Path test = testOutputDirectory;
            SourceDirectory directory = entry.getKey();
            String moduleName = directory.moduleName;
            if (moduleName != null) {
                main = main.resolve(moduleName);
                if (!Files.isDirectory(main)) {
                    main = mainOutputDirectory;
                }
                test = test.resolve(moduleName);
                if (!Files.isDirectory(test)) {
                    test = testOutputDirectory;
                }
            }
            Path source = directory.getModuleInfo().orElseThrow(); // Should never be absent for entries in the map.
            entry.setValue(ModuleInfoOverwrite.create(source, main, test));
        }
    }

    /**
     * Restores the hidden {@code module-info.class} files to their original names.
     */
    @Override
    public void close() throws IOException {
        IOException error = null;
        for (Map.Entry<SourceDirectory, ModuleInfoOverwrite> entry : moduleInfos.entrySet()) {
            ModuleInfoOverwrite mo = entry.getValue();
            if (mo != null) {
                entry.setValue(null);
                try {
                    mo.restore();
                } catch (IOException e) {
                    if (error == null) {
                        error = e;
                    } else {
                        error.addSuppressed(e);
                    }
                }
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * {@return a string representation for debugging purposes}
     */
    @Override
    public String toString() {
        var sb = new StringBuilder(getClass().getSimpleName()).append('[');
        if (release != null) {
            sb.append(release).append(": ");
        }
        return sb.append(files.size()).append(" files]").toString();
    }
}
