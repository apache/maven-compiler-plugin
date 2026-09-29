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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persistent state for incremental compilation, storing per-source-file content
 * hashes and per-type metadata (ABI fingerprint, signature dependencies, and
 * implementation dependencies).
 *
 * <p>Serialized as a compact binary format via {@link java.io.DataOutputStream}
 * and stored alongside the class output as {@code .incremental-state}. The state
 * enables the incremental engine to detect which files changed, whether their
 * ABI is affected, and which consumers need recompilation.
 *
 * <p>Consumer lookup methods ({@link #getSignatureConsumers},
 * {@link #getImplementationConsumers}, {@link #getAllConsumers}) support the
 * cascade logic: signature consumers are followed transitively (their ABI may
 * change), while implementation consumers are recompiled directly but do not
 * cascade further.
 *
 * @see AbiIncrementalBuild
 */
public class IncrementalState {

    private static final int VERSION = 5;

    private final Map<String, String> sourceHashes = new LinkedHashMap<>();
    private final Map<String, Long> sourceMtimes = new LinkedHashMap<>();
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    private final Map<String, String> externalFingerprints = new LinkedHashMap<>();
    private final Map<String, String> classpathIdentities = new LinkedHashMap<>();

    public record TypeInfo(
            String sourceFile,
            String abiFingerprint,
            Set<String> signatureDeps,
            Set<String> implementationDeps,
            Set<String> annotationTypes) {}

    public String getSourceHash(String path) {
        return sourceHashes.get(path);
    }

    public Map<String, String> getSourceHashes() {
        return Collections.unmodifiableMap(sourceHashes);
    }

    public TypeInfo getType(String qualifiedName) {
        return types.get(qualifiedName);
    }

    public Map<String, TypeInfo> getTypes() {
        return Collections.unmodifiableMap(types);
    }

    public String getAbiFingerprint(String qualifiedName) {
        TypeInfo info = types.get(qualifiedName);
        return info != null ? info.abiFingerprint() : null;
    }

    public void setSourceHash(String path, String hash) {
        sourceHashes.put(path, hash);
    }

    /**
     * Returns the last-modified time (in milliseconds) recorded for the given
     * source file in the previous build, or {@link OptionalLong#empty()} if not
     * stored (e.g. first build or state format upgrade).
     */
    public OptionalLong getSourceMtime(String path) {
        Long mtime = sourceMtimes.get(path);
        return mtime != null ? OptionalLong.of(mtime) : OptionalLong.empty();
    }

    public void setSourceMtime(String path, long mtime) {
        sourceMtimes.put(path, mtime);
    }

    public void setType(String qualifiedName, TypeInfo info) {
        types.put(qualifiedName, info);
    }

    public void removeSource(String path) {
        sourceHashes.remove(path);
        removeTypesForSource(path);
    }

    /**
     * Removes all type entries associated with the given source file.
     * Used to clear stale types before recompilation — a source file
     * that previously defined types A and B but now only defines A
     * would otherwise retain phantom type B in the state.
     */
    public void removeTypesForSource(String sourceFile) {
        types.entrySet().removeIf(e -> e.getValue().sourceFile().equals(sourceFile));
    }

    public List<String> getTypesFromSource(String sourceFile) {
        return types.entrySet().stream()
                .filter(e -> e.getValue().sourceFile().equals(sourceFile))
                .map(Map.Entry::getKey)
                .toList();
    }

    public Set<String> getSignatureConsumers(String type) {
        var result = new TreeSet<String>();
        for (var entry : types.entrySet()) {
            if (entry.getValue().signatureDeps().contains(type)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public Set<String> getImplementationConsumers(String type) {
        var result = new TreeSet<String>();
        for (var entry : types.entrySet()) {
            if (entry.getValue().implementationDeps().contains(type)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public Set<String> getAllConsumers(String type) {
        var result = getSignatureConsumers(type);
        result.addAll(getImplementationConsumers(type));
        return result;
    }

    public String sourceFileFor(String typeName) {
        TypeInfo info = types.get(typeName);
        return info != null ? info.sourceFile() : null;
    }

    public Map<String, String> getExternalFingerprints() {
        return Collections.unmodifiableMap(externalFingerprints);
    }

    public void setExternalFingerprints(Map<String, String> fingerprints) {
        externalFingerprints.clear();
        externalFingerprints.putAll(fingerprints);
    }

    public Map<String, String> getClasspathIdentities() {
        return Collections.unmodifiableMap(classpathIdentities);
    }

    public void setClasspathIdentities(Map<String, String> identities) {
        classpathIdentities.clear();
        classpathIdentities.putAll(identities);
    }

    /**
     * Returns the source files of all types carrying any of the given annotations.
     */
    public Set<String> getSourceFilesWithAnnotations(Set<String> annotationTypes) {
        var result = new TreeSet<String>();
        for (TypeInfo info : types.values()) {
            for (String ann : info.annotationTypes()) {
                if (annotationTypes.contains(ann)) {
                    result.add(info.sourceFile());
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Returns all annotation types used across all types in this module.
     */
    public Set<String> getAllAnnotationTypes() {
        var result = new TreeSet<String>();
        for (TypeInfo info : types.values()) {
            result.addAll(info.annotationTypes());
        }
        return result;
    }

    /**
     * Returns the set of type names that appear in dependency sets but are not
     * defined in this module (no {@link TypeInfo} entry). These are types from
     * the classpath — other reactor modules or external libraries.
     */
    public Set<String> getExternalDependencies() {
        var external = new TreeSet<String>();
        for (TypeInfo info : types.values()) {
            for (String dep : info.signatureDeps()) {
                if (!types.containsKey(dep)) {
                    external.add(dep);
                }
            }
            for (String dep : info.implementationDeps()) {
                if (!types.containsKey(dep)) {
                    external.add(dep);
                }
            }
        }
        return external;
    }

    /**
     * Returns the current ABI fingerprints for all types in this module,
     * suitable for writing to an {@link AbiManifest}.
     */
    public Map<String, String> getAllAbiFingerprints() {
        var result = new LinkedHashMap<String, String>();
        for (var entry : types.entrySet()) {
            result.put(entry.getKey(), entry.getValue().abiFingerprint());
        }
        return result;
    }

    public IncrementalState copy() {
        var copy = new IncrementalState();
        copy.sourceHashes.putAll(this.sourceHashes);
        copy.sourceMtimes.putAll(this.sourceMtimes);
        copy.types.putAll(this.types);
        copy.externalFingerprints.putAll(this.externalFingerprints);
        copy.classpathIdentities.putAll(this.classpathIdentities);
        return copy;
    }

    public static IncrementalState from(Map<String, String> sourceHashes, Map<String, SourceFileAnalysis> results) {
        var state = new IncrementalState();
        state.sourceHashes.putAll(sourceHashes);
        for (var result : results.values()) {
            state.types.put(
                    result.qualifiedName(),
                    new TypeInfo(
                            result.sourceFile(),
                            result.abiFingerprint(),
                            result.signatureDeps(),
                            result.implementationDeps(),
                            result.annotationTypes()));
        }
        return state;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(VERSION);
            out.writeInt(sourceHashes.size());
            for (var entry : sourceHashes.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
                // v5: mtime per source file (0 if not recorded)
                Long mtime = sourceMtimes.get(entry.getKey());
                out.writeLong(mtime != null ? mtime : 0L);
            }
            out.writeInt(types.size());
            for (var entry : types.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue().sourceFile());
                out.writeUTF(entry.getValue().abiFingerprint());
                writeStringSet(out, entry.getValue().signatureDeps());
                writeStringSet(out, entry.getValue().implementationDeps());
                // v4: annotation types
                writeStringSet(out, entry.getValue().annotationTypes());
            }
            // v2: external fingerprints
            writeStringMap(out, externalFingerprints);
            // v3: classpath entry identities
            writeStringMap(out, classpathIdentities);
        }
    }

    public static IncrementalState load(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int version = in.readInt();
            if (version < 1 || version > VERSION) {
                return null;
            }

            var state = new IncrementalState();
            int sourceCount = in.readInt();
            for (int i = 0; i < sourceCount; i++) {
                String path = in.readUTF();
                String hash = in.readUTF();
                state.sourceHashes.put(path, hash);
                // v5: mtime per source file
                if (version >= 5) {
                    long mtime = in.readLong();
                    if (mtime != 0L) {
                        state.sourceMtimes.put(path, mtime);
                    }
                }
            }
            int typeCount = in.readInt();
            for (int i = 0; i < typeCount; i++) {
                String name = in.readUTF();
                String sourceFile = in.readUTF();
                String abi = in.readUTF();
                Set<String> sigDeps = readStringSet(in);
                Set<String> implDeps = readStringSet(in);
                Set<String> annotTypes = version >= 4 ? readStringSet(in) : Set.of();
                state.types.put(name, new TypeInfo(sourceFile, abi, sigDeps, implDeps, annotTypes));
            }
            if (version >= 2) {
                readStringMap(in, state.externalFingerprints);
            }
            if (version >= 3) {
                readStringMap(in, state.classpathIdentities);
            }
            return state;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeStringSet(DataOutputStream out, Set<String> set) throws IOException {
        out.writeInt(set.size());
        for (String s : set) {
            out.writeUTF(s);
        }
    }

    private static Set<String> readStringSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        var set = new TreeSet<String>();
        for (int i = 0; i < count; i++) {
            set.add(in.readUTF());
        }
        return set;
    }

    private static void writeStringMap(DataOutputStream out, Map<String, String> map) throws IOException {
        out.writeInt(map.size());
        for (var entry : map.entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeUTF(entry.getValue());
        }
    }

    private static void readStringMap(DataInputStream in, Map<String, String> target) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            target.put(in.readUTF(), in.readUTF());
        }
    }
}
