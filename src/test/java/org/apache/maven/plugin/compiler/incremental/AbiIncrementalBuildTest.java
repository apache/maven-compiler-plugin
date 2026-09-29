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
import java.util.stream.Stream;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbiIncrementalBuildTest {

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
        doFullBuildCycle(false);
    }

    private void doFullBuildCycle(boolean modular) throws Exception {
        var abi = new AbiIncrementalBuild(classesDir);
        List<Path> allFiles = listSources();
        Set<Path> toCompile = abi.initialize(allFiles);

        while (!toCompile.isEmpty()) {
            compileFiles(toCompile, modular);
            abi.attachTo(lastTask);
            lastTask.call();
            toCompile = abi.processRound();
        }
        abi.finish();
    }

    private JavacTask lastTask;

    private void compileFiles(Set<Path> files) throws Exception {
        compileFiles(files, false);
    }

    private void compileFiles(Set<Path> files, boolean modular) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var fm = compiler.getStandardFileManager(null, null, null);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
        fm.setLocation(StandardLocation.CLASS_PATH, List.of(classesDir.toFile()));
        if (modular) {
            fm.setLocation(StandardLocation.SOURCE_PATH, List.of(sourceDir.toFile()));
        } else {
            fm.setLocation(StandardLocation.SOURCE_PATH, List.of());
        }
        var units = fm.getJavaFileObjectsFromPaths(files);
        lastTask = (JavacTask) compiler.getTask(null, fm, null, null, null, units);
    }

    private List<Path> listSources() throws Exception {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void fullBuildCompilesEverything() throws Exception {
        var abi = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi.initialize(listSources());

        assertTrue(abi.isFullBuild(), "First run should be full build");
        assertEquals(3, toCompile.size(), "Should compile all 3 files");

        compileFiles(toCompile);
        abi.attachTo(lastTask);
        lastTask.call();
        abi.processRound();
        abi.finish();

        // State and manifest should exist
        assertTrue(Files.exists(workDir.resolve("target/.incremental-state")));
        assertTrue(Files.exists(workDir.resolve("target/.abi-fingerprints")));
        assertEquals(3, abi.compiledCount());
    }

    @Test
    void noChangeReturnsEmpty() throws Exception {
        doFullBuildCycle();

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi2.initialize(listSources());
        assertTrue(toCompile.isEmpty(), "No changes should return empty set");
        abi2.finish();
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

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi2.initialize(listSources());
        assertFalse(abi2.isFullBuild());
        assertEquals(1, toCompile.size(), "Only Helper should need compilation");

        compileFiles(toCompile);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> cascade = abi2.processRound();
        assertTrue(cascade.isEmpty(), "Body-only change should not cascade");

        abi2.finish();
        assertEquals(1, abi2.compiledCount());
        assertEquals(2, abi2.unchangedCount());
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

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> round1 = abi2.initialize(listSources());
        assertEquals(1, round1.size(), "Only Model changed");

        compileFiles(round1);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> round2 = abi2.processRound();

        // Service has Model as signature dep → should cascade
        assertFalse(round2.isEmpty(), "ABI change should cascade");

        compileFiles(round2);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> round3 = abi2.processRound();
        assertTrue(round3.isEmpty(), "Should reach fixpoint");

        abi2.finish();
        // Model + Service should be compiled; Helper should not
        assertTrue(abi2.compiledCount() >= 2, "At least Model and Service should be compiled");
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

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> round1 = abi2.initialize(listSources());
        assertEquals(1, round1.size(), "Only Helper changed");

        compileFiles(round1);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> round2 = abi2.processRound();

        // Service has Helper as impl dep → should cascade directly
        assertFalse(round2.isEmpty(), "Impl dep ABI change should trigger cascade to Service");

        compileFiles(round2);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> round3 = abi2.processRound();
        assertTrue(round3.isEmpty(), "Impl dep cascade should not propagate further");

        abi2.finish();
        // Helper + Service compiled, Model untouched
        assertEquals(2, abi2.compiledCount());
    }

    @Test
    void newFileDetected() throws Exception {
        doFullBuildCycle();

        CompilerTestHelper.writeSource(
                sourceDir, "api", "Extra", "package api; public class Extra { public String val() { return \"\"; } }");

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi2.initialize(listSources());
        assertFalse(toCompile.isEmpty(), "New file should be detected");
        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().contains("Extra")),
                "Extra.java should be in compile set");

        compileFiles(toCompile);
        abi2.attachTo(lastTask);
        lastTask.call();
        abi2.processRound();
        abi2.finish();
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

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi2.initialize(listSources());
        assertFalse(toCompile.isEmpty(), "Deleted file should trigger recompilation of consumers");
        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().contains("Service")),
                "Service (consumer of deleted Helper) should be recompiled");

        compileFiles(toCompile);
        abi2.attachTo(lastTask);
        lastTask.call();
        abi2.processRound();
        abi2.finish();

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

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> round1 = abi2.initialize(listSources());
        assertFalse(round1.isEmpty(), "Modified file should need recompilation");

        compileFiles(round1);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> round2 = abi2.processRound();

        // Cascade any ABI consumers
        while (!round2.isEmpty()) {
            compileFiles(round2);
            abi2.attachTo(lastTask);
            lastTask.call();
            round2 = abi2.processRound();
        }
        abi2.finish();

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
        assertTrue(Files.exists(workDir.resolve("target/.incremental-state")));
        var state = IncrementalState.load(workDir.resolve("target/.incremental-state"));
        assertNotNull(state, "State should be loaded");
        assertNotNull(state.getType("module:my.mod"), "State should contain module-info entry with 'module:' prefix");
        assertNotNull(state.getAbiFingerprint("module:my.mod"), "Module entry should have an ABI fingerprint");
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

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi2.initialize(listSources());
        assertFalse(toCompile.isEmpty(), "Changed module-info should need recompilation");
        assertTrue(
                toCompile.stream().anyMatch(p -> p.toString().endsWith("module-info.java")),
                "module-info.java should be in compile set");
    }

    @Test
    void moduleInfoAbiFingerprintChangesOnDirectiveChange() throws Exception {
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.mod {\n  exports api;\n}\n");

        doFullBuildCycle(true);

        var state1 = IncrementalState.load(workDir.resolve("target/.incremental-state"));
        String fingerprint1 = state1.getAbiFingerprint("module:my.mod");
        assertNotNull(fingerprint1);

        // Change exports → ABI should change
        Files.writeString(
                sourceDir.resolve("module-info.java"), "module my.mod {\n  exports api;\n  exports impl;\n}\n");

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi2.initialize(listSources());

        while (!toCompile.isEmpty()) {
            compileFiles(toCompile, true);
            abi2.attachTo(lastTask);
            lastTask.call();
            toCompile = abi2.processRound();
        }
        abi2.finish();

        var state2 = IncrementalState.load(workDir.resolve("target/.incremental-state"));
        String fingerprint2 = state2.getAbiFingerprint("module:my.mod");
        assertNotNull(fingerprint2);
        assertNotEquals(fingerprint1, fingerprint2, "ABI fingerprint should change when exports are added");
    }

    @Test
    void moduleNameChangeTriggersFullRebuild() throws Exception {
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.old {\n  exports api;\n}\n");

        doFullBuildCycle(true);

        // Change the module name
        Files.writeString(sourceDir.resolve("module-info.java"), "module my.renamed {\n  exports api;\n}\n");

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> toCompile = abi2.initialize(listSources());
        assertFalse(abi2.isFullBuild(), "Should start as incremental");

        // First round: recompile module-info.java
        compileFiles(toCompile, true);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> cascade = abi2.processRound();

        // Module name change should force recompilation of all remaining files
        assertFalse(cascade.isEmpty(), "Module name change should trigger full rebuild");

        // Complete all rounds
        while (!cascade.isEmpty()) {
            compileFiles(cascade, true);
            abi2.attachTo(lastTask);
            lastTask.call();
            cascade = abi2.processRound();
        }
        abi2.finish();

        // State should reflect the new module name
        var state = IncrementalState.load(workDir.resolve("target/.incremental-state"));
        assertNotNull(state.getType("module:my.renamed"), "State should have new module name");
        assertNull(state.getType("module:my.old"), "State should not have old module name");
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

        var abi2 = new AbiIncrementalBuild(classesDir);
        Set<Path> round1 = abi2.initialize(listSources());
        assertEquals(1, round1.size(), "Only Constants changed");

        compileFiles(round1);
        abi2.attachTo(lastTask);
        lastTask.call();
        Set<Path> round2 = abi2.processRound();

        // Constants ABI changed (value is part of fingerprint) → Service should cascade
        assertFalse(round2.isEmpty(), "Constant value change should cascade to consumers");

        compileFiles(round2);
        abi2.attachTo(lastTask);
        lastTask.call();
        abi2.processRound();
        abi2.finish();
    }
}
