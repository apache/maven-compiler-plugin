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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source files for a specific Java release. Instances of {@code SourcesForRelease} are created from
 * a list of {@link SourceFile} after the sources have been filtered according include and exclude filters.
 *
 * @author Martin Desruisseaux
 */
final class SourcesForRelease implements Closeable {
    /**
     * The release for this set of sources. For this class, the
     * {@link SourceVersion#RELEASE_0} value means "no version".
     */
    final SourceVersion release;

    /**
     * All source files.
     */
    final List<Path> files;

    /**
     * The root directories for each module. Keys are module names.
     * The empty string stands for no module.
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
     * Creates an initially empty instance for the given Java release.
     *
     * @param release the release for this set of sources, or {@link SourceVersion#RELEASE_0} for no version.
     */
    private SourcesForRelease(SourceVersion release) {
        this.release = release;
        roots = new LinkedHashMap<>();
        files = new ArrayList<>(256);
        moduleInfos = new LinkedHashMap<>();
    }

    /**
     * Adds the given source file to this collection of source files.
     * The value of {@code source.directory.release} must be {@link #release}.
     *
     * @param source the source file to add.
     */
    private void add(SourceFile source) {
        var directory = source.directory;
        if (lastDirectoryAdded != directory) {
            lastDirectoryAdded = directory;
            String moduleName = directory.moduleName;
            if (moduleName == null) {
                moduleName = "";
            }
            roots.computeIfAbsent(moduleName, (key) -> new LinkedHashSet<>()).add(directory.root);
            directory.getModuleInfo().ifPresent((path) -> moduleInfos.put(directory, null));
        }
        files.add(source.file);
    }

    /**
     * Groups all sources files first by Java release versions, then by module names.
     * The elements in the returned collection are sorted in the order of {@link SourceVersion}
     * enumeration values. It should match the increasing order of Java releases.
     *
     * @param sources the sources to group.
     * @return the given sources grouped by Java release versions and module names.
     */
    public static Collection<SourcesForRelease> groupByReleaseAndModule(List<SourceFile> sources) {
        var result = new EnumMap<SourceVersion, SourcesForRelease>(SourceVersion.class);
        for (SourceFile source : sources) {
            SourceVersion release = source.directory.release;
            if (release == null) {
                release = SourceVersion.RELEASE_0; // No release sub-directory for the compiled classes.
            }
            result.computeIfAbsent(release, SourcesForRelease::new).add(source);
        }
        // TODO: add empty set for all modules present in a release but not in the next release.
        return result.values();
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
}
