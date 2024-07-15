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
package org.apache.maven.plugin.compiler.stubs;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A dummy implementation of the {@code JavaCompiler} interface with intentional failure.
 *
 * <h2>Instantiation</h2>
 * This stub is not instantiated directly. Instead, the fully-qualified class name must be declared
 * in the {@code META-INF/services/javax.tools.Tool} file. Then, an instance is requested by setting
 * the {@code <compilerId>} element to {@value #FAILING_COMPILER_ID} in the {@code plugin-config.xml}
 * file of the test.
 */
public class FailingCompilerStub extends CompilerStub {
    /**
     * The name returned by {@link #name()}. Used for identifying this stub.
     * This is the value to specify in the {@code <compilerId>} element of the POM test file.
     *
     * @see #name()
     */
    public static final String FAILING_COMPILER_ID = "maven-failing-compiler-stub";

    /**
     * Invoked by reflection by {@link java.util.ServiceLoader}.
     */
    public FailingCompilerStub() {}

    /**
     * {@return the compiler idenitifer of this stub}.
     */
    @Override
    public String name() {
        return FAILING_COMPILER_ID;
    }

    /**
     * Executes the pseudo-compilation.
     *
     * @return 1 for error
     */
    @Override
    public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
        return 1;
    }
}
