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

import java.util.Set;

/**
 * Analysis results for a single type produced during compilation.
 *
 * <p>The {@code classDeps} field is the union of all class references found in the
 * classfile constant pool, regardless of where they appear. It is always populated
 * and used by the {@code graph} incremental strategy.
 *
 * <p>The {@code signatureDeps}, {@code implementationDeps}, {@code abiFingerprint},
 * and {@code abiCanonical} fields are only meaningful when the {@code abi} strategy
 * is in use; they are empty/null otherwise.
 *
 * @param qualifiedName      fully qualified type name (e.g. {@code com.example.Foo})
 * @param sourceFile         path to the source file that defines this type
 * @param classDeps          all types referenced in the classfile constant pool (union of all
 *                           class references, regardless of where they appear) — used by the
 *                           {@code graph} strategy
 * @param signatureDeps      types referenced in the public API surface (extends/implements
 *                           clauses, method signatures, non-private field types) — used by the
 *                           {@code abi} strategy for precise cascade decisions
 * @param implementationDeps types referenced only in method bodies or private members — used
 *                           by the {@code abi} strategy; changes here trigger recompilation of
 *                           this type only, without cascading to its consumers
 * @param abiFingerprint     truncated SHA-256 hash of the canonical ABI form (empty string
 *                           when the {@code graph} strategy is in use)
 * @param abiCanonical       human-readable canonical representation of the public API
 *                           (empty string when the {@code graph} strategy is in use)
 * @param annotationTypes    fully qualified names of annotations present on this type,
 *                           used for annotation processor classification decisions
 * @param moduleName         Java module name (empty string if non-modular or unnamed module)
 */
public record SourceFileAnalysis(
        String qualifiedName,
        String sourceFile,
        Set<String> classDeps,
        Set<String> signatureDeps,
        Set<String> implementationDeps,
        String abiFingerprint,
        String abiCanonical,
        Set<String> annotationTypes,
        String moduleName) {}
