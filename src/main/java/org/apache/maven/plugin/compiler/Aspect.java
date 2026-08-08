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

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Elements to take in consideration when deciding whether to recompile a file.
 * The actual change detection, state persistence, and stale output cleanup are handled by the
 * {@link org.apache.maven.api.build.incremental.IncrementalContext IncrementalContext} API. This enumeration
 * configures which kinds of changes should be tracked and how they affect the rebuild decision
 * (partial vs. full).
 *
 * @see AbstractCompilerMojo#incrementalCompilation
 */
enum Aspect {
    /**
     * Recompile all source files if the compiler options changed.
     *
     * <p><b>BuildContext note:</b> Options change detection is handled automatically by
     * the {@code MojoConfigurationDigester}, which digests all {@code @Parameter} fields.
     * When any parameter value changes between builds, the build context escalates to a
     * full rebuild. This aspect is still accepted for backwards compatibility but the
     * detection itself is delegated to BuildContext.</p>
     */
    OPTIONS(Set.of()),

    /**
     * Recompile all source files if at least one dependency (JAR file) changed since the last build.
     * Dependency JARs are registered as BuildContext inputs; their content changes are tracked
     * across builds.
     *
     * <h4>Implementation note</h4>
     * The checks use BuildContext state files stored in the {@code target/incremental} directory.
     */
    DEPENDENCIES(Set.of()),

    /**
     * Recompile source files modified since the last build.
     * Source files are registered as BuildContext inputs. The build context tracks their state
     * (timestamp, size) across builds and reports which files are NEW, MODIFIED, or UNMODIFIED.
     * When a source file is removed, the build context automatically deletes its associated
     * output files (including inner classes) during finalization.
     *
     * <p>It is usually not needed to specify both {@code SOURCES} and {@link #CLASSES}.
     * But doing so is not forbidden.</p>
     *
     * <h4>Implementation note</h4>
     * The checks use BuildContext state files stored in the {@code target/incremental} directory.
     */
    SOURCES(Set.of()),

    /**
     * Recompile source files ({@code *.java}) associated to no output file ({@code *.class})
     * or associated to an output file older than the source. This algorithm does not check
     * if a source file has been removed, potentially leaving non-recompiled classes with
     * references to classes that no longer exist.
     *
     * <p>It is usually not needed to specify both {@link #SOURCES} and {@code CLASSES}.
     * But doing so is not forbidden.</p>
     *
     * <h4>Implementation note</h4>
     * With BuildContext, this aspect is handled the same way as {@link #SOURCES} — input
     * state tracking across builds subsumes the timestamp-based comparison.
     */
    CLASSES(Set.of()),

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
     *   <li>{@link #SOURCES}, {@link #CLASSES}, {@link #REBUILD_ON_ADD} and  {@link #REBUILD_ON_CHANGE}
     *       aspects are not used.</li>
     *   <li>There is no include/exclude filter.</li>
     * </ul>
     */
    MODULES(Set.of(SOURCES, CLASSES)),

    /**
     * Modifier for recompiling all source files when the addition of a new file is detected.
     * This flag is effective only when used together with {@link #SOURCES} or {@link #CLASSES}.
     * When used with {@link #CLASSES}, it provides a way to detect class renaming
     * (this is not needed with {@link #SOURCES} for detecting renaming).
     */
    REBUILD_ON_ADD(Set.of(MODULES)),

    /**
     * Modifier for recompiling all source files when a change is detected in at least one source file.
     * This flag is effective only when used together with {@link #SOURCES} or {@link #CLASSES}.
     * It does not rebuild when a new source file is added without change in other files,
     * unless {@link #REBUILD_ON_ADD} is also specified.
     */
    REBUILD_ON_CHANGE(REBUILD_ON_ADD.excludes),

    /**
     * The compiler plugin unconditionally specifies all sources to the Java compiler.
     * This aspect is mutually exclusive with all other aspects.
     */
    NONE(Set.of(OPTIONS, DEPENDENCIES, SOURCES, CLASSES, REBUILD_ON_ADD, REBUILD_ON_CHANGE, MODULES));

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
     * @return the aspects which, when modified, should cause a partial or full rebuild
     * @throws CompilationFailureException if a value is not recognized, or if mutually exclusive values are specified
     */
    static EnumSet<Aspect> parse(final String values) {
        var aspects = EnumSet.noneOf(Aspect.class);
        for (String value : values.split(",")) {
            value = value.trim();
            try {
                aspects.add(valueOf(value.toUpperCase(Locale.US).replace('-', '_')));
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
                    throw new CompilationFailureException("Illegal incremental build setting: \"" + aspect + "\" and \""
                            + exclude + "\" are mutually exclusive.");
                }
            }
        }
        if (aspects.isEmpty()) {
            throw new CompilationFailureException("Incremental build setting cannot be empty.");
        }
        return aspects;
    }
}
