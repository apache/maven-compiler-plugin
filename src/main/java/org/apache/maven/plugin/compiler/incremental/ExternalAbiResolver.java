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

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves ABI fingerprints for types defined outside the current compilation
 * module — in other reactor modules or in external library JARs.
 *
 * <p>Resolution uses a three-strategy cascade:
 * <ol>
 *   <li><b>Manifest (option 1):</b> If a classpath directory contains an
 *       {@value AbiManifest#FILENAME} file, fingerprints are read from it.
 *       This is the fast path for reactor modules compiled with javaci.</li>
 *   <li><b>Reactor metadata (option 2):</b> The caller can mark specific
 *       classpath entries as reactor modules via {@code reactorModulePaths}.
 *       These directories are scanned for class files when no manifest is
 *       present.</li>
 *   <li><b>Bytecode fallback (option 3):</b> For any type not resolved above,
 *       the resolver searches all classpath entries (directories and JARs) and
 *       computes the ABI fingerprint from bytecode via {@link BytecodeAnalyzer}.
 *       This works with any dependency, including third-party JARs that were
 *       not built with javaci.</li>
 * </ol>
 *
 * <p>JAR entries are cached by identity (path + size + last-modified-time).
 * When a JAR has not changed since the last build and a stored fingerprint
 * exists for the requested type, the stored fingerprint is reused without
 * opening the JAR.
 *
 * @see AbiManifest
 * @see AbiIncrementalBuild
 */
public class ExternalAbiResolver {

    private static final Logger LOGGER = Logger.getLogger(ExternalAbiResolver.class.getName());

    private final List<Path> classpathEntries;
    private final Set<Path> reactorModulePaths;
    private Map<Path, Map<String, String>> manifestCache;

    private Map<String, String> previousFingerprints = Map.of();
    private Set<String> unchangedJars = Set.of();

    public ExternalAbiResolver(List<Path> classpathEntries, Set<Path> reactorModulePaths) {
        this.classpathEntries = classpathEntries != null ? classpathEntries : List.of();
        this.reactorModulePaths = reactorModulePaths != null ? reactorModulePaths : Set.of();
    }

    /**
     * Configures JAR caching. Fingerprints for types found in unchanged JARs
     * are reused from the previous build without re-opening the JAR.
     *
     * @param previousFingerprints external fingerprints from the previous build
     * @param storedJarIdentities  JAR identities ({@code path -> size:mtime})
     *                             from the previous build
     */
    public void setCachedState(Map<String, String> previousFingerprints, Map<String, String> storedJarIdentities) {
        this.previousFingerprints = previousFingerprints != null ? previousFingerprints : Map.of();
        this.unchangedJars = computeUnchangedJars(storedJarIdentities);
    }

    /**
     * Resolves ABI fingerprints for the given set of type names.
     *
     * @param typeNames fully qualified type names to resolve
     * @return map from type name to ABI fingerprint (types not found on the
     *         classpath are omitted)
     */
    public Map<String, String> resolve(Set<String> typeNames) {
        if (typeNames.isEmpty()) {
            return Map.of();
        }

        var result = new HashMap<String, String>();
        var remaining = new LinkedHashSet<>(typeNames);

        // Strategy 1 & 2: read from manifests in directory classpath entries
        resolveFromManifests(remaining, result);
        remaining.removeAll(result.keySet());

        // Strategy 3: compute from bytecode for anything still unresolved
        if (!remaining.isEmpty()) {
            resolveFromBytecode(remaining, result);
        }

        return result;
    }

    /**
     * Computes identity strings for all JAR entries on the classpath.
     * The identity is {@code size:lastModifiedMillis}.
     */
    public Map<String, String> computeCurrentJarIdentities() {
        var identities = new LinkedHashMap<String, String>();
        for (Path entry : classpathEntries) {
            if (isJarFile(entry) && Files.exists(entry)) {
                String id = jarIdentity(entry);
                if (id != null) {
                    identities.put(entry.toString(), id);
                }
            }
        }
        return identities;
    }

    private void resolveFromManifests(Set<String> typeNames, Map<String, String> result) {
        if (manifestCache == null) {
            manifestCache = new HashMap<>();
            for (Path entry : classpathEntries) {
                if (Files.isDirectory(entry)) {
                    // Manifest is in the build directory (parent of classes dir)
                    Path parent = entry.getParent();
                    if (parent != null) {
                        Path manifestFile = parent.resolve(AbiManifest.FILENAME);
                        Map<String, String> manifest = AbiManifest.read(manifestFile);
                        if (!manifest.isEmpty()) {
                            manifestCache.put(entry, manifest);
                        }
                    }
                }
            }
        }

        for (String typeName : typeNames) {
            for (var manifest : manifestCache.values()) {
                String fp = manifest.get(typeName);
                if (fp != null) {
                    result.put(typeName, fp);
                    break;
                }
            }
        }
    }

    private void resolveFromBytecode(Set<String> typeNames, Map<String, String> result) {
        var remaining = new LinkedHashSet<>(typeNames);
        for (Path entry : classpathEntries) {
            if (remaining.isEmpty()) {
                break;
            }
            try {
                if (Files.isDirectory(entry)) {
                    resolveFromDirectory(entry, remaining, result);
                } else if (isJarFile(entry) && Files.exists(entry)) {
                    resolveFromJar(entry, remaining, result);
                }
            } catch (IOException e) {
                // Classpath entry unreadable — skip to next
                LOGGER.log(Level.FINE, "Skipping unreadable classpath entry: " + entry, e);
            }
        }
    }

    private void resolveFromDirectory(Path dir, Set<String> remaining, Map<String, String> result) throws IOException {
        for (var it = remaining.iterator(); it.hasNext(); ) {
            String typeName = it.next();
            Path classFile = dir.resolve(typeName.replace('.', '/') + ".class");
            if (Files.exists(classFile)) {
                result.put(typeName, BytecodeAnalyzer.analyze(classFile).abiFingerprint());
                it.remove();
            }
        }
    }

    private void resolveFromJar(Path jarPath, Set<String> remaining, Map<String, String> result) throws IOException {
        if (unchangedJars.contains(jarPath.toString())) {
            // JAR unchanged — reuse stored fingerprints where available
            for (var it = remaining.iterator(); it.hasNext(); ) {
                String typeName = it.next();
                if (previousFingerprints.containsKey(typeName)) {
                    result.put(typeName, previousFingerprints.get(typeName));
                    it.remove();
                }
            }
            return;
        }
        // JAR changed or new — open once, resolve all remaining types
        try (var fs = FileSystems.newFileSystem(jarPath)) {
            for (var it = remaining.iterator(); it.hasNext(); ) {
                String typeName = it.next();
                Path classFile = fs.getPath(typeName.replace('.', '/') + ".class");
                if (Files.exists(classFile)) {
                    byte[] bytes = Files.readAllBytes(classFile);
                    result.put(typeName, BytecodeAnalyzer.analyze(bytes).abiFingerprint());
                    it.remove();
                }
            }
        }
    }

    private Set<String> computeUnchangedJars(Map<String, String> storedIdentities) {
        if (storedIdentities == null || storedIdentities.isEmpty()) {
            return Set.of();
        }
        var unchanged = new HashSet<String>();
        for (Path entry : classpathEntries) {
            if (isJarFile(entry) && Files.exists(entry)) {
                String currentId = jarIdentity(entry);
                String storedId = storedIdentities.get(entry.toString());
                if (currentId != null && currentId.equals(storedId)) {
                    unchanged.add(entry.toString());
                }
            }
        }
        return unchanged;
    }

    static String jarIdentity(Path jarPath) {
        try {
            var attrs = Files.readAttributes(jarPath, BasicFileAttributes.class);
            return attrs.size() + ":" + attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isJarFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }
}
