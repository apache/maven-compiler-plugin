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
 * @param qualifiedName      fully qualified type name (e.g. {@code com.example.Foo})
 * @param sourceFile         path to the source file that defines this type
 * @param signatureDeps      types referenced in the public API surface (extends/implements
 *                           clauses, method signatures, non-private field types) — an ABI
 *                           change in any of these cascades to this type's consumers
 * @param implementationDeps types referenced only in method bodies or private members — an
 *                           ABI change triggers recompilation of this type only, without
 *                           cascading to its consumers
 * @param abiFingerprint     truncated SHA-256 hash of the canonical ABI form
 * @param abiCanonical       human-readable canonical representation of the public API
 * @param annotationTypes    fully qualified names of annotations present on this type,
 *                           used for annotation processor classification decisions
 */
public record SourceFileAnalysis(
        String qualifiedName,
        String sourceFile,
        Set<String> signatureDeps,
        Set<String> implementationDeps,
        String abiFingerprint,
        String abiCanonical,
        Set<String> annotationTypes) {}
