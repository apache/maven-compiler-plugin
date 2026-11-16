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

/**
 * Classification of annotation processors for incremental compilation.
 *
 * <p>Follows the same taxonomy as Gradle's incremental annotation processing
 * for ecosystem compatibility.
 *
 * @see ProcessorClassification
 */
public enum ProcessorType {

    /**
     * Each annotated input type produces independent generated outputs.
     * Safe for incremental compilation — only the changed annotated type
     * and its generated outputs need reprocessing.
     */
    ISOLATING,

    /**
     * Generated outputs may depend on the full set of annotated types
     * (e.g., Dagger component graphs, ServiceLoader registrations).
     * When any source carrying the processor's trigger annotations changes,
     * all sources with those annotations must be included in the compilation.
     */
    AGGREGATING,

    /**
     * Processor has not declared its incremental behavior. When any annotated
     * source changes, all sources are recompiled as a conservative fallback.
     */
    UNKNOWN
}
