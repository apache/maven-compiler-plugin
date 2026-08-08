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

/**
 * The incrementality type of an annotation processor, following the convention established by
 * Gradle's incremental annotation processing support. Processors declare their type via a
 * descriptor file at {@code META-INF/gradle/incremental.annotation.processors} or
 * {@code META-INF/maven/incremental.annotation.processors} inside the processor JAR.
 *
 * <p>The descriptor format is one line per processor:</p>
 * <pre>
 * com.example.MyProcessor,isolating
 * com.example.AggregatingProcessor,aggregating
 * </pre>
 *
 * @since 4.0.0
 * @see IncrementalProcessorScanner
 */
enum IncrementalProcessorType {
    /**
     * Each annotated element is processed in isolation. Generated files are per-element,
     * and only the changed source and its generated files need recompilation.
     *
     * <p>Isolating processors must:</p>
     * <ul>
     *   <li>Make decisions based only on information reachable from the annotated element's AST</li>
     *   <li>Provide exactly one originating element per generated file</li>
     *   <li>Not inspect unrelated elements in {@code RoundEnvironment}</li>
     * </ul>
     */
    ISOLATING,

    /**
     * Combines multiple annotated elements into shared outputs (e.g., a service registry).
     * Any source change requires reprocessing all annotated sources and regenerating
     * all outputs.
     */
    AGGREGATING,

    /**
     * The processor declares its type at runtime via {@code Processor.getSupportedOptions()}.
     * Treated as unknown at build configuration time since the actual type cannot be determined
     * without running the processor.
     */
    DYNAMIC,

    /**
     * The processor's incrementality type is unknown — no descriptor was found in its JAR.
     * Treated conservatively as requiring a full rebuild on any change, which matches the
     * behavior of Maven's compiler plugin prior to incremental processor support.
     */
    UNKNOWN
}
