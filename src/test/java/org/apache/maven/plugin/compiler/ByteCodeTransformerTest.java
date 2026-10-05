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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.spi.ToolProvider;

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link ByteCodeTransformer#patchJdkModuleVersion}.
 *
 * <p>On JDK 24+, surefire prepends {@code META-INF/versions/24/} on the test classpath,
 * so this test exercises the {@code java.lang.classfile}-backed implementation.
 * On JDK 17-23, it exercises the ASM fallback.
 *
 * @see <a href="https://issues.apache.org/jira/browse/MCOMPILER-542">MCOMPILER-542</a>
 */
class ByteCodeTransformerTest {

    private static final Log LOG = mock(Log.class);

    @Test
    void returnsNullForNonModuleClass(@TempDir Path tempDir) throws Exception {
        byte[] bytes = compileRegularClass(tempDir);
        assertNull(
                ByteCodeTransformer.patchJdkModuleVersion(bytes, "21", LOG),
                "Regular class has no ModuleAttribute -> null");
    }

    @Test
    void patchesJdkRequiresVersionToTarget(@TempDir Path tempDir) throws Exception {
        byte[] bytes = compileModuleInfo(
                tempDir, "jdkRequires", "module com.example { requires java.base; requires java.logging; }");
        byte[] result = ByteCodeTransformer.patchJdkModuleVersion(bytes, "21", LOG);
        // javac on JDK 21+ emits requires version attributes for jdk modules.
        // If the compiler wrote version attributes, patchJdkModuleVersion must replace them with "21".
        if (result != null) {
            assertTrue(result.length > 0, "Patched result must be non-empty");
            Map<String, String> versions = readRequiresVersions(result);
            for (Map.Entry<String, String> entry : versions.entrySet()) {
                String mod = entry.getKey();
                if (mod.startsWith("java.") || mod.startsWith("jdk.")) {
                    assertEquals(
                            "21",
                            entry.getValue(),
                            "JDK module " + mod + " requires version should be patched to '21'");
                }
            }
        }
    }

    @Test
    void patchedBytesAreValidClassFile(@TempDir Path tempDir) throws Exception {
        byte[] bytes = compileModuleInfo(tempDir, "valid", "module com.example { requires java.base; }");
        byte[] result = ByteCodeTransformer.patchJdkModuleVersion(bytes, "21", LOG);
        if (result != null) {
            assertTrue(result.length >= 4, "Patched result must be at least 4 bytes");
            assertTrue(
                    result[0] == (byte) 0xCA
                            && result[1] == (byte) 0xFE
                            && result[2] == (byte) 0xBA
                            && result[3] == (byte) 0xBE,
                    "Patched result must start with CAFEBABE");
        }
    }

    @Test
    void doesNotThrowOnEmptyModule(@TempDir Path tempDir) throws Exception {
        byte[] bytes = compileModuleInfo(tempDir, "empty", "module com.example { }");
        ByteCodeTransformer.patchJdkModuleVersion(bytes, "21", LOG);
    }

    // --- helpers ---

    /**
     * Parse a module-info.class with ASM and return a map of {moduleName -> requiresVersion}
     * for all requires entries that have a version.
     */
    private static Map<String, String> readRequiresVersions(byte[] classBytes) {
        var versions = new HashMap<String, String>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public ModuleVisitor visitModule(String name, int access, String version) {
                                return new ModuleVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitRequire(String module, int access, String version) {
                                        if (version != null) {
                                            versions.put(module, version);
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return versions;
    }

    private static byte[] compileModuleInfo(Path base, String subdir, String content) throws IOException {
        Path dir = base.resolve(subdir);
        Files.createDirectories(dir);
        Path src = dir.resolve("module-info.java");
        Files.writeString(src, content);
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        javac(out, src);
        Path cf = out.resolve("module-info.class");
        assertTrue(Files.exists(cf), "module-info.class should be compiled");
        return Files.readAllBytes(cf);
    }

    private static byte[] compileRegularClass(Path base) throws IOException {
        Path src = base.resolve("Dummy.java");
        Files.writeString(src, "public class Dummy {}");
        Path out = base.resolve("out-dummy");
        Files.createDirectories(out);
        javac(out, src);
        Path cf = out.resolve("Dummy.class");
        assertTrue(Files.exists(cf), "Dummy.class should be compiled");
        return Files.readAllBytes(cf);
    }

    private static void javac(Path outputDir, Path... sources) throws IOException {
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        var args = new ArrayList<String>();
        args.add("-d");
        args.add(outputDir.toString());
        for (Path src : sources) {
            args.add(src.toString());
        }
        int rc = javac.run(System.out, System.err, args.toArray(new String[0]));
        if (rc != 0) {
            throw new IOException("javac failed on " + Arrays.toString(sources));
        }
    }
}
