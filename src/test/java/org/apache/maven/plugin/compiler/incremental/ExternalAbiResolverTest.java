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
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalAbiResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesFromManifestInParentOfDirectoryEntry() throws Exception {
        // Simulate: classpath entry = target/classes, manifest = target/abi-fingerprints
        Path targetDir = tempDir.resolve("target");
        Path classesDir = targetDir.resolve("classes");
        Files.createDirectories(classesDir);
        AbiManifest.write(targetDir.resolve(AbiManifest.FILENAME), Map.of("ext.Model", "fp123"));

        var resolver = new ExternalAbiResolver(List.of(classesDir), null);
        var result = resolver.resolve(Set.of("ext.Model"));
        assertEquals("fp123", result.get("ext.Model"));
    }

    @Test
    void fallsBackToBytecodeWhenNoManifest() throws Exception {
        // Compile a class into a directory, then resolve from it
        Path sourceDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        CompilerTestHelper.writeSource(
                sourceDir, "ext", "Simple", "package ext; public class Simple { public int val() { return 0; } }");
        CompilerTestHelper.compileAndAnalyze(sourceDir, classesDir);

        var resolver = new ExternalAbiResolver(List.of(classesDir), null);
        var result = resolver.resolve(Set.of("ext.Simple"));
        assertNotNull(result.get("ext.Simple"), "should resolve from bytecode");
        assertFalse(result.get("ext.Simple").isEmpty());
    }

    @Test
    void jarCachingReusesStoredFingerprint() throws Exception {
        // Create a JAR with a class
        Path jarPath = createJarWithClass(tempDir, "cached", "CachedClass");

        // First resolve — no cache
        var resolver1 = new ExternalAbiResolver(List.of(jarPath), null);
        var result1 = resolver1.resolve(Set.of("cached.CachedClass"));
        String originalFp = result1.get("cached.CachedClass");
        assertNotNull(originalFp);

        // Second resolve — with cache from first run
        var identities = resolver1.computeCurrentJarIdentities();
        var resolver2 = new ExternalAbiResolver(List.of(jarPath), null);
        resolver2.setCachedState(Map.of("cached.CachedClass", originalFp), identities);

        var result2 = resolver2.resolve(Set.of("cached.CachedClass"));
        assertEquals(originalFp, result2.get("cached.CachedClass"), "should reuse cached fingerprint");
    }

    @Test
    void typesNotOnClasspathOmittedFromResult() {
        var resolver = new ExternalAbiResolver(List.of(), null);
        var result = resolver.resolve(Set.of("nonexistent.Type"));
        assertTrue(result.isEmpty());
    }

    @Test
    void resolvesFromJarClasspathEntry() throws Exception {
        Path jarPath = createJarWithClass(tempDir, "jartype", "JarType");

        var resolver = new ExternalAbiResolver(List.of(jarPath), null);
        var result = resolver.resolve(Set.of("jartype.JarType"));
        assertNotNull(result.get("jartype.JarType"), "should resolve type from JAR");
    }

    @Test
    void emptyTypeNamesReturnsEmptyMap() {
        var resolver = new ExternalAbiResolver(List.of(), null);
        var result = resolver.resolve(Set.of());
        assertTrue(result.isEmpty());
    }

    private Path createJarWithClass(Path baseDir, String packageName, String className) throws Exception {
        Path sourceDir = baseDir.resolve("jar-src");
        Path classesDir = baseDir.resolve("jar-classes");
        CompilerTestHelper.writeSource(
                sourceDir,
                packageName,
                className,
                "package " + packageName + "; public class " + className + " { public int val() { return 0; } }");
        CompilerTestHelper.compileAndAnalyze(sourceDir, classesDir);

        Path jarPath = baseDir.resolve("test.jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            Path classFile = classesDir.resolve(packageName + "/" + className + ".class");
            jos.putNextEntry(new JarEntry(packageName + "/" + className + ".class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jarPath;
    }
}
