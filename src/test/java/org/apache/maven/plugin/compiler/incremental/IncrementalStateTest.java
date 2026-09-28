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

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalStateTest {

    @TempDir
    Path tempDir;

    private IncrementalState createPopulatedState() {
        var state = new IncrementalState();
        state.setSourceHash("src/Model.java", "hash1");
        state.setSourceHash("src/Service.java", "hash2");
        state.setType(
                "com.Model",
                new IncrementalState.TypeInfo(
                        "src/Model.java", "abi1", Set.of(), Set.of(), Set.of("com.MyAnnotation")));
        state.setType(
                "com.Service",
                new IncrementalState.TypeInfo(
                        "src/Service.java", "abi2", Set.of("com.Model"), Set.of("com.Helper"), Set.of()));
        state.setExternalFingerprints(Map.of("ext.Lib", "extfp1"));
        state.setClasspathIdentities(Map.of("/path/to/lib.jar", "1234:5678"));
        return state;
    }

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        var state = createPopulatedState();
        Path file = tempDir.resolve("state.bin");
        state.save(file);

        var loaded = IncrementalState.load(file);
        assertNotNull(loaded);
        assertEquals("hash1", loaded.getSourceHash("src/Model.java"));
        assertEquals("hash2", loaded.getSourceHash("src/Service.java"));
        assertEquals("abi1", loaded.getAbiFingerprint("com.Model"));
        assertEquals("abi2", loaded.getAbiFingerprint("com.Service"));

        var serviceInfo = loaded.getType("com.Service");
        assertNotNull(serviceInfo);
        assertTrue(serviceInfo.signatureDeps().contains("com.Model"));
        assertTrue(serviceInfo.implementationDeps().contains("com.Helper"));

        var modelInfo = loaded.getType("com.Model");
        assertNotNull(modelInfo);
        assertTrue(modelInfo.annotationTypes().contains("com.MyAnnotation"));

        assertEquals("extfp1", loaded.getExternalFingerprints().get("ext.Lib"));
        assertEquals("1234:5678", loaded.getClasspathIdentities().get("/path/to/lib.jar"));
    }

    @Test
    void loadFromNonexistentFileReturnsNull() {
        assertNull(IncrementalState.load(tempDir.resolve("nonexistent")));
    }

    @Test
    void loadFromCorruptedFileReturnsNull() throws Exception {
        Path file = tempDir.resolve("corrupt.bin");
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(999); // unknown version
        }
        assertNull(IncrementalState.load(file));
    }

    @Test
    void loadFromTruncatedFileReturnsNull() throws Exception {
        Path file = tempDir.resolve("truncated.bin");
        Files.write(file, new byte[] {0, 0, 0, 4, 0, 0}); // version 4, then truncated
        assertNull(IncrementalState.load(file));
    }

    @Test
    void removeSourceRemovesBothHashAndTypes() {
        var state = createPopulatedState();
        state.removeSource("src/Model.java");

        assertNull(state.getSourceHash("src/Model.java"));
        assertNull(state.getType("com.Model"));
        // Other entries untouched
        assertEquals("hash2", state.getSourceHash("src/Service.java"));
        assertNotNull(state.getType("com.Service"));
    }

    @Test
    void removeTypesForSourceRemovesCorrectEntries() {
        var state = createPopulatedState();
        state.removeTypesForSource("src/Model.java");

        assertNull(state.getType("com.Model"));
        // Source hash remains
        assertEquals("hash1", state.getSourceHash("src/Model.java"));
        // Other types untouched
        assertNotNull(state.getType("com.Service"));
    }

    @Test
    void getExternalDependencies() {
        var state = createPopulatedState();
        Set<String> external = state.getExternalDependencies();
        // com.Helper is an impl dep of com.Service but has no TypeInfo entry
        assertTrue(external.contains("com.Helper"), "should include impl deps without TypeInfo");
        // com.Model is a sig dep of com.Service but has a TypeInfo entry
        assertTrue(!external.contains("com.Model"), "should not include types with TypeInfo");
    }

    @Test
    void getAllConsumersReturnsBothSigAndImpl() {
        var state = createPopulatedState();
        Set<String> consumers = state.getAllConsumers("com.Model");
        assertTrue(consumers.contains("com.Service"), "Service has Model as sig dep");

        Set<String> helperConsumers = state.getAllConsumers("com.Helper");
        assertTrue(helperConsumers.contains("com.Service"), "Service has Helper as impl dep");
    }

    @Test
    void copyCreatesIndependentState() {
        var original = createPopulatedState();
        var copy = original.copy();

        copy.setSourceHash("src/Extra.java", "hash3");
        copy.setType(
                "com.Extra", new IncrementalState.TypeInfo("src/Extra.java", "abi3", Set.of(), Set.of(), Set.of()));

        assertNull(original.getSourceHash("src/Extra.java"), "original should not be affected");
        assertNull(original.getType("com.Extra"), "original should not be affected");
    }

    @Test
    void getAllAbiFingerprints() {
        var state = createPopulatedState();
        Map<String, String> fingerprints = state.getAllAbiFingerprints();
        assertEquals("abi1", fingerprints.get("com.Model"));
        assertEquals("abi2", fingerprints.get("com.Service"));
        assertEquals(2, fingerprints.size());
    }

    @Test
    void getSourceFilesWithAnnotations() {
        var state = createPopulatedState();
        Set<String> files = state.getSourceFilesWithAnnotations(Set.of("com.MyAnnotation"));
        assertTrue(files.contains("src/Model.java"));
        assertEquals(1, files.size());
    }

    @Test
    void getAllAnnotationTypes() {
        var state = createPopulatedState();
        Set<String> annotations = state.getAllAnnotationTypes();
        assertTrue(annotations.contains("com.MyAnnotation"));
        assertEquals(1, annotations.size());
    }
}
