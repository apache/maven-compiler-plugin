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
 * hashes and per-type metadata.
 *
 * <p>Two levels of type metadata are supported, corresponding to the two incremental
 * strategies:
 * <ul>
 *   <li>{@link GraphTypeInfo} — used by the {@code graph} strategy. Stores class-level
 *       dependency references (all types referenced in the classfile constant pool), without
 *       distinguishing signature from implementation dependencies, and without ABI fingerprints.</li>
 *   <li>{@link AbiTypeInfo} — used by the {@code abi} strategy. Extends {@code GraphTypeInfo}
 *       with fine-grained {@code signatureDeps}/{@code implementationDeps} sets and an ABI
 *       fingerprint, enabling more precise cascade decisions and cross-module manifest writing.</li>
 * </ul>
 *
 * <p>Serialized as a compact binary format via {@link DataOutputStream} and stored alongside
 * the class output as {@code .incremental-state}. A one-byte tag discriminates the two
 * {@code TypeInfo} variants: {@code 0} = {@code GraphTypeInfo}, {@code 1} = {@code AbiTypeInfo}.
 *
 * <p>Consumer lookup methods ({@link #getSignatureConsumers}, {@link #getImplementationConsumers},
 * {@link #getAllConsumers}) support the cascade logic. For {@code GraphTypeInfo}, all consumers
 * are stored as "signature consumers" (single index, no distinction). For {@code AbiTypeInfo},
 * signature and implementation consumers are indexed separately.
 *
 * @see GraphIncrementalBuild
 */
public class IncrementalState {

    private static final int VERSION = 2;

    /** Serialization tag for {@link GraphTypeInfo}. */
    private static final byte TAG_GRAPH = 0;

    /** Serialization tag for {@link AbiTypeInfo}. */
    private static final byte TAG_ABI = 1;

    private final Map<String, String> sourceHashes = new LinkedHashMap<>();
    private final Map<String, Long> sourceMtimes = new LinkedHashMap<>();
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    private final Map<String, String> externalFingerprints = new LinkedHashMap<>();
    private final Map<String, String> classpathIdentities = new LinkedHashMap<>();
    /**
     * For {@link GraphTypeInfo}: stores all consumers (no sig/impl distinction).
     * For {@link AbiTypeInfo}: stores only signature consumers.
     */
    private final Map<String, Set<String>> signatureConsumersIndex = new LinkedHashMap<>();
    /** Only populated for {@link AbiTypeInfo} entries. */
    private final Map<String, Set<String>> implementationConsumersIndex = new LinkedHashMap<>();

    private final Map<String, Set<String>> sourceToTypesIndex = new LinkedHashMap<>();
    private String configHash = "";

    // -----------------------------------------------------------------------
    // TypeInfo sealed hierarchy
    // -----------------------------------------------------------------------

    /**
     * Per-type metadata stored by the incremental engine.
     *
     * <p>Use {@code switch} on the concrete type to distinguish the two strategies:
     * <pre>{@code
     * switch (info) {
     *     case GraphTypeInfo g -> // class-level deps only
     *     case AbiTypeInfo  a -> // fine-grained sig/impl deps + ABI fingerprint
     * }
     * }</pre>
     */
    public sealed interface TypeInfo permits GraphTypeInfo, AbiTypeInfo {
        /** Path to the source file that defines this type. */
        String sourceFile();

        /** All class-level dependency references (constant pool {@code ClassEntry} names). */
        Set<String> classDeps();

        /** Annotation types applied to this type or its members. */
        Set<String> annotationTypes();

        /** Java module name, or empty string if non-modular or unnamed module. */
        String moduleName();
    }

    /**
     * Type metadata for the {@code graph} incremental strategy.
     *
     * <p>Stores all class-level references without distinguishing public API (signature)
     * from method-body references (implementation). No ABI fingerprint is computed.
     *
     * @param sourceFile     path to the source file that defines this type
     * @param classDeps      all types referenced in the classfile (constant pool class entries),
     *                       regardless of where the reference appears
     * @param annotationTypes annotation types applied to this type or its members
     * @param moduleName     Java module name (empty string if non-modular or unnamed)
     */
    public record GraphTypeInfo(
            String sourceFile, Set<String> classDeps, Set<String> annotationTypes, String moduleName)
            implements TypeInfo {}

    /**
     * Type metadata for the {@code abi} incremental strategy.
     *
     * <p>Extends {@link GraphTypeInfo} with fine-grained dependency sets and an ABI fingerprint,
     * enabling more precise cascade decisions (only signature consumers cascade transitively)
     * and cross-module incremental detection via {@link AbiManifest}.
     *
     * @param sourceFile         path to the source file that defines this type
     * @param classDeps          all types referenced in the classfile (union of sig + impl deps)
     * @param annotationTypes    annotation types applied to this type or its members
     * @param moduleName         Java module name (empty string if non-modular or unnamed)
     * @param signatureDeps      types in the public API surface (extends/implements, method/field
     *                           descriptors of non-private members, exception types, annotations)
     * @param implementationDeps types referenced only in method bodies or private members
     * @param abiFingerprint     16-character hex SHA-256 prefix of the ABI canonical form
     */
    public record AbiTypeInfo(
            String sourceFile,
            Set<String> classDeps,
            Set<String> annotationTypes,
            String moduleName,
            Set<String> signatureDeps,
            Set<String> implementationDeps,
            String abiFingerprint)
            implements TypeInfo {}

    // -----------------------------------------------------------------------
    // Source hash accessors
    // -----------------------------------------------------------------------

    public String getSourceHash(String path) {
        return sourceHashes.get(path);
    }

    public Map<String, String> getSourceHashes() {
        return Collections.unmodifiableMap(sourceHashes);
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

    // -----------------------------------------------------------------------
    // TypeInfo accessors
    // -----------------------------------------------------------------------

    public TypeInfo getType(String qualifiedName) {
        return types.get(qualifiedName);
    }

    public Map<String, TypeInfo> getTypes() {
        return Collections.unmodifiableMap(types);
    }

    /**
     * Returns the ABI fingerprint for the given type, or {@code null} if the type
     * is not stored with {@link AbiTypeInfo} (i.e. the {@code graph} strategy is in use).
     */
    public String getAbiFingerprint(String qualifiedName) {
        TypeInfo info = types.get(qualifiedName);
        return info instanceof AbiTypeInfo abi ? abi.abiFingerprint() : null;
    }

    public void setType(String qualifiedName, TypeInfo info) {
        TypeInfo old = types.put(qualifiedName, info);
        updateInvertedIndex(qualifiedName, old, info);
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
        types.entrySet().removeIf(e -> {
            if (e.getValue().sourceFile().equals(sourceFile)) {
                updateInvertedIndex(e.getKey(), e.getValue(), null);
                return true;
            }
            return false;
        });
    }

    public List<String> getTypesFromSource(String sourceFile) {
        Set<String> indexed = sourceToTypesIndex.get(sourceFile);
        return indexed != null ? List.copyOf(indexed) : List.of();
    }

    public String sourceFileFor(String typeName) {
        TypeInfo info = types.get(typeName);
        return info != null ? info.sourceFile() : null;
    }

    // -----------------------------------------------------------------------
    // Consumer index accessors
    // -----------------------------------------------------------------------

    /**
     * Returns the set of types that have {@code type} in their signature dependencies.
     *
     * <p>For the {@code graph} strategy ({@link GraphTypeInfo}), this returns all consumers
     * (no sig/impl distinction is made). For the {@code abi} strategy ({@link AbiTypeInfo}),
     * this returns only signature consumers (those that cascade transitively).
     */
    public Set<String> getSignatureConsumers(String type) {
        return Collections.unmodifiableSet(signatureConsumersIndex.getOrDefault(type, Collections.emptySet()));
    }

    /**
     * Returns the set of types that have {@code type} in their implementation dependencies only.
     *
     * <p>Only populated for the {@code abi} strategy ({@link AbiTypeInfo}). Always empty for
     * the {@code graph} strategy.
     */
    public Set<String> getImplementationConsumers(String type) {
        return Collections.unmodifiableSet(implementationConsumersIndex.getOrDefault(type, Collections.emptySet()));
    }

    /**
     * Returns all consumers of {@code type} (union of signature and implementation consumers).
     */
    public Set<String> getAllConsumers(String type) {
        var result = new TreeSet<>(getSignatureConsumers(type));
        result.addAll(getImplementationConsumers(type));
        return result;
    }

    // -----------------------------------------------------------------------
    // External dependency / fingerprint accessors
    // -----------------------------------------------------------------------

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
     * Returns the compilation context hash, capturing external configuration
     * such as module-info-patch.maven file content.
     */
    public String getConfigHash() {
        return configHash;
    }

    public void setConfigHash(String hash) {
        this.configHash = hash != null ? hash : "";
    }

    // -----------------------------------------------------------------------
    // Aggregate queries
    // -----------------------------------------------------------------------

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
     *
     * <p>Uses {@link TypeInfo#classDeps()} which is available for both strategies.
     */
    public Set<String> getExternalDependencies() {
        var external = new TreeSet<String>();
        for (TypeInfo info : types.values()) {
            for (String dep : info.classDeps()) {
                if (!types.containsKey(dep)) {
                    external.add(dep);
                }
            }
        }
        return external;
    }

    /**
     * Returns the current ABI fingerprints for all types stored as {@link AbiTypeInfo},
     * suitable for writing to an {@link AbiManifest}.
     *
     * <p>Types stored as {@link GraphTypeInfo} (i.e. compiled with the {@code graph} strategy)
     * are not included — they have no ABI fingerprint.
     */
    public Map<String, String> getAllAbiFingerprints() {
        var result = new LinkedHashMap<String, String>();
        for (var entry : types.entrySet()) {
            if (entry.getValue() instanceof AbiTypeInfo abi) {
                result.put(entry.getKey(), abi.abiFingerprint());
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Copy / factory
    // -----------------------------------------------------------------------

    public IncrementalState copy() {
        var copy = new IncrementalState();
        copy.sourceHashes.putAll(this.sourceHashes);
        copy.sourceMtimes.putAll(this.sourceMtimes);
        copy.types.putAll(this.types);
        copy.externalFingerprints.putAll(this.externalFingerprints);
        copy.classpathIdentities.putAll(this.classpathIdentities);
        copy.configHash = this.configHash;
        copy.buildInvertedIndex();
        return copy;
    }

    // -----------------------------------------------------------------------
    // Inverted index maintenance
    // -----------------------------------------------------------------------

    private void buildInvertedIndex() {
        signatureConsumersIndex.clear();
        implementationConsumersIndex.clear();
        sourceToTypesIndex.clear();
        for (var entry : types.entrySet()) {
            String consumer = entry.getKey();
            TypeInfo info = entry.getValue();
            indexDepsFor(consumer, info);
        }
    }

    private void updateInvertedIndex(String typeName, TypeInfo oldInfo, TypeInfo newInfo) {
        // Remove old entries
        if (oldInfo != null) {
            Set<String> deps = depsForIndex(oldInfo);
            for (String dep : deps) {
                Set<String> consumers = signatureConsumersIndex.get(dep);
                if (consumers != null) {
                    consumers.remove(typeName);
                }
            }
            if (oldInfo instanceof AbiTypeInfo oldAbi) {
                for (String dep : oldAbi.implementationDeps()) {
                    Set<String> consumers = implementationConsumersIndex.get(dep);
                    if (consumers != null) {
                        consumers.remove(typeName);
                    }
                }
            }
            Set<String> oldSources = sourceToTypesIndex.get(oldInfo.sourceFile());
            if (oldSources != null) {
                oldSources.remove(typeName);
            }
        }
        // Add new entries
        indexDepsFor(typeName, newInfo);
    }

    /**
     * Indexes dependency edges for {@code typeName} from {@code info}.
     * Pass {@code null} for {@code info} to skip (no-op, used during removal).
     */
    private void indexDepsFor(String typeName, TypeInfo info) {
        if (info == null) {
            return;
        }
        // For GraphTypeInfo: all classDeps go into the signatureConsumersIndex (single index).
        // For AbiTypeInfo: signatureDeps go into signatureConsumersIndex,
        //                  implementationDeps go into implementationConsumersIndex.
        Set<String> sigDeps;
        if (info instanceof AbiTypeInfo abi) {
            sigDeps = abi.signatureDeps();
        } else {
            sigDeps = info.classDeps();
        }
        for (String dep : sigDeps) {
            signatureConsumersIndex.computeIfAbsent(dep, k -> new TreeSet<>()).add(typeName);
        }
        if (info instanceof AbiTypeInfo abi) {
            for (String dep : abi.implementationDeps()) {
                implementationConsumersIndex
                        .computeIfAbsent(dep, k -> new TreeSet<>())
                        .add(typeName);
            }
        }
        sourceToTypesIndex
                .computeIfAbsent(info.sourceFile(), k -> new TreeSet<>())
                .add(typeName);
    }

    /** Returns the set of deps used for the signatureConsumersIndex for a given TypeInfo. */
    private static Set<String> depsForIndex(TypeInfo info) {
        if (info instanceof AbiTypeInfo abi) {
            return abi.signatureDeps();
        }
        return info.classDeps();
    }

    // -----------------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------------

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(VERSION);
            out.writeInt(sourceHashes.size());
            for (var entry : sourceHashes.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
                Long mtime = sourceMtimes.get(entry.getKey());
                out.writeLong(mtime != null ? mtime : 0L);
            }
            out.writeInt(types.size());
            for (var entry : types.entrySet()) {
                String name = entry.getKey();
                TypeInfo info = entry.getValue();
                out.writeUTF(name);
                if (info instanceof AbiTypeInfo a) {
                    out.writeByte(TAG_ABI);
                    out.writeUTF(a.sourceFile());
                    writeStringSet(out, a.classDeps());
                    writeStringSet(out, a.annotationTypes());
                    out.writeUTF(a.moduleName());
                    writeStringSet(out, a.signatureDeps());
                    writeStringSet(out, a.implementationDeps());
                    out.writeUTF(a.abiFingerprint());
                } else {
                    GraphTypeInfo g = (GraphTypeInfo) info;
                    out.writeByte(TAG_GRAPH);
                    out.writeUTF(g.sourceFile());
                    writeStringSet(out, g.classDeps());
                    writeStringSet(out, g.annotationTypes());
                    out.writeUTF(g.moduleName());
                }
            }
            writeStringMap(out, externalFingerprints);
            writeStringMap(out, classpathIdentities);
            out.writeUTF(configHash);
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
                long mtime = in.readLong();
                if (mtime != 0L) {
                    state.sourceMtimes.put(path, mtime);
                }
            }
            int typeCount = in.readInt();
            for (int i = 0; i < typeCount; i++) {
                String name = in.readUTF();
                if (version == 1) {
                    // Legacy format (VERSION 1): all entries were AbiTypeInfo-equivalent
                    String sourceFile = in.readUTF();
                    String abi = in.readUTF();
                    Set<String> sigDeps = readStringSet(in);
                    Set<String> implDeps = readStringSet(in);
                    Set<String> annotTypes = readStringSet(in);
                    String moduleName = in.readUTF();
                    // Reconstruct classDeps as union of sig+impl (best effort for migration)
                    var classDeps = new TreeSet<String>();
                    classDeps.addAll(sigDeps);
                    classDeps.addAll(implDeps);
                    state.types.put(
                            name,
                            new AbiTypeInfo(
                                    sourceFile, Set.copyOf(classDeps), annotTypes, moduleName, sigDeps, implDeps, abi));
                } else {
                    byte tag = in.readByte();
                    String sourceFile = in.readUTF();
                    Set<String> classDeps = readStringSet(in);
                    Set<String> annotTypes = readStringSet(in);
                    String moduleName = in.readUTF();
                    if (tag == TAG_GRAPH) {
                        state.types.put(name, new GraphTypeInfo(sourceFile, classDeps, annotTypes, moduleName));
                    } else {
                        // TAG_ABI
                        Set<String> sigDeps = readStringSet(in);
                        Set<String> implDeps = readStringSet(in);
                        String abi = in.readUTF();
                        state.types.put(
                                name,
                                new AbiTypeInfo(sourceFile, classDeps, annotTypes, moduleName, sigDeps, implDeps, abi));
                    }
                }
            }
            readStringMap(in, state.externalFingerprints);
            readStringMap(in, state.classpathIdentities);
            state.configHash = in.readUTF();
            state.buildInvertedIndex();
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
