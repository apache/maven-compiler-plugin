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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CompilationOutputRegistryTest {
    private static final String FIRST_EXECUTION = "compile@first";

    private static final String SECOND_EXECUTION = "compile@second";

    private static final Path OUTPUT = Paths.get("target/classes/Example.class");

    private static final Path OTHER_OUTPUT = Paths.get("target/classes/Other.class");

    private final Map<String, Object> pluginContext = new HashMap<>();

    private final Set<Path> outputs = Collections.singleton(OUTPUT);

    @Test
    void shouldIgnoreOutputsFromTheSameExecution() {
        CompilationOutputRegistry.register(pluginContext, FIRST_EXECUTION, outputs);

        assertFalse(CompilationOutputRegistry.find(pluginContext, FIRST_EXECUTION, outputs)
                .isPresent());
    }

    @Test
    void shouldFindOutputsFromAnotherExecution() {
        CompilationOutputRegistry.register(pluginContext, FIRST_EXECUTION, outputs);

        assertEquals(
                OUTPUT,
                CompilationOutputRegistry.find(pluginContext, SECOND_EXECUTION, outputs)
                        .orElse(null));
    }

    @Test
    void shouldIgnoreDisjointOutputs() {
        CompilationOutputRegistry.register(pluginContext, FIRST_EXECUTION, outputs);

        assertFalse(CompilationOutputRegistry.find(pluginContext, SECOND_EXECUTION, Collections.singleton(OTHER_OUTPUT))
                .isPresent());
    }

    @Test
    void shouldTrackTheLastExecution() {
        CompilationOutputRegistry.register(pluginContext, FIRST_EXECUTION, outputs);
        CompilationOutputRegistry.register(pluginContext, SECOND_EXECUTION, outputs);

        assertEquals(
                OUTPUT,
                CompilationOutputRegistry.find(pluginContext, FIRST_EXECUTION, outputs)
                        .orElse(null));
        assertFalse(CompilationOutputRegistry.find(pluginContext, SECOND_EXECUTION, outputs)
                .isPresent());
    }
}
