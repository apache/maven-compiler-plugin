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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeAnalyzerTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path outputDir;

    @Test
    void descriptorToReadableVoid() {
        assertEquals("void", BytecodeAnalyzer.descriptorToReadable("V"));
    }

    @Test
    void descriptorToReadableBoolean() {
        assertEquals("boolean", BytecodeAnalyzer.descriptorToReadable("Z"));
    }

    @Test
    void descriptorToReadableByte() {
        assertEquals("byte", BytecodeAnalyzer.descriptorToReadable("B"));
    }

    @Test
    void descriptorToReadableChar() {
        assertEquals("char", BytecodeAnalyzer.descriptorToReadable("C"));
    }

    @Test
    void descriptorToReadableShort() {
        assertEquals("short", BytecodeAnalyzer.descriptorToReadable("S"));
    }

    @Test
    void descriptorToReadableInt() {
        assertEquals("int", BytecodeAnalyzer.descriptorToReadable("I"));
    }

    @Test
    void descriptorToReadableLong() {
        assertEquals("long", BytecodeAnalyzer.descriptorToReadable("J"));
    }

    @Test
    void descriptorToReadableFloat() {
        assertEquals("float", BytecodeAnalyzer.descriptorToReadable("F"));
    }

    @Test
    void descriptorToReadableDouble() {
        assertEquals("double", BytecodeAnalyzer.descriptorToReadable("D"));
    }

    @Test
    void descriptorToReadableObject() {
        assertEquals("java.lang.String", BytecodeAnalyzer.descriptorToReadable("Ljava/lang/String;"));
    }

    @Test
    void descriptorToReadableArray() {
        assertEquals("int[]", BytecodeAnalyzer.descriptorToReadable("[I"));
        assertEquals("java.lang.String[]", BytecodeAnalyzer.descriptorToReadable("[Ljava/lang/String;"));
    }

    @Test
    void descriptorToReadableEmpty() {
        assertEquals("", BytecodeAnalyzer.descriptorToReadable(""));
    }

    @Test
    void parseParamsMultiple() {
        assertEquals("java.lang.String, int", BytecodeAnalyzer.parseParams("(Ljava/lang/String;I)V"));
    }

    @Test
    void parseParamsEmpty() {
        assertEquals("", BytecodeAnalyzer.parseParams("()V"));
    }

    @Test
    void parseReturnType() {
        assertEquals("java.lang.String", BytecodeAnalyzer.parseReturn("(I)Ljava/lang/String;"));
        assertEquals("void", BytecodeAnalyzer.parseReturn("()V"));
        assertEquals("int", BytecodeAnalyzer.parseReturn("()I"));
    }

    @Test
    void analyzePathProducesCorrectClassName() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir,
                "test",
                "Hello",
                "package test; public class Hello { public String greet() { return \"hi\"; } }");
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        Path classFile = outputDir.resolve("test/Hello.class");
        assertTrue(Files.exists(classFile));

        var analysis = BytecodeAnalyzer.analyze(classFile);
        assertEquals("test.Hello", analysis.className());
        assertNotNull(analysis.abiFingerprint());
        assertNotNull(analysis.abiCanonical());
    }

    @Test
    void analyzeBytesEquivalentToAnalyzePath() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir, "test", "Hello", "package test; public class Hello { public int value() { return 42; } }");
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        Path classFile = outputDir.resolve("test/Hello.class");
        var fromPath = BytecodeAnalyzer.analyze(classFile);
        var fromBytes = BytecodeAnalyzer.analyze(Files.readAllBytes(classFile));

        assertEquals(fromPath.className(), fromBytes.className());
        assertEquals(fromPath.abiFingerprint(), fromBytes.abiFingerprint());
        assertEquals(fromPath.abiCanonical(), fromBytes.abiCanonical());
        assertEquals(fromPath.referencedTypes(), fromBytes.referencedTypes());
    }
}
