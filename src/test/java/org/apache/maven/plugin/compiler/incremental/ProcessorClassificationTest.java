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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessorClassificationTest {

    @TempDir
    Path tempDir;

    @Test
    void readsFromJavacMetaInf() throws Exception {
        Path dir = tempDir.resolve("proc");
        Path metaInf = dir.resolve("META-INF/maven/compiler");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"), "com.MyProcessor,ISOLATING\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.MyProcessor"));
    }

    @Test
    void readsFromGradleMetaInf() throws Exception {
        Path dir = tempDir.resolve("proc");
        Path metaInf = dir.resolve("META-INF/gradle");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"), "com.GradleProc,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(ProcessorType.AGGREGATING, pc.classify("com.GradleProc"));
    }

    @Test
    void readsFromJar() throws Exception {
        Path jarPath = tempDir.resolve("processor.jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("META-INF/maven/compiler/incremental.annotation.processors"));
            jos.write("com.JarProcessor,ISOLATING\n".getBytes());
            jos.closeEntry();
        }

        var pc = new ProcessorClassification(List.of(jarPath));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.JarProcessor"));
    }

    @Test
    void unknownProcessorReturnsUnknown() {
        var pc = new ProcessorClassification(List.of());
        assertEquals(ProcessorType.UNKNOWN, pc.classify("com.NotRegistered"));
    }

    @Test
    void isolatingClassificationWorks() throws Exception {
        Path dir = tempDir.resolve("proc");
        Path metaInf = dir.resolve("META-INF/maven/compiler");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"), "com.Proc1,ISOLATING\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.Proc1"));
    }

    @Test
    void aggregatingClassificationWorks() throws Exception {
        Path dir = tempDir.resolve("proc");
        Path metaInf = dir.resolve("META-INF/maven/compiler");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"), "com.Proc1,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(ProcessorType.AGGREGATING, pc.classify("com.Proc1"));
    }

    @Test
    void javacTakesPrecedenceOverGradle() throws Exception {
        Path dir = tempDir.resolve("proc");
        Files.createDirectories(dir.resolve("META-INF/maven/compiler"));
        Files.createDirectories(dir.resolve("META-INF/gradle"));
        Files.writeString(
                dir.resolve("META-INF/maven/compiler/incremental.annotation.processors"), "com.Proc,ISOLATING\n");
        Files.writeString(dir.resolve("META-INF/gradle/incremental.annotation.processors"), "com.Proc,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.Proc"), "maven/compiler should take precedence");
    }

    @Test
    void worstCaseReturnsUnknownIfAnyUnknown() throws Exception {
        Path dir = tempDir.resolve("proc");
        Files.createDirectories(dir.resolve("META-INF/maven/compiler"));
        Files.writeString(
                dir.resolve("META-INF/maven/compiler/incremental.annotation.processors"), "com.Known,ISOLATING\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(
                ProcessorType.UNKNOWN,
                pc.worstCase(List.of("com.Known", "com.NotRegistered")),
                "Should be UNKNOWN if any processor is unknown");
    }

    @Test
    void worstCaseReturnsAggregatingOverIsolating() throws Exception {
        Path dir = tempDir.resolve("proc");
        Files.createDirectories(dir.resolve("META-INF/maven/compiler"));
        Files.writeString(
                dir.resolve("META-INF/maven/compiler/incremental.annotation.processors"),
                "com.Iso,ISOLATING\ncom.Agg,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(
                ProcessorType.AGGREGATING,
                pc.worstCase(List.of("com.Iso", "com.Agg")),
                "Should be AGGREGATING when mixed");
    }

    @Test
    void commentsAndBlankLinesIgnored() throws Exception {
        Path dir = tempDir.resolve("proc");
        Files.createDirectories(dir.resolve("META-INF/maven/compiler"));
        Files.writeString(
                dir.resolve("META-INF/maven/compiler/incremental.annotation.processors"),
                "# comment\n\ncom.Proc,ISOLATING\n# another comment\n");

        var pc = new ProcessorClassification(List.of(dir));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.Proc"));
    }

    @Test
    void nullProcessorPathHandled() {
        var pc = new ProcessorClassification(null);
        assertEquals(ProcessorType.UNKNOWN, pc.classify("com.Anything"));
    }

    @Test
    void emptyListReturnsIsolatingForWorstCase() {
        var pc = new ProcessorClassification(List.of());
        assertEquals(ProcessorType.ISOLATING, pc.worstCase(List.of()));
    }
}
