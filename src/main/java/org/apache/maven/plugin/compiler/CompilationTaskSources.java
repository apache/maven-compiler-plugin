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

import javax.tools.JavaCompiler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Source files to compile in a single compilation task.
 * This is simply a list of files, together with an optional task to execute before and after compilation.
 */
class CompilationTaskSources {
    /**
     * All source files to compile;
     */
    final List<Path> files;

    /**
     * Creates a new compilation task.
     *
     * @param files the files to compile
     */
    CompilationTaskSources(List<Path> files) {
        this.files = files;
    }

    /**
     * Executes the compilation task. Subclasses can override this method is they need to perform
     * pre-compilation or post-compilation tasks.
     *
     * @param task the compilation task
     * @return whether the compilation was successful
     * @throws IOException if an initialization or cleaner task was required and failed
     */
    boolean compile(JavaCompiler.CompilationTask task) throws IOException {
        return task.call();
    }
}
