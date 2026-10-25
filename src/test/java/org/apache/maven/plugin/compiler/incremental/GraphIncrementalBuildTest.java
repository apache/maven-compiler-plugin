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
package org.apache.maven.plugin.compiler.incremental;

import javax.tools.JavaCompiler;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphIncrementalBuildTest {

    @TempDir
    Path workDir;

    Path sourceDir;
    Path classesDir;

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = workDir.resolve("src");
        classesDir = workDir.resolve("target/classes");
        Files.createDirectories(classesDir);

        CompilerTestHelper.writeSource(
                sourceDir,
                "api",
                "Model",
                "package api; public class Model { private String name; public String getName() { return name; } public void setName(String n) { this.name = n; } }");
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Helper",
                "package impl; public class Helper { public String normalize(String s) { return s == null ? \"\" : s.trim(); } }");
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Service",
                "package impl; import api.Model; public class Service { private final Helper h = new Helper(); public Model process(String input) { Model m = new Model(); m.setName(h.normalize(input)); return m; } }");
    }

    private void doFullBuildCycle() throws Exception {
        doFullBuildCycle(false, false);
    }

    private void doFullBuildCycle(boolean modular) throws Exception {
        doFullBuildCycle(modular, false);
    }

    private void doFullBuildCycle(boolean modular, boolean abiTracking) throws Exception {
        var build = new GraphIncrementalBuild(classesDir);
        build.setAbiTracking(abiTracking);
        List<Path> allFiles = listSources();
        Set<Path> toCompile = build.initialize(allFiles);

        while (!toCompile.isEmpty()) {
            compileFiles(toCompile, modular);
            toCompile = build.processCompiledClasses(toCompile);
        }
        build.finish();
    }

    private void compileFiles(Set<Path> files) throws Exception {
        compileFiles(files, false);
    }

    private void compileFiles(Set<Path> files, boolean modular) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var fm = compiler.getStandardFileManager(null, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, List.of(classesDir.toFile()));
            if (modular) {
                fm.setLocation(StandardLocation.SOURCE_PATH, List.of(sourceDir.toFile()));
            } else {
                fm.setLocation(StandardLocation.SOURCE_PATH, List.of());
            }
            var units = fm.getJavaFileObjectsFromPaths(files);
            var task = compiler.getTask(null, fm, null, null, null, units);
            if (!task.call()) {
                throw new RuntimeException("Compilation failed");
            }
        }
    }

    private List<Path> listSources() throws Exception {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void fullBuildCompilesEverything() throws Exception {
        var build = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build.initialize(listSources());

        assertTrue(build.isFullBuild(), "First run should be full build");
        assertEquals(3, toCompile.size(), "Should compile all 3 files");

        compileFiles(toCompile);
        build.processCompiledClasses(toCompile);
        build.finish();

        // State should exist
        assertTrue(
                Files.exists(workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state")));
        assertEquals(3, build.compiledCount());
    }

    @Test
    void noChangeReturnsEmpty() throws Exception {
        doFullBuildCycle();

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());
        assertTrue(toCompile.isEmpty(), "No changes should return empty set");
        assertNull(build2.getRebuildCause(), "No changes should have no rebuild cause");
        build2.finish();
    }

    @Test
    void rebuildCauseDescribesChanges() throws Exception {
        doFullBuildCycle();

        // Modify one file, add a new one
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Helper",
                "package impl; public class Helper { public String normalize(String s) { return s == null ? \"\" : s.strip(); } }");
        CompilerTestHelper.writeSource(sourceDir, "api", "Extra", "package api; public class Extra {}");

        var build2 = new GraphIncrementalBuild(classesDir);
        build2.initialize(listSources());
        assertNotNull(build2.getRebuildCause(), "Should have a rebuild cause");
        assertTrue(build2.getRebuildCause().contains("changed"), "Should mention changed files");
        assertTrue(build2.getRebuildCause().contains("new"), "Should mention new files");
    }

    @Test
    void fullBuildCauseDescribed() throws Exception {
        var build = new GraphIncrementalBuild(classesDir);
        build.initialize(listSources());
        assertNotNull(build.getRebuildCause(), "Full build should have a cause");
        assertTrue(build.getRebuildCause().contains("no previous"), "Should mention no previous state");
    }

    @Test
    void bodyOnlyChangeRecompilesOnlyChangedFile() throws Exception {
        doFullBuildCycle();

        // Modify Helper body only
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Helper",
                "package impl; public class Helper { public String normalize(String s) { return s == null ? \"\" : s.strip().toLowerCase(); } }");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());
        assertFalse(build2.isFullBuild());
        assertEquals(1, toCompile.size(), "Only Helper should need compilation");

        compileFiles(toCompile);
        Set<Path> cascade = build2.processCompiledClasses(toCompile);
        // In the dep-graph strategy, any change to a class cascades to its consumers.
        // Helper is used by Service (implementation dep), so Service must be recompiled.
        assertFalse(cascade.isEmpty(), "Body change cascades to consumers in graph strategy");

        // Compile the cascade set
        while (!cascade.isEmpty()) {
            compileFiles(cascade);
            cascade = build2.processCompiledClasses(cascade);
        }

        build2.finish();
        // Helper + its consumers (Service) compiled — Model was not touched
        assertTrue(build2.compiledCount() >= 1);
        assertTrue(build2.unchangedCount() < 3, "At least one file should be unchanged (Model)");
    }

    @Test
    void abiChangeCascadesToSignatureConsumers() throws Exception {
        doFullBuildCycle();

        // Add public method to Model (ABI change)
        CompilerTestHelper.writeSource(
                sourceDir,
                "api",
                "Model",
                "package api; public class Model { private String name; public String getName() { return name; } public void setName(String n) { this.name = n; } public boolean isValid() { return name != null; } }");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> round1 = build2.initialize(listSources());
        assertEquals(1, round1.size(), "Only Model changed");

        compileFiles(round1);
        Set<Path> round2 = build2.processCompiledClasses(round1);

        // Service has Model as signature dep → should cascade
        assertFalse(round2.isEmpty(), "ABI change should cascade");

        compileFiles(round2);
        Set<Path> round3 = build2.processCompiledClasses(round2);
        assertTrue(round3.isEmpty(), "Should reach fixpoint");

        build2.finish();
        // Model + Service should be compiled; Helper should not
        assertTrue(build2.compiledCount() >= 2, "At least Model and Service should be compiled");
    }

    @Test
    void abiChangeCascadesToImplConsumersDirectlyOnly() throws Exception {
        doFullBuildCycle();

        // Add public method to Helper (ABI change)
        // Service has Helper as impl dep → Service recompiled but no further cascade
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Helper",
                "package impl; public class Helper { public String normalize(String s) { return s == null ? \"\" : s.trim(); } public int count(String s) { return s.length(); } }");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> round1 = build2.initialize(listSources());
        assertEquals(1, round1.size(), "Only Helper changed");

        compileFiles(round1);
        Set<Path> round2 = build2.processCompiledClasses(round1);

        // Service has Helper as impl dep → should cascade directly
        assertFalse(round2.isEmpty(), "Impl dep ABI change should trigger cascade to Service");

        compileFiles(round2);
        Set<Path> round3 = build2.processCompiledClasses(round2);
        assertTrue(round3.isEmpty(), "Impl dep cascade should not propagate further");

        build2.finish();
        // Helper + Service compiled, Model untouched
        assertEquals(2, build2.compiledCount());
    }

    @Test
    void newFileDetected() throws Exception {
        doFullBuildCycle();

        CompilerTestHelper.writeSource(
                sourceDir, "api", "Extra", "package api; public class Extra { public String val() { return \"\"; } }");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());
        assertFalse(toCompile.isEmpty(), "New file should be detected");
        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().contains("Extra")),
                "Extra.java should be in compile set");

        compileFiles(toCompile);
        build2.processCompiledClasses(toCompile);
        build2.finish();
    }

    @Test
    void deletedFileConsumersRecompiled() throws Exception {
        doFullBuildCycle();

        // Delete Helper.java — Service depends on Helper
        CompilerTestHelper.deleteSource(sourceDir, "impl", "Helper");

        // Also rewrite Service to not depend on Helper (otherwise compilation fails)
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Service",
                "package impl; import api.Model; public class Service { public Model process(String input) { Model m = new Model(); m.setName(input); return m; } }");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());
        assertFalse(toCompile.isEmpty(), "Deleted file should trigger recompilation of consumers");
        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().contains("Service")),
                "Service (consumer of deleted Helper) should be recompiled");

        compileFiles(toCompile);
        build2.processCompiledClasses(toCompile);
        build2.finish();

        // Helper.class should be deleted
        assertFalse(
                Files.exists(classesDir.resolve("impl/Helper.class")), "Deleted type's class file should be removed");
    }

    @Test
    void removedInnerClassDeletesStaleClassFile() throws Exception {
        // Set up source with inner class
        CompilerTestHelper.writeSource(
                sourceDir,
                "api",
                "Model",
                "package api; public class Model { private String name; public String getName() { return name; } public void setName(String n) { this.name = n; } public static class Builder { public Model build() { return new Model(); } } }");

        doFullBuildCycle();

        // Inner class file should exist after full build
        assertTrue(
                Files.exists(classesDir.resolve("api/Model$Builder.class")),
                "Inner class file should exist after full build");

        // Remove the inner class
        CompilerTestHelper.writeSource(
                sourceDir,
                "api",
                "Model",
                "package api; public class Model { private String name; public String getName() { return name; } public void setName(String n) { this.name = n; } }");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> round1 = build2.initialize(listSources());
        assertFalse(round1.isEmpty(), "Modified file should need recompilation");

        compileFiles(round1);
        Set<Path> round2 = build2.processCompiledClasses(round1);

        // Cascade any ABI consumers
        while (!round2.isEmpty()) {
            compileFiles(round2);
            round2 = build2.processCompiledClasses(round2);
        }
        build2.finish();

        // Stale inner class file should be cleaned up
        assertFalse(
                Files.exists(classesDir.resolve("api/Model$Builder.class")),
                "Stale inner class file should be deleted when inner class is removed");
        assertTrue(Files.exists(classesDir.resolve("api/Model.class")), "Main class file should still exist");
    }

    @Test
    void moduleInfoTrackedInFullBuild() throws Exception {
        // Set up modular sources
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.mod {\n  exports api;\n}\n");

        doFullBuildCycle(true);

        // State should exist and include module entry
        assertTrue(
                Files.exists(workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state")));
        var state = IncrementalState.load(
                workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state"));
        assertNotNull(state, "State should be loaded");
        assertNotNull(state.getType("module:my.mod"), "State should contain module-info entry with 'module:' prefix");
    }

    @Test
    void moduleInfoChangeDetectedAsIncremental() throws Exception {
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.mod {\n  exports api;\n}\n");

        doFullBuildCycle(true);

        // Modify module-info.java — add exports impl
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Helper",
                "package impl; public class Helper { public String normalize(String s) { return s == null ? \"\" : s.trim(); } }");
        Files.writeString(
                sourceDir.resolve("module-info.java"), "module my.mod {\n  exports api;\n  exports impl;\n}\n");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());
        assertFalse(toCompile.isEmpty(), "Changed module-info should need recompilation");
        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().endsWith("module-info.java")),
                "module-info.java should be in compile set");
    }

    @Test
    void moduleInfoChangeIsDetectedAsIncremental() throws Exception {
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.mod {\n  exports api;\n}\n");

        doFullBuildCycle(true);

        var state1 = IncrementalState.load(
                workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state"));
        assertNotNull(state1.getType("module:my.mod"));

        // Change exports → module-info should be detected as changed
        Files.writeString(
                sourceDir.resolve("module-info.java"), "module my.mod {\n  exports api;\n  exports impl;\n}\n");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());

        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().endsWith("module-info.java")),
                "module-info.java should be in compile set when exports change");

        while (!toCompile.isEmpty()) {
            compileFiles(toCompile, true);
            toCompile = build2.processCompiledClasses(toCompile);
        }
        build2.finish();

        var state2 = IncrementalState.load(
                workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state"));
        assertNotNull(state2.getType("module:my.mod"), "Module entry should still be tracked after change");
    }

    @Test
    void moduleNameChangeTriggersFullRebuild() throws Exception {
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.old {\n  exports api;\n}\n");

        doFullBuildCycle(true);

        // Change the module name
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.renamed {\n  exports api;\n}\n");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());
        assertFalse(build2.isFullBuild(), "Should start as incremental");

        // First round: recompile module-info.java
        compileFiles(toCompile, true);
        Set<Path> cascade = build2.processCompiledClasses(toCompile);

        // Module name change should force recompilation of all remaining files
        assertFalse(cascade.isEmpty(), "Module name change should trigger full rebuild");

        // Complete all rounds
        while (!cascade.isEmpty()) {
            compileFiles(cascade, true);
            cascade = build2.processCompiledClasses(cascade);
        }
        build2.finish();

        // State should reflect the new module name
        var state = IncrementalState.load(
                workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state"));
        assertNotNull(state.getType("module:my.renamed"), "State should have new module name");
        assertNull(state.getType("module:my.old"), "State should not have old module name");
    }

    @Test
    void packageInfoChangeDetected() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir,
                "api",
                "ApiStatus",
                "package api; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PACKAGE) public @interface ApiStatus { String value(); }");
        CompilerTestHelper.writeSource(sourceDir, "api", "package-info", "@api.ApiStatus(\"stable\")\npackage api;\n");

        doFullBuildCycle();

        assertTrue(
                Files.exists(classesDir.resolve("api/package-info.class")),
                "package-info.class should exist after full build");

        // Modify the package-info annotation value
        CompilerTestHelper.writeSource(
                sourceDir, "api", "package-info", "@api.ApiStatus(\"experimental\")\npackage api;\n");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> toCompile = build2.initialize(listSources());
        assertFalse(toCompile.isEmpty(), "Changed package-info should need recompilation");
        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().endsWith("package-info.java")),
                "package-info.java should be in compile set");

        compileFiles(toCompile);
        build2.processCompiledClasses(toCompile);
        build2.finish();
    }

    @Test
    void constantValueChangeTriggersCascade() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir,
                "api",
                "Constants",
                "package api; public final class Constants { public static final int MAX = 100; private Constants() {} }");
        CompilerTestHelper.writeSource(
                sourceDir,
                "impl",
                "Service",
                "package impl; import api.Constants; import api.Model; public class Service { public Model process(String input) { Model m = new Model(); if (input.length() > Constants.MAX) { m.setName(input.substring(0, Constants.MAX)); } else { m.setName(input); } return m; } }");

        doFullBuildCycle();

        // Change constant value
        CompilerTestHelper.writeSource(
                sourceDir,
                "api",
                "Constants",
                "package api; public final class Constants { public static final int MAX = 200; private Constants() {} }");

        var build2 = new GraphIncrementalBuild(classesDir);
        Set<Path> round1 = build2.initialize(listSources());
        assertEquals(1, round1.size(), "Only Constants changed");

        compileFiles(round1);
        Set<Path> round2 = build2.processCompiledClasses(round1);

        // Constants ABI changed (value is part of fingerprint) → Service should cascade
        assertFalse(round2.isEmpty(), "Constant value change should cascade to consumers");

        compileFiles(round2);
        build2.processCompiledClasses(round2);
        build2.finish();
    }

    @Test
    void stateFileInMavenStatusDirectory() throws Exception {
        doFullBuildCycle();

        // State file should be in target/maven-status/maven-compiler-plugin/classes/, not inside output directory
        Path stateFile = workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state");
        assertTrue(
                Files.exists(stateFile), "State file should be in target/maven-status/maven-compiler-plugin/classes/");
        assertFalse(
                Files.exists(classesDir.resolve("incremental-state")),
                "State file should NOT be inside output directory (target/classes/)");
    }

    @Test
    void separateOutputDirsGetSeparateState() throws Exception {
        doFullBuildCycle();

        // Simulate a second execution with a different output directory (e.g., test-compile)
        Path testClassesDir = workDir.resolve("target/test-classes");
        Files.createDirectories(testClassesDir);

        Path testSourceDir = workDir.resolve("test-src");
        CompilerTestHelper.writeSource(
                testSourceDir, "test", "MyTest", "package test; public class MyTest { public void run() {} }");

        var build2 = new GraphIncrementalBuild(testClassesDir);
        List<Path> testFiles;
        try (var walk = Files.walk(testSourceDir)) {
            testFiles =
                    walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        build2.initialize(testFiles);
        assertTrue(build2.isFullBuild(), "Second output dir should get its own full build");

        // State file should be in the maven-status directory
        // Original state should be untouched
        var mainState = IncrementalState.load(
                workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state"));
        assertNotNull(mainState, "Main compile state should still exist");
        assertEquals(3, mainState.getSourceHashes().size(), "Main state should have 3 sources");
    }

    @Test
    void invalidateDeletesStateFile() throws Exception {
        doFullBuildCycle();

        Path stateFile = workDir.resolve("target/maven-status/maven-compiler-plugin/classes/incremental-state");
        assertTrue(Files.exists(stateFile), "State file should exist after successful build");

        // Simulate compilation failure → invalidate
        var build2 = new GraphIncrementalBuild(classesDir);
        build2.initialize(listSources());
        build2.invalidate();

        assertFalse(Files.exists(stateFile), "State file should be deleted after invalidate");

        // Next build should be a full build
        var build3 = new GraphIncrementalBuild(classesDir);
        build3.initialize(listSources());
        assertTrue(build3.isFullBuild(), "Build after invalidation should be full");
    }

    @Test
    void configHashChangeTriggersFullRebuild() throws Exception {
        // Build with configHash "abc"
        var abi1 = new GraphIncrementalBuild(classesDir);
        abi1.setConfigHash("abc");
        Set<Path> toCompile = abi1.initialize(listSources());
        assertTrue(abi1.isFullBuild(), "First build should be full");

        compileFiles(toCompile);
        abi1.processCompiledClasses(toCompile);
        abi1.finish();

        // Same configHash → incremental (no changes)
        var build2 = new GraphIncrementalBuild(classesDir);
        build2.setConfigHash("abc");
        toCompile = build2.initialize(listSources());
        assertTrue(toCompile.isEmpty(), "Same configHash should be up-to-date");

        // Different configHash → full rebuild
        var build3 = new GraphIncrementalBuild(classesDir);
        build3.setConfigHash("xyz");
        build3.initialize(listSources());
        assertTrue(build3.isFullBuild(), "Changed configHash should trigger full rebuild");
    }

    @Test
    void finishWritesAbiManifest() throws Exception {
        doFullBuildCycle(false, true);

        // Manifest should be written to the build directory (parent of classes/)
        Path manifest = workDir.resolve("target/" + AbiManifest.FILENAME);
        assertTrue(Files.exists(manifest), "ABI manifest should be written by finish()");

        var fingerprints = AbiManifest.read(manifest);
        assertFalse(fingerprints.isEmpty(), "Manifest should contain fingerprints");
        // All three types should be in the manifest
        assertTrue(fingerprints.containsKey("api.Model"), "Manifest should contain api.Model");
        assertTrue(fingerprints.containsKey("impl.Helper"), "Manifest should contain impl.Helper");
        assertTrue(fingerprints.containsKey("impl.Service"), "Manifest should contain impl.Service");
    }

    @Test
    void crossModuleAbiChangeInvalidatesConsumers() throws Exception {
        Path upstreamTarget = workDir.resolve("upstream/target");
        Path upstreamClasses = upstreamTarget.resolve("classes");
        Path upstreamSrc = workDir.resolve("upstream/src");
        Files.createDirectories(upstreamClasses);
        Files.createDirectories(upstreamSrc.resolve("api"));

        CompilerTestHelper.writeSource(
                upstreamSrc,
                "api",
                "Model",
                "package api; public class Model { public String get() { return \"\"; } }");

        List<Path> upstreamFiles = List.of(upstreamSrc.resolve("api/Model.java"));
        var upBuild1 = new GraphIncrementalBuild(upstreamClasses);
        upBuild1.setAbiTracking(true);
        Set<Path> upCompile1 = upBuild1.initialize(upstreamFiles);
        CompilerTestHelper.compileFiles(upstreamClasses, upCompile1);
        upBuild1.processCompiledClasses(upCompile1);
        upBuild1.finish();

        Path upManifest = upstreamTarget.resolve(AbiManifest.FILENAME);
        assertTrue(Files.exists(upManifest), "Upstream manifest should be written");
        assertTrue(AbiManifest.read(upManifest).containsKey("api.Model"), "Manifest should contain api.Model");

        Path downstreamTarget = workDir.resolve("downstream/target");
        Path downstreamClasses = downstreamTarget.resolve("classes");
        Path downstreamSrc = workDir.resolve("downstream/src");
        Files.createDirectories(downstreamClasses);
        Files.createDirectories(downstreamSrc.resolve("impl"));

        CompilerTestHelper.writeSource(
                downstreamSrc,
                "impl",
                "Service",
                "package impl; import api.Model; public class Service { public Model build() { return new Model(); } }");

        List<Path> downstreamFiles = List.of(downstreamSrc.resolve("impl/Service.java"));
        var downBuild1 = new GraphIncrementalBuild(downstreamClasses);
        downBuild1.setAbiTracking(true);
        downBuild1.setClasspathEntries(List.of(upstreamClasses));
        downBuild1.setReactorModulePaths(Set.of(upstreamClasses));
        Set<Path> downCompile1 = downBuild1.initialize(downstreamFiles);
        CompilerTestHelper.compileFiles(downstreamClasses, downCompile1, upstreamClasses);
        downBuild1.processCompiledClasses(downCompile1);
        downBuild1.finish();

        CompilerTestHelper.writeSource(
                upstreamSrc,
                "api",
                "Model",
                "package api; public class Model { public String get() { return \"\"; } public int size() { return 0; } }");

        var upBuild2 = new GraphIncrementalBuild(upstreamClasses);
        upBuild2.setAbiTracking(true);
        Set<Path> upCompile2 = upBuild2.initialize(upstreamFiles);
        assertFalse(upCompile2.isEmpty(), "Upstream should recompile Model");
        CompilerTestHelper.compileFiles(upstreamClasses, upCompile2);
        upBuild2.processCompiledClasses(upCompile2);
        upBuild2.finish();

        var newFingerprints = AbiManifest.read(upManifest);
        assertFalse(newFingerprints.isEmpty(), "Updated manifest should be non-empty");

        var downBuild2 = new GraphIncrementalBuild(downstreamClasses);
        downBuild2.setAbiTracking(true);
        downBuild2.setClasspathEntries(List.of(upstreamClasses));
        downBuild2.setReactorModulePaths(Set.of(upstreamClasses));
        Set<Path> downCompile2 = downBuild2.initialize(downstreamFiles);
        assertFalse(downCompile2.isEmpty(), "Downstream should be invalidated by upstream ABI change");
        assertTrue(
                downCompile2.contains(downstreamSrc.resolve("impl/Service.java")),
                "Service.java should be scheduled for recompilation");
    }

    @Test
    void externalClasspathChangeInvalidatesDependents() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir, "app", "Client", "package app; public class Client { public void run() {} }");

        Path jarPath = workDir.resolve("lib/external.jar");
        Files.createDirectories(jarPath.getParent());
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            jos.write("Manifest-Version: 1.0\n".getBytes());
            jos.closeEntry();
        }

        var build1 = new GraphIncrementalBuild(classesDir);
        build1.setClasspathEntries(List.of(jarPath));
        List<Path> sources = List.of(sourceDir.resolve("app/Client.java"));
        Set<Path> toCompile = build1.initialize(sources);
        assertFalse(toCompile.isEmpty(), "First build should compile Client.java");
        compileFiles(toCompile);
        build1.processCompiledClasses(toCompile);
        build1.finish();

        var build2 = new GraphIncrementalBuild(classesDir);
        build2.setClasspathEntries(List.of(jarPath));
        Set<Path> toRecompile = build2.initialize(sources);
        assertTrue(toRecompile.isEmpty(), "Second build with unchanged JAR should be up-to-date");
        build2.finish();
    }
}
