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

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AbiExtractorTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path outputDir;

    private String fingerprintFor(String source) throws Exception {
        CompilerTestHelper.writeSource(sourceDir, "test", "Subject", source);
        Map<String, SourceFileAnalysis> results = CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);
        return results.get("test.Subject").abiFingerprint();
    }

    @Test
    void bodyOnlyChangeProducesSameFingerprint() throws Exception {
        String fp1 = fingerprintFor("package test; public class Subject { public int value() { return 1; } }");
        // Reset dirs for a clean compile
        sourceDir = sourceDir.resolveSibling("src2");
        outputDir = outputDir.resolveSibling("out2");
        String fp2 = fingerprintFor("package test; public class Subject { public int value() { return 42; } }");
        assertEquals(fp1, fp2, "Body-only change should not affect ABI fingerprint");
    }

    @Test
    void addingPublicMethodChangesFingerprint() throws Exception {
        String fp1 = fingerprintFor("package test; public class Subject { public int value() { return 1; } }");
        sourceDir = sourceDir.resolveSibling("src2");
        outputDir = outputDir.resolveSibling("out2");
        String fp2 = fingerprintFor(
                "package test; public class Subject { public int value() { return 1; } public String name() { return \"\"; } }");
        assertNotEquals(fp1, fp2, "Adding public method should change ABI fingerprint");
    }

    @Test
    void addingPrivateMethodDoesNotChangeFingerprint() throws Exception {
        String fp1 = fingerprintFor("package test; public class Subject { public int value() { return 1; } }");
        sourceDir = sourceDir.resolveSibling("src2");
        outputDir = outputDir.resolveSibling("out2");
        String fp2 = fingerprintFor(
                "package test; public class Subject { public int value() { return helper(); } private int helper() { return 1; } }");
        assertEquals(fp1, fp2, "Adding private method should not change ABI fingerprint");
    }

    @Test
    void changingConstantValueChangesFingerprint() throws Exception {
        String fp1 = fingerprintFor("package test; public class Subject { public static final int VALUE = 100; }");
        sourceDir = sourceDir.resolveSibling("src2");
        outputDir = outputDir.resolveSibling("out2");
        String fp2 = fingerprintFor("package test; public class Subject { public static final int VALUE = 200; }");
        assertNotEquals(fp1, fp2, "Changing constant value should change ABI fingerprint");
    }

    @Test
    void genericTypeParametersIncluded() throws Exception {
        String fp1 = fingerprintFor("package test; public class Subject<T> { public T get() { return null; } }");
        sourceDir = sourceDir.resolveSibling("src2");
        outputDir = outputDir.resolveSibling("out2");
        String fp2 = fingerprintFor(
                "package test; public class Subject<T extends Comparable<T>> { public T get() { return null; } }");
        assertNotEquals(fp1, fp2, "Changing type parameter bounds should change ABI fingerprint");
    }

    @Test
    void changingThrowsClauseChangesFingerprint() throws Exception {
        String fp1 = fingerprintFor("package test; public class Subject { public void run() { } }");
        sourceDir = sourceDir.resolveSibling("src2");
        outputDir = outputDir.resolveSibling("out2");
        String fp2 = fingerprintFor("package test; public class Subject { public void run() throws Exception { } }");
        assertNotEquals(fp1, fp2, "Changing throws clause should change ABI fingerprint");
    }

    @Test
    void changingSuperclassChangesFingerprint() throws Exception {
        CompilerTestHelper.writeSource(sourceDir, "test", "Base", "package test; public class Base { }");
        CompilerTestHelper.writeSource(sourceDir, "test", "Subject", "package test; public class Subject { }");
        Map<String, SourceFileAnalysis> r1 = CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);
        String fp1 = r1.get("test.Subject").abiFingerprint();

        Path sourceDir2 = sourceDir.resolveSibling("src2");
        Path outputDir2 = outputDir.resolveSibling("out2");
        CompilerTestHelper.writeSource(sourceDir2, "test", "Base", "package test; public class Base { }");
        CompilerTestHelper.writeSource(
                sourceDir2, "test", "Subject", "package test; public class Subject extends Base { }");
        Map<String, SourceFileAnalysis> r2 = CompilerTestHelper.compileAndAnalyze(sourceDir2, outputDir2);
        String fp2 = r2.get("test.Subject").abiFingerprint();

        assertNotEquals(fp1, fp2, "Changing superclass should change ABI fingerprint");
    }

    @Test
    void sealedClassPermitsAffectsFingerprint() throws Exception {
        Path src1 = sourceDir.resolveSibling("srcSealed1");
        Path out1 = outputDir.resolveSibling("outSealed1");
        CompilerTestHelper.writeSource(
                src1, "test", "Subject", "package test; public sealed class Subject permits A { }");
        CompilerTestHelper.writeSource(src1, "test", "A", "package test; public final class A extends Subject { }");
        Map<String, SourceFileAnalysis> r1 = CompilerTestHelper.compileAndAnalyze(src1, out1);
        String fp1 = r1.get("test.Subject").abiFingerprint();

        Path src2 = sourceDir.resolveSibling("srcSealed2");
        Path out2 = outputDir.resolveSibling("outSealed2");
        CompilerTestHelper.writeSource(
                src2, "test", "Subject", "package test; public sealed class Subject permits A, B { }");
        CompilerTestHelper.writeSource(src2, "test", "A", "package test; public final class A extends Subject { }");
        CompilerTestHelper.writeSource(src2, "test", "B", "package test; public final class B extends Subject { }");
        Map<String, SourceFileAnalysis> r2 = CompilerTestHelper.compileAndAnalyze(src2, out2);
        String fp2 = r2.get("test.Subject").abiFingerprint();

        assertNotEquals(fp1, fp2, "Adding a permitted subclass should change ABI fingerprint");
    }

    @Test
    void enumConstantReorderingAffectsFingerprint() throws Exception {
        String fp1 = fingerprintFor("package test; public enum Subject { RED, GREEN, BLUE }");
        sourceDir = sourceDir.resolveSibling("srcEnum2");
        outputDir = outputDir.resolveSibling("outEnum2");
        String fp2 = fingerprintFor("package test; public enum Subject { BLUE, GREEN, RED }");
        assertNotEquals(fp1, fp2, "Reordering enum constants should change ABI fingerprint (ordinal changes)");
    }
}
