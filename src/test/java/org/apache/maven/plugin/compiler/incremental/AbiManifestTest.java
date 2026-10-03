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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbiManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndReadRoundTrip() throws Exception {
        Path file = tempDir.resolve("manifest");
        var data = Map.of("com.Foo", "abc123", "com.Bar", "def456");
        AbiManifest.write(file, data);

        var loaded = AbiManifest.read(file);
        assertEquals(2, loaded.size());
        assertEquals("abc123", loaded.get("com.Foo"));
        assertEquals("def456", loaded.get("com.Bar"));
    }

    @Test
    void writtenFileStartsWithVersionHeader() throws Exception {
        Path file = tempDir.resolve("manifest");
        AbiManifest.write(file, Map.of("com.Foo", "abc123"));

        List<String> lines = Files.readAllLines(file);
        assertEquals(AbiManifest.VERSION_HEADER, lines.get(0));
    }

    @Test
    void readNonexistentFileReturnsEmptyMap() {
        var result = AbiManifest.read(tempDir.resolve("nonexistent"));
        assertTrue(result.isEmpty());
    }

    @Test
    void readUnsupportedVersionReturnsEmptyMap() throws Exception {
        Path file = tempDir.resolve("manifest");
        Files.writeString(file, "#javaci:v99\ncom.Foo=abc123\n");

        var result = AbiManifest.read(file);
        assertTrue(result.isEmpty(), "Unsupported version should return empty map");
    }

    @Test
    void readLegacyFileWithoutVersionHeaderStillWorks() throws Exception {
        Path file = tempDir.resolve("manifest");
        Files.writeString(file, "com.Foo=abc123\ncom.Bar=def456\n");

        var result = AbiManifest.read(file);
        assertEquals(2, result.size());
        assertEquals("abc123", result.get("com.Foo"));
    }

    @Test
    void entriesAreSortedLexicographically() throws Exception {
        Path file = tempDir.resolve("manifest");
        AbiManifest.write(file, Map.of("z.Last", "fp1", "a.First", "fp2", "m.Middle", "fp3"));

        List<String> lines = Files.readAllLines(file);
        // Skip header line
        var entries = new ArrayList<String>();
        for (String line : lines) {
            if (!line.startsWith("#") && !line.isEmpty()) {
                entries.add(line);
            }
        }
        assertEquals("a.First=fp2", entries.get(0));
        assertEquals("m.Middle=fp3", entries.get(1));
        assertEquals("z.Last=fp1", entries.get(2));
    }

    @Test
    void commentsAndBlankLinesAreSkipped() throws Exception {
        Path file = tempDir.resolve("manifest");
        Files.writeString(file, "#javaci:v1\n# a comment\n\ncom.Foo=abc123\n  \n# another\ncom.Bar=def456\n");

        var result = AbiManifest.read(file);
        assertEquals(2, result.size());
        assertEquals("abc123", result.get("com.Foo"));
        assertEquals("def456", result.get("com.Bar"));
    }
}
