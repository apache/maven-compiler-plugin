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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.services.DependencyResolverResult;

/**
 * Reader of {@value #FILENAME} files.
 * The main options managed by this class are the options that are not defined by Maven dependencies.
 * They are the options for opening or exporting packages to other modules, or reading more modules.
 * The values of these options are module names or package names.
 * This class does not manage the options for which the value is a path.
 *
 * <h2>Global options</h2>
 * The {@code --add-modules} and {@code --limit-modules} options are global, not options defined on a per-module basis.
 * The global aspect is handled by using shared maps for the {@link #addModules} and {@link #limitModules} fields.
 * The value of {@code --add-modules} is usually controlled by the dependencies declared in the {@code pom.xml} file
 * and rarely needs to be modified.
 *
 * @author Martin Desruisseaux
 */
final class ModuleInfoPatch {
    /**
     * Name of {@value} files that are parsed by this class.
     */
    public static final String FILENAME = "module-info-patch.maven";

    /**
     * Maven-specific keyword for meaning to export a package to all the test module path.
     * Other keywords such as {@code "ALL-MODULE-PATH"} are understood by the Java compiler.
     */
    private static final String TEST_MODULE_PATH = "TEST-MODULE-PATH";

    /**
     * Maven-specific keyword for meaning to export a package to all other modules in the current Maven (sub)project.
     * This is useful when a module contains a package of test fixtures also used for the tests in all other modules.
     */
    private static final String SUBPROJECT_MODULES = "SUBPROJECT-MODULES";

    /**
     * Special cases for the {@code --add-modules} option.
     * The {@value #TEST_MODULE_PATH} keyword is specific to Maven.
     * Other keywords in this set are recognized by the Java compiler.
     */
    private static final Set<String> ADD_MODULES_SPECIAL_CASES = Set.of("ALL-MODULE-PATH", TEST_MODULE_PATH);

    /**
     * Special cases for the {@code --add-exports} option.
     * The {@value #TEST_MODULE_PATH} and {@value #SUBPROJECT_MODULES} keywords are specific to Maven.
     * Other keywords in this set are recognized by the Java compiler.
     */
    private static final Set<String> ADD_EXPORTS_SPECIAL_CASES =
            Set.of("ALL-UNNAMED", TEST_MODULE_PATH, SUBPROJECT_MODULES);

    /**
     * The name of the module to patch, or {@code null} if unspecified.
     *
     * @see #getModuleName()
     */
    private String moduleName;

    /**
     * Values parsed from the {@value #FILENAME} file for {@code --add-modules} option.
     * A unique set is shared by {@code ModuleInfoPatch} instances of a project, because there
     * is only one {@code --add-module} option applying to all modules. The values will be the
     * union of the values provided by all {@value #FILENAME} files.
     */
    private final Set<String> addModules;

    /**
     * Values parsed from the {@value #FILENAME} file for {@code --limit-modules} option.
     * A unique set is shared by all {@code ModuleInfoPatch} instances of a project in the
     * same way as {@link #addModules}.
     */
    private final Set<String> limitModules;

    /**
     * Values parsed from the {@value #FILENAME} file for {@code --add-reads} option.
     * Option values will be prefixed by {@link #moduleName}.
     */
    private final Set<String> addReads;

    /**
     * Values parsed from the {@value #FILENAME} file for {@code --add-exports} option.
     * Option values will be prefixed by {@link #moduleName}.
     * Keys are package names.
     */
    private final Map<String, Set<String>> addExports;

    /**
     * Values parsed from the {@value #FILENAME} file for {@code --add-opens} option.
     * Option values will be prefixed by {@link #moduleName}.
     * Keys are package names.
     */
    private final Map<String, Set<String>> addOpens;

    /**
     * A clone of this {@code ModuleInfoPatch} but with runtime dependencies instead of compile-time dependencies.
     * The information saved in this object are not used by the compiler plugin, because the runtime dependencies
     * may differ from the runtime dependencies. But we need to save them for the needs of other plugins such as
     * Surefire. If the compile and runtime dependencies are the same, then the value is {@code this}.
     */
    private ModuleInfoPatch runtimeDependencies;

    /**
     * Creates an initially empty module patch.
     *
     * @param defaultModule  the name of the default module if there is no {@value #FILENAME}
     * @param previous       the previous instance (for sharing global options), or {@code null} if none.
     */
    ModuleInfoPatch(String defaultModule, ModuleInfoPatch previous) {
        if (defaultModule != null && !defaultModule.isBlank()) {
            moduleName = defaultModule;
        }
        if (previous != null) {
            addModules = previous.addModules;
            limitModules = previous.limitModules;
        } else {
            addModules = new LinkedHashSet<>();
            limitModules = new LinkedHashSet<>();
        }
        addReads = new LinkedHashSet<>();
        addExports = new LinkedHashMap<>();
        addOpens = new LinkedHashMap<>();
        runtimeDependencies = this;
    }

    /**
     * Creates a deep clone of the given module info patch.
     * This is used for initializing the {@link #runtimeDependencies} field.
     *
     * @param parent the module info patch to clone
     */
    private ModuleInfoPatch(ModuleInfoPatch parent) {
        moduleName = parent.moduleName;
        addModules = new LinkedHashSet<>(parent.addModules);
        limitModules = new LinkedHashSet<>(parent.limitModules);
        addReads = new LinkedHashSet<>(parent.addReads);
        addExports = new LinkedHashMap<>(parent.addExports);
        addOpens = new LinkedHashMap<>(parent.addOpens);
        // Leave `runtimeDependencies` to null as it would be an error to use it a second time.
    }

    /**
     * Creates a module patch with the specified {@code --add-reads} options and everything else empty.
     *
     * @param addReads the {@code --add-reads} option
     * @param moduleName the name of the module to patch
     *
     * @see #patchWithSameReads(String)
     */
    private ModuleInfoPatch(Set<String> addReads, String moduleName) {
        this.moduleName = moduleName;
        this.addReads = addReads;
        /*
         * Really need `Collections.emptyFoo()` here, not `Set.of()` or `Map.of()`.
         * A difference is that the former silently accept calls to `clear()` as
         * no-operation, while the latter throw `UnsupportedOperationException`.
         */
        addModules = Collections.emptySet();
        limitModules = Collections.emptySet();
        addExports = Collections.emptyMap();
        addOpens = Collections.emptyMap();
        // `runtimeDependencies` to be initialized by the caller.
    }

    /**
     * Sets this instance to the default configuration to use when no {@value #FILENAME} is present.
     */
    public void setToDefaults() {
        addModules.add(TEST_MODULE_PATH);
        addReads.add(TEST_MODULE_PATH);
    }

    /**
     * Loads the content of the given stream of characters.
     * This method does not close the given reader.
     *
     * @param source stream of characters to read
     * @throws IOException if an I/O error occurred while loading the file
     */
    public void load(Reader source) throws IOException {
        var reader = new StreamTokenizer(source);
        reader.slashSlashComments(true);
        reader.slashStarComments(true);
        expectToken(reader, "patch-module");
        moduleName = nextName(reader, true);
        expectToken(reader, '{');
        while (reader.nextToken() == StreamTokenizer.TT_WORD) {
            switch (reader.sval) {
                case "add-modules":
                    readModuleList(reader, addModules, ADD_MODULES_SPECIAL_CASES);
                    break;
                case "limit-modules":
                    readModuleList(reader, limitModules, Set.of());
                    break;
                case "add-reads":
                    readModuleList(reader, addReads, Set.of(TEST_MODULE_PATH));
                    break;
                case "add-exports":
                    readQualified(reader, addExports, ADD_EXPORTS_SPECIAL_CASES);
                    break;
                case "add-opens":
                    readQualified(reader, addOpens, Set.of());
                    break;
                default:
                    throw new ModuleInfoPatchException("Unknown keyword \"" + reader.sval + '"', reader);
            }
        }
        if (reader.ttype != '}') {
            throw new ModuleInfoPatchException("Not a token", reader);
        }
        if (reader.nextToken() != StreamTokenizer.TT_EOF) {
            throw new ModuleInfoPatchException("Expected end of file but found \"" + reader.sval + '"', reader);
        }
    }

    /**
     * Skips a token which is expected to be equal to the given value.
     *
     * @param reader the reader from which to skip a token
     * @param expected the expected token value
     * @throws IOException if an I/O error occurred while loading the file
     * @throws ModuleInfoPatchException if the next token does not have the expected value
     */
    private static void expectToken(StreamTokenizer reader, String expected) throws IOException {
        if (reader.nextToken() != StreamTokenizer.TT_WORD || !expected.equals(reader.sval)) {
            throw new ModuleInfoPatchException("Expected \"" + expected + '"', reader);
        }
    }

    /**
     * Skips a token which is expected to be equal to the given value.
     * The expected character must be flagged as an ordinary character in the reader.
     *
     * @param reader the reader from which to skip a token
     * @param expected the expected character value
     * @throws IOException if an I/O error occurred while loading the file
     * @throws ModuleInfoPatchException if the next token does not have the expected value
     */
    private static void expectToken(StreamTokenizer reader, char expected) throws IOException {
        if (reader.nextToken() != expected) {
            throw new ModuleInfoPatchException("Expected \"" + expected + '"', reader);
        }
    }

    /**
     * Returns the next package or module name.
     * This method verifies that the name is non-empty and a valid Java identifier.
     *
     * @param reader the reader from which to get the package or module name
     * @param module {@code true} is expecting a module name, {@code false} if expecting a package name
     * @return the package or module name
     * @throws IOException if an I/O error occurred while loading the file
     * @throws ModuleInfoPatchException if the next token is not a package or module name
     */
    private static String nextName(StreamTokenizer reader, boolean module) throws IOException {
        if (reader.nextToken() != StreamTokenizer.TT_WORD) {
            throw new ModuleInfoPatchException("Expected a " + (module ? "module" : "package") + " name", reader);
        }
        return ensureValidName(reader, reader.sval.strip(), module);
    }

    /**
     * Verifies that the given name is a valid package or module identifier.
     *
     * @param reader the reader from which to get the line number if an exception needs to be thrown
     * @param name the name to verify
     * @param module {@code true} is expecting a module name, {@code false} if expecting a package name
     * @throws ModuleInfoPatchException if the next token is not a package or module name
     * @return the given name
     */
    private static String ensureValidName(StreamTokenizer reader, String name, boolean module) {
        int length = name.length();
        boolean expectFirstChar = true;
        int c;
        for (int i = 0; i < length; i += Character.charCount(c)) {
            c = name.codePointAt(i);
            if (expectFirstChar) {
                if (Character.isJavaIdentifierStart(c)) {
                    expectFirstChar = false;
                } else {
                    break; // Will throw exception because `expectFirstChar` is true.
                }
            } else if (!Character.isJavaIdentifierPart(c)) {
                expectFirstChar = true;
                if (c != '.') {
                    break; // Will throw exception because `expectFirstChar` is true.
                }
            }
        }
        if (expectFirstChar) { // Also true if the name is empty
            throw new ModuleInfoPatchException(
                    "Invalid " + (module ? "module" : "package") + " name \"" + name + '"', reader);
        }
        return name;
    }

    /**
     * Reads a list of modules and stores the values in the given set.
     *
     * @param reader the reader from which to get the module names
     * @param target where to store the module names
     * @param specialCases special values to accept
     * @return {@code target} or a new set if the target was initially null
     * @throws IOException if an I/O error occurred while loading the file
     * @throws ModuleInfoPatchException if the next token is not a module name
     */
    private static void readModuleList(StreamTokenizer reader, Set<String> target, Set<String> specialCases)
            throws IOException {
        do {
            while (reader.nextToken() == StreamTokenizer.TT_WORD) {
                String module = reader.sval.strip();
                if (!specialCases.contains(module)) {
                    module = ensureValidName(reader, module, true);
                }
                target.add(module);
            }
        } while (reader.ttype == ',');
        if (reader.ttype != ';') {
            throw new ModuleInfoPatchException("Missing ';' character", reader);
        }
    }

    /**
     * Reads a package name followed by a list of modules names.
     * Used for qualified exports or qualified opens.
     *
     * @param reader the reader from which to get the module names
     * @param target where to store the module names
     * @param specialCases special values to accept
     * @throws IOException if an I/O error occurred while loading the file
     * @throws ModuleInfoPatchException if the next token is not a module name
     */
    private static void readQualified(StreamTokenizer reader, Map<String, Set<String>> target, Set<String> specialCases)
            throws IOException {
        String packageName = nextName(reader, false);
        expectToken(reader, "to");
        readModuleList(reader, modulesForPackage(target, packageName), specialCases);
    }

    /**
     * {@return the set of modules associated to the given package name}.
     *
     * @param target the map where to store the set of modules
     * @param packageName the package name for which to get a set of modules
     */
    private static Set<String> modulesForPackage(Map<String, Set<String>> target, String packageName) {
        return target.computeIfAbsent(packageName, (key) -> new LinkedHashSet<>());
    }

    /**
     * Bit mask for {@link #replaceTestModulePath(DependencyResolverResult)} internal usage.
     */
    private static final int COMPILE = 1;

    /**
     * Bit mask for {@link #replaceTestModulePath(DependencyResolverResult)} internal usage.
     */
    private static final int RUNTIME = 2;

    /**
     * Potentially adds the same value to compile and runtime sets.
     * Whether to add a value is specified by the {@code scope} bitmask,
     * which can contain a combination of {@link #COMPILE} and {@link #RUNTIME}.
     *
     * @param compile the collection where to add the value if the {@link #COMPILE} bit is set
     * @param runtime the collection where to add the value if the {@link #RUNTIME} bit is set
     * @param scope a combination of {@link #COMPILE} and {@link #RUNTIME} bits
     * @param module the value to potentially add
     * @return whether at least one collection has been modified
     */
    private static boolean addModuleName(Set<String> compile, Set<String> runtime, int scope, String module) {
        boolean modified = false;
        if ((scope & COMPILE) != 0) {
            modified = compile.add(module);
        }
        if ((scope & RUNTIME) != 0 && compile != runtime) {
            modified |= runtime.add(module);
        }
        return modified;
    }

    /**
     * Potentially adds the same value to compile and runtime exports.
     * Whether to add a value is specified by the {@code scope} bitmask,
     * which can contain a combination of {@link #COMPILE} and {@link #RUNTIME}.
     *
     * @param packageName name of the package to export
     * @param scope a combination of {@link #COMPILE} and {@link #RUNTIME} bits
     * @param module the module for which to export a package
     * @return whether at least one collection has been modified
     */
    private boolean addExport(String packageName, int scope, String module) {
        Set<String> compile = modulesForPackage(addExports, packageName);
        Set<String> runtime = compile;
        if (runtimeDependencies != this) {
            runtime = modulesForPackage(runtimeDependencies.addExports, packageName);
        }
        return addModuleName(compile, runtime, scope, module);
    }

    /**
     * Replaces all occurrences of {@link #SUBPROJECT_MODULES} by the actual module names.
     *
     * @param sourceDirectories the test source directories for all modules in the project
     */
    public void replaceProjectModules(final List<SourceDirectory> sourceDirectories) {
        for (Map.Entry<String, Set<String>> entry : addExports.entrySet()) {
            if (entry.getValue().remove(SUBPROJECT_MODULES)) {
                for (final SourceDirectory source : sourceDirectories) {
                    final String module = source.moduleName;
                    if (module != null && !module.equals(moduleName)) {
                        addExport(entry.getKey(), COMPILE | RUNTIME, module);
                    }
                }
            }
        }
    }

    /**
     * Replaces all occurrences of {@link #TEST_MODULE_PATH} by the actual module names.
     * These dependencies are automatically added to the {@code --add-modules} option once for all modules,
     * then added to the {@code add-reads} option if the user specified the {@code TEST-MODULE-PATH} value.
     * The latter is on a per-module basis. These options are also added implicitly if the user did not put
     * a {@value #FILENAME} file in the test.
     *
     * @param dependencyResolution the result of resolving the dependencies, or {@code null} if none
     * @throws IOException if an error occurred while reading information from a dependency
     */
    public void replaceTestModulePath(final DependencyResolverResult dependencyResolution) throws IOException {
        final var exportsToTestModulePath = new LinkedHashSet<String>(); // Packages to export.
        for (Map.Entry<String, Set<String>> entry : addExports.entrySet()) {
            if (entry.getValue().remove(TEST_MODULE_PATH)) {
                exportsToTestModulePath.add(entry.getKey());
            }
        }
        final boolean addAllTestModulePath = addModules.remove(TEST_MODULE_PATH);
        final boolean readAllTestModulePath = addReads.remove(TEST_MODULE_PATH);
        if (!addAllTestModulePath && !readAllTestModulePath && exportsToTestModulePath.isEmpty()) {
            return; // Nothing to do.
        }
        if (dependencyResolution == null) {
            // Note: we could log a warning, but we would need to ensure that it is logged only once.
            return;
        }
        /*
         * At this point, all `TEST-MODULE-PATCH` special values have been removed, but the actual module names
         * have not yet been added. The module names may be added in two different instances. This instance is
         * used for compile-time dependencies, while the `runtime` instance is used for runtime dependencies.
         * The latter is created only if at least one dependency is different.
         */
        final var done = new HashMap<String, Integer>(); // Added modules and their dependencies.
        for (Map.Entry<Dependency, Path> entry :
                dependencyResolution.getDependencies().entrySet()) {

            final int scope; // As a bitmask.
            switch (entry.getKey().getScope()) {
                case TEST:
                    scope = COMPILE | RUNTIME;
                    break;
                case TEST_ONLY:
                    scope = COMPILE;
                    if (runtimeDependencies == this) {
                        runtimeDependencies = new ModuleInfoPatch(this);
                    }
                    break;
                case TEST_RUNTIME:
                    scope = RUNTIME;
                    if (runtimeDependencies == this) {
                        runtimeDependencies = new ModuleInfoPatch(this);
                    }
                    break;
                default:
                    continue; // Skip non-test dependencies because they should already be in the main module-info.
            }
            Path dependencyPath = entry.getValue();
            String module = dependencyResolution.getModuleName(dependencyPath).orElse(null);
            if (module == null) {
                if (readAllTestModulePath) {
                    addModuleName(addReads, runtimeDependencies.addReads, scope, "ALL-UNNAMED");
                }
            } else if (mergeBit(done, module, scope)) {
                boolean modified = false;
                if (addAllTestModulePath) {
                    modified |= addModuleName(addModules, runtimeDependencies.addModules, scope, module);
                }
                if (readAllTestModulePath) {
                    modified |= addModuleName(addReads, runtimeDependencies.addReads, scope, module);
                }
                for (String packageName : exportsToTestModulePath) {
                    modified |= addExport(packageName, scope, module);
                }
                /*
                 * For making the options simpler, we do not add `--add-modules` or `--add-reads`
                 * options for modules that are required by a module that we already added. This
                 * simplification is not necessary, but makes the command-line easier to read.
                 */
                if (modified) {
                    dependencyResolution.getModuleDescriptor(dependencyPath).ifPresent((descriptor) -> {
                        for (ModuleDescriptor.Requires r : descriptor.requires()) {
                            done.merge(r.name(), scope, (o, n) -> o | n);
                        }
                    });
                }
            }
        }
    }

    /**
     * Sets the given bit in a map of bit masks.
     *
     * @param map the map where to set a bit
     * @param key key of the entry for which to set a bit
     * @param bit the bit to set
     * @return whether the map changed as a result of this operation
     */
    private static boolean mergeBit(final Map<String, Integer> map, final String key, final int bit) {
        Integer mask = map.putIfAbsent(key, bit);
        if (mask != null) {
            if ((mask & bit) != 0) {
                return false;
            }
            map.put(key, mask | bit);
        }
        return true;
    }

    /**
     * Returns a patch for another module with the same {@code --add-reads} options. All other options are empty.
     * This is used when a {@code ModuleInfoPatch} instance has been created for the implicit options and the
     * caller wants to replicate these default values to other modules declared in the {@code <sources>}.
     *
     * <h4>Constraint</h4>
     * This method should be invoked <em>after</em> {@link #replaceTestModulePath(DependencyResolverResult)},
     * otherwise the runtime dependencies derived from {@code TEST-MODULE-PaTH} may not be correct.
     *
     * @param otherModule the other module to patch, or {@code null} or empty if none
     * @return patch for the other module, or {@code null} if {@code otherModule} was null or empty
     */
    public ModuleInfoPatch patchWithSameReads(String otherModule) {
        if (otherModule == null || otherModule.isBlank()) {
            return null;
        }
        var other = new ModuleInfoPatch(addReads, otherModule);
        other.runtimeDependencies =
                (runtimeDependencies == this) ? other : new ModuleInfoPatch(runtimeDependencies.addReads, otherModule);
        return other;
    }

    /**
     * {@return the name of the module to patch, or null if unspecified and no default}.
     */
    public String getModuleName() {
        return moduleName;
    }

    /**
     * Writes the values of the given option if the values is is non-null.
     *
     * @param option the option for which to write the values
     * @param prefix prefix to write, followed by {@code '='}, before the value, or empty if none
     * @param compile the values to write for the compiler, or {@code null} if none
     * @param runtime the values to write for the Java launcher
     * @param configuration where to write the option values for the compiler
     * @param out where to write the option values for the Java launcher
     */
    private static void write(
            String option,
            String prefix,
            Set<String> compile,
            Set<String> runtime,
            Options configuration,
            BufferedWriter out)
            throws IOException {
        Set<String> values = runtime;
        do {
            if (!values.isEmpty()) {
                var buffer = new StringJoiner(",", (prefix != null) ? prefix + '=' : "", "");
                for (String value : values) {
                    buffer.add(value);
                }
                if (values == compile) {
                    configuration.addIfNonBlank("--" + option, buffer.toString());
                }
                if (values == runtime) {
                    out.append("--").append(option).append(' ').append(buffer.toString());
                    out.newLine();
                }
            }
        } while (values != compile && (values = compile) != null);
    }

    /**
     * Writes options that are qualified by module name and package name.
     *
     * @param option the option for which to write the values
     * @param compile the values to write for the compiler, or {@code null} if none
     * @param runtime the values to write for the Java launcher
     * @param configuration where to write the option values for the compiler
     * @param out where to write the option values for the Java launcher
     */
    private void write(
            String option,
            Map<String, Set<String>> compile,
            Map<String, Set<String>> runtime,
            Options configuration,
            BufferedWriter out)
            throws IOException {
        Map<String, Set<String>> values = runtime;
        do {
            for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
                String prefix = moduleName + '/' + entry.getKey();
                Set<String> otherModules = entry.getValue();
                write(
                        option,
                        prefix,
                        (values == compile) ? otherModules : null,
                        (values == runtime) ? otherModules : Set.of(),
                        configuration,
                        out);
            }
        } while (values != compile && (values = compile) != null);
    }

    /**
     * Writes the options.
     *
     * @param compile where to write the compile-time options
     * @param runtime where to write the runtime options
     */
    public void writeTo(final Options compile, final BufferedWriter runtime) throws IOException {
        write("add-modules", null, addModules, runtimeDependencies.addModules, compile, runtime);
        write("limit-modules", null, limitModules, runtimeDependencies.limitModules, compile, runtime);
        if (moduleName != null) {
            write("add-reads", moduleName, addReads, runtimeDependencies.addReads, compile, runtime);
            write("add-exports", addExports, runtimeDependencies.addExports, compile, runtime);
            write("add-opens", null, runtimeDependencies.addOpens, compile, runtime);
        }
        addModules.clear(); // Add modules only once (this set is shared by other instances).
        limitModules.clear();
    }
}
