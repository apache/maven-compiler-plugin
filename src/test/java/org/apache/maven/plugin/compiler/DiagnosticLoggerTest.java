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

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import java.nio.file.Path;
import java.util.Locale;

import org.apache.maven.impl.DefaultMessageBuilderFactory;
import org.apache.maven.internal.impl.DefaultLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosticLoggerTest {
    @Test
    void summary(@TempDir final Path tmp) {
        final var builder = new StringBuilder();
        final DiagnosticLogger logger = new DiagnosticLogger(
                new DefaultLog(NOPLogger.NOP_LOGGER) {
                    @Override
                    public boolean isInfoEnabled() {
                        return true;
                    }

                    @Override
                    public boolean isWarnEnabled() {
                        return true;
                    }

                    @Override
                    public void info(final CharSequence content) {
                        builder.append("[INFO] ").append(content).append('\n');
                    }

                    @Override
                    public void warn(final CharSequence content) {
                        builder.append("[WARNING] ").append(content).append('\n');
                    }
                },
                new DefaultMessageBuilderFactory(),
                Locale.ROOT,
                tmp,
                MessageLogType.SUMMARY);
        logger.report(new SimpleDiagnostic(Diagnostic.Kind.NOTE, "some note", "compiler.note.deprecated.filename"));
        logger.report(new SimpleDiagnostic(Diagnostic.Kind.WARNING, "some warning", "compiler.warn.invalid.path"));
        logger.logSummary();

        assertEquals("""
                        [INFO] compiler.note.deprecated.filename ({0} uses or overrides a deprecated API.): 1
                        [WARNING] compiler.warn.invalid.path (Invalid filename: {0}): 1
                        """, builder.toString());
    }

    private static class SimpleDiagnostic implements Diagnostic<JavaFileObject> {
        private final Kind kind;
        private final String message;
        private final String code;

        private SimpleDiagnostic(final Kind kind, final String message, final String code) {
            this.kind = kind;
            this.message = message;
            this.code = code;
        }

        @Override
        public Kind getKind() {
            return kind;
        }

        @Override
        public JavaFileObject getSource() {
            return null;
        }

        @Override
        public long getPosition() {
            return 0;
        }

        @Override
        public long getStartPosition() {
            return 0;
        }

        @Override
        public long getEndPosition() {
            return 0;
        }

        @Override
        public long getLineNumber() {
            return 0;
        }

        @Override
        public long getColumnNumber() {
            return 0;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage(final Locale locale) {
            return message;
        }
    }
}
