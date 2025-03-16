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

import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.JavaPathType;

import static org.apache.maven.plugin.compiler.AbstractCompilerMojo.SUPPORT_LEGACY;

/**
 * A task which configures and executes the Java compiler for the test classes.
 * This executor contains additional configurations compared to the base class.
 *
 * @author Martin Desruisseaux
 */
class ToolExecutorForTest extends ToolExecutor {
    /**
     * The output directory of the main classes.
     * This directory will be added to the class-path or module-path.
     *
     * @see TestCompilerMojo#mainOutputDirectory
     */
    protected final Path mainOutputDirectory;

    /**
     * Path to the {@code module-info.class} file of the main code, or {@code null} if that file does not exist.
     */
    private final Path mainModulePath;

    /**
     * Whether to place the main classes on the module path when {@code module-info} is present.
     * The default and recommended value is {@code true}. The user may force to {@code false},
     * in which case the main classes are placed on the class path, but this is deprecated.
     * This flag may be removed in a future version if we remove support of this practice.
     *
     * @see TestCompilerMojo#useModulePath
     */
    private final boolean useModulePath;

    /**
     * Whether a {@code module-info.java} file is defined in the test sources.
     * In such case, it has precedence over the {@code module-info.java} in main sources.
     * This is defined for compatibility with Maven 3, but not recommended.
     */
    private final boolean hasTestModuleInfo;

    /**
     * Whether the {@code module-info} of the tests overwrites the main {@code module-info}.
     * This is a deprecated practice, but is accepted if {@link #SUPPORT_LEGACY} is true.
     */
    private boolean overwriteMainModuleInfo;

    /**
     * Name of the main module to compile, or {@code null} if not yet determined.
     * If the project is not modular, hen this field contains an empty string.
     *
     * TODO: use "*" as a sentinel value for modular source hierarchy.
     *
     * @see #getMainModuleName()
     */
    private String moduleName;

    /**
     * Whether {@link #addModuleOptions(Options)} has already been invoked.
     * The options shall be completed only once, otherwise conflicts may occur.
     */
    private boolean addedModuleOptions;

    /**
     * Creates a new task by taking a snapshot of the current configuration of the given <abbr>MOJO</abbr>.
     * This constructor creates the {@linkplain #outputDirectory output directory} if it does not already exist.
     *
     * @param mojo the <abbr>MOJO</abbr> from which to take a snapshot
     * @param listener where to send compilation warnings, or {@code null} for the Maven logger
     * @throws MojoException if this constructor identifies an invalid parameter in the <abbr>MOJO</abbr>
     * @throws IOException if an error occurred while creating the output directory or scanning the source directories
     */
    @SuppressWarnings("deprecation")
    ToolExecutorForTest(TestCompilerMojo mojo, DiagnosticListener<? super JavaFileObject> listener) throws IOException {
        super(mojo, listener);
        mainOutputDirectory = mojo.mainOutputDirectory;
        mainModulePath = mojo.mainModulePath;
        useModulePath = mojo.useModulePath;
        hasTestModuleInfo = mojo.hasTestModuleInfo;
        /*
         * If we are compiling the test classes of a modular project, add the `--patch-modules` options.
         * In this case, the option values are directory of source files, not to be confused with cases
         * where a module is patched with compiled classes.
         *
         * Note that those options are handled like dependencies,
         * because they will need to be set using the `javax.tools.StandardLocation` API.
         */
        for (SourceDirectory dir : sourceDirectories) {
            String moduleToPatch = dir.moduleName;
            if (moduleToPatch == null) {
                moduleToPatch = getMainModuleName();
                if (moduleToPatch.isEmpty()) {
                    continue; // No module-info found.
                }
                if (SUPPORT_LEGACY) {
                    String testModuleName = mojo.getTestModuleName(sourceDirectories);
                    if (testModuleName != null) {
                        overwriteMainModuleInfo = testModuleName.equals(getMainModuleName());
                        if (!overwriteMainModuleInfo) {
                            continue; // The test classes are in their own module.
                        }
                    }
                }
            }
            dependencies
                    .computeIfAbsent(JavaPathType.patchModule(moduleToPatch), (key) -> new ArrayList<>())
                    .add(dir.root);
        }
    }

    /**
     * {@return the module name of the main code, or an empty string if none}.
     * This method reads the module descriptor when first needed and caches the result.
     *
     * @throws IOException if the module descriptor cannot be read.
     */
    private String getMainModuleName() throws IOException {
        if (moduleName == null) {
            if (mainModulePath != null) {
                try (InputStream in = Files.newInputStream(mainModulePath)) {
                    moduleName = ModuleDescriptor.read(in).name();
                }
            } else {
                moduleName = "";
            }
        }
        return moduleName;
    }

    /**
     * Generates the {@code --add-modules} and {@code --add-reads} options for the dependencies that are not
     * in the main compilation. This method is invoked only if {@code hasModuleDeclaration} is {@code true}.
     *
     * @param dependencyResolution the project dependencies
     * @param configuration where to add the options
     * @throws IOException if the module information of a dependency cannot be read
     */
    @SuppressWarnings({"checkstyle:MissingSwitchDefault", "fallthrough"})
    private void addModuleOptions(final Options configuration) throws IOException {
        if (addedModuleOptions) {
            return;
        }
        addedModuleOptions = true;
        if (!hasModuleDeclaration || dependencyResolution == null) {
            return;
        }
        if (SUPPORT_LEGACY && useModulePath && hasTestModuleInfo) {
            /*
             * Do not add any `--add-reads` parameters. The developers should put
             * everything needed in the `module-info`, including test dependencies.
             */
            return;
        }
        final var done = new HashSet<String>(); // Added modules and their dependencies.
        final var addModules = new StringJoiner(",");
        StringJoiner addReads = null;
        boolean hasUnnamed = false;
        for (Map.Entry<Dependency, Path> entry :
                dependencyResolution.getDependencies().entrySet()) {
            boolean compile = false;
            switch (entry.getKey().getScope()) {
                case TEST:
                case TEST_ONLY:
                    compile = true;
                    // Fall through
                case TEST_RUNTIME:
                    if (compile) {
                        // Needs to be initialized even if `name` is null.
                        if (addReads == null) {
                            addReads = new StringJoiner(",", getMainModuleName() + "=", "");
                        }
                    }
                    Path path = entry.getValue();
                    String name = dependencyResolution.getModuleName(path).orElse(null);
                    if (name == null) {
                        hasUnnamed = true;
                    } else if (done.add(name)) {
                        addModules.add(name);
                        if (compile) {
                            addReads.add(name);
                        }
                        /*
                         * For making the options simpler, we do not add `--add-modules` or `--add-reads`
                         * options for modules that are required by a module that we already added. This
                         * simplification is not necessary, but makes the command-line easier to read.
                         */
                        dependencyResolution.getModuleDescriptor(path).ifPresent((descriptor) -> {
                            for (ModuleDescriptor.Requires r : descriptor.requires()) {
                                done.add(r.name());
                            }
                        });
                    }
                    break;
            }
        }
        if (!done.isEmpty()) {
            configuration.addIfNonBlank("--add-modules", addModules.toString());
        }
        if (addReads != null) {
            if (hasUnnamed) {
                addReads.add("ALL-UNNAMED");
            }
            configuration.addIfNonBlank("--add-reads", addReads.toString());
        }
    }

    /**
     * @hidden
     */
    @Override
    public boolean applyIncrementalBuild(AbstractCompilerMojo mojo, Options configuration) throws IOException {
        addModuleOptions(configuration); // Effective only once.
        return super.applyIncrementalBuild(mojo, configuration);
    }

    /**
     * @hidden
     */
    @Override
    public boolean compile(JavaCompiler compiler, Options configuration, Writer otherOutput) throws IOException {
        addModuleOptions(configuration); // Effective only once.
        return super.compile(compiler, configuration, otherOutput);
    }

    /**
     * Separates the compilation of {@code module-info} from other classes. This is needed when the
     * {@code module-info} of the test classes overwrite the {@code module-info} of the main classes.
     * In the latter case, we need to compile the test {@code module-info} first in order to substitute
     * the main module-info by the test one before to compile the remaining test classes.
     */
    @Override
    final CompilationTaskSources[] toCompilationTasks(final SourcesForRelease unit) {
        if (!(SUPPORT_LEGACY && useModulePath && hasTestModuleInfo && overwriteMainModuleInfo)) {
            return super.toCompilationTasks(unit);
        }
        CompilationTaskSources moduleInfo = null;
        final List<Path> files = unit.files;
        for (int i = files.size(); --i >= 0; ) {
            if (SourceDirectory.isModuleInfoSource(files.get(i))) {
                moduleInfo = new CompilationTaskSources(List.of(files.remove(i)));
                if (files.isEmpty()) {
                    return new CompilationTaskSources[] {moduleInfo};
                }
                break;
            }
        }
        var task = new CompilationTaskSources(files) {
            /**
             * Substitutes the main {@code module-info.class} by the test's one, compiles test classes,
             * then restores the original {@code module-info.class}. The test {@code module-info.class}
             * must have been compiled separately before this method is invoked.
             */
            @Override
            boolean compile(JavaCompiler.CompilationTask task) throws IOException {
                try (unit) {
                    unit.substituteModuleInfos(mainOutputDirectory, outputDirectory);
                    return super.compile(task);
                }
            }
        };
        if (moduleInfo != null) {
            return new CompilationTaskSources[] {moduleInfo, task};
        } else {
            return new CompilationTaskSources[] {task};
        }
    }
}
