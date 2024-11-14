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

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Locale;

/**
 * A compiler which is executed by invoking a command-line tool.
 *
 * @author Martin Desruisseaux
 */
final class ForkedCompiler extends ForkedTool implements JavaCompiler {
    /**
     * Creates a new forked compiler.
     *
     * @param  mojo  the MOJO from which to get the configuration
     */
    ForkedCompiler(final AbstractCompilerMojo mojo) {
        super(mojo);
    }

    /**
     * Creates a task for launching the compilation.
     *
     * @param out where to send additional compiler output
     * @param fileManager the {@link ForkedToolSources} instance created by {@link #getStandardFileManager}
     * @param diagnosticListener currently ignored
     * @param options compiler options (should be {@link Options#options})
     * @param classes names of classes to be processed by annotation processing (currently ignored)
     * @param compilationUnits the source files to compile
     * @return the compilation task to run
     */
    @Override
    public CompilationTask getTask(
            Writer out,
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options,
            Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {
        return new CompilationTask() {
            /**
             * Adds root modules to be taken into account during module resolution.
             * Currently ignored, caller should use compiler options instead.
             */
            @Override
            public void addModules(Iterable<String> moduleNames) {}

            /**
             * Sets processors for annotation processing, bypassing the normal discovery mechanism.
             * Ignored because we cannot pass an instance of a Java object to a command-line.
             */
            @Override
            public void setProcessors(Iterable<? extends Processor> processors) {}

            /**
             * Sets the locale to be applied when formatting diagnostics and other localized data.
             * Currently ignored.
             */
            @Override
            public void setLocale(Locale locale) {}

            /**
             * Performs this compilation task.
             *
             * @return true if all the files compiled without errors
             */
            @Override
            public Boolean call() {
                try {
                    return run(out, (ForkedToolSources) fileManager, options, compilationUnits);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
}
