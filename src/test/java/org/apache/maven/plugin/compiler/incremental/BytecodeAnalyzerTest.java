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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals(fromPath.signatureTypes(), fromBytes.signatureTypes());
        assertEquals(fromPath.implementationTypes(), fromBytes.implementationTypes());
    }

    @Test
    void genericTypeChangeAffectsFingerprint() throws Exception {
        // Compile with List<String>
        CompilerTestHelper.writeSource(sourceDir, "test", "Generics", """
                package test;
                public class Generics {
                    public java.util.List<String> getNames() { return null; }
                    public java.util.Map<String, Integer> getMap() { return null; }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);
        var analysis1 = BytecodeAnalyzer.analyze(outputDir.resolve("test/Generics.class"));

        // Recompile with List<Integer> — erased descriptor is identical,
        // but generic signature differs
        Path srcFile = sourceDir.resolve("test/Generics.java");
        Files.writeString(srcFile, """
                package test;
                public class Generics {
                    public java.util.List<Integer> getNames() { return null; }
                    public java.util.Map<String, Integer> getMap() { return null; }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);
        var analysis2 = BytecodeAnalyzer.analyze(outputDir.resolve("test/Generics.class"));

        assertNotEquals(
                analysis1.abiFingerprint(),
                analysis2.abiFingerprint(),
                "Changing List<String> to List<Integer> should change bytecode ABI fingerprint");
        assertTrue(analysis2.abiCanonical().contains("<sig:"), "Canonical form should include generic signatures");
    }

    @Test
    void classLevelGenericSignatureAffectsFingerprint() throws Exception {
        CompilerTestHelper.writeSource(sourceDir, "test", "Box", """
                package test;
                public class Box<T> {
                    public T get() { return null; }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);
        var analysis1 = BytecodeAnalyzer.analyze(outputDir.resolve("test/Box.class"));

        Files.writeString(sourceDir.resolve("test/Box.java"), """
                package test;
                public class Box<T extends Comparable<T>> {
                    public T get() { return null; }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);
        var analysis2 = BytecodeAnalyzer.analyze(outputDir.resolve("test/Box.class"));

        assertNotEquals(
                analysis1.abiFingerprint(),
                analysis2.abiFingerprint(),
                "Changing type parameter bounds should change bytecode ABI fingerprint");
    }

    /**
     * When running on JDK 24+, {@link BytecodeAnalyzer} uses the {@code ClassfileClassAnalyzer}.
     * Verifies idempotency: analyzing the same bytes twice produces identical results.
     * Also verifies that sig/impl split is non-trivially populated for a class with
     * user-defined type cross-references.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void classfileAnalyzerIsIdempotent() throws Exception {
        // Write two classes so Subject references UserType (a non-JDK type → sig dependency)
        CompilerTestHelper.writeSource(sourceDir, "test", "UserType", "package test; public class UserType {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "Subject", """
                package test;
                public class Subject implements Runnable {
                    public static final String CONST = "hello";
                    private int secret = 42;
                    public UserType getUser() { return new UserType(); }
                    @Override public void run() { getUser(); }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        byte[] bytes = Files.readAllBytes(outputDir.resolve("test/Subject.class"));

        var r1 = BytecodeAnalyzer.analyze(bytes);
        var r2 = BytecodeAnalyzer.analyze(bytes);

        assertEquals(r1.className(), r2.className(), "className");
        assertEquals(r1.abiCanonical(), r2.abiCanonical(), "abiCanonical");
        assertEquals(r1.abiFingerprint(), r2.abiFingerprint(), "abiFingerprint");
        assertEquals(r1.signatureTypes(), r2.signatureTypes(), "signatureTypes");
        assertEquals(r1.implementationTypes(), r2.implementationTypes(), "implementationTypes");
        // Subject.getUser() returns UserType → UserType is a non-JDK signature dependency
        assertTrue(r1.signatureTypes().contains("test.UserType"), "test.UserType should be a signature type ref");
    }
}
