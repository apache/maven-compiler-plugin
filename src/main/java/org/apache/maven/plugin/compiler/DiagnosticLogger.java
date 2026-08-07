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
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.BuilderProblem;
import org.apache.maven.api.services.DiagnosticReporter;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;

/**
 * A Java compiler diagnostic listener which sends the messages to the Maven logger
 * and reports structured {@link BuilderProblem}s to the {@link DiagnosticReporter}.
 *
 * @author Martin Desruisseaux
 */
final class DiagnosticLogger implements DiagnosticListener<JavaFileObject> {
    /**
     * The logger where to send diagnostics.
     */
    private final Log logger;

    /**
     * The factory for creating message builders.
     */
    private final MessageBuilderFactory messageBuilderFactory;

    /**
     * The service for reporting structured diagnostics to the build report.
     * May be {@code null} if no reporter is available (e.g. Maven 4.0.x).
     */
    private final DiagnosticReporter diagnosticReporter;

    /**
     * The locale for compiler message.
     */
    private final Locale locale;

    /**
     * The base directory with which to relativize the paths to source files.
     */
    private final Path directory;

    /**
     * Number of errors or warnings.
     */
    private int numErrors, numWarnings;

    /**
     * Number of messages received for each code.
     */
    private final Map<String, Integer> codeCount;

    /**
     * The first error, or {@code null} if none.
     */
    private String firstError;

    /**
     * Creates a listener which will send the diagnostics to the given logger
     * and to the given diagnostic reporter.
     *
     * @param logger the logger where to send diagnostics
     * @param messageBuilderFactory the factory for creating message builders
     * @param diagnosticReporter the reporter for structured build diagnostics, or {@code null}
     * @param locale the locale for compiler message
     * @param directory the base directory with which to relativize the paths to source files
     */
    DiagnosticLogger(
            Log logger,
            MessageBuilderFactory messageBuilderFactory,
            DiagnosticReporter diagnosticReporter,
            Locale locale,
            Path directory) {
        this.logger = logger;
        this.messageBuilderFactory = messageBuilderFactory;
        this.diagnosticReporter = diagnosticReporter;
        this.locale = locale;
        this.directory = directory;
        codeCount = new LinkedHashMap<>();
    }

    /**
     * Makes the given file relative to the base directory.
     *
     * @param  file  the path to make relative to the base directory
     * @return the given path, potentially relative to the base directory
     */
    private String relativize(String file) {
        if (directory != null) {
            try {
                return directory.relativize(Path.of(file)).toString();
            } catch (IllegalArgumentException e) {
                // Ignore, keep the absolute path.
            }
        }
        return file;
    }

    /**
     * Maps a {@link Diagnostic.Kind} to a {@link BuilderProblem.Severity}.
     */
    private static BuilderProblem.Severity mapSeverity(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> BuilderProblem.Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> BuilderProblem.Severity.WARNING;
            default -> BuilderProblem.Severity.INFO;
        };
    }

    /**
     * Invoked when the compiler emitted a warning.
     *
     * @param diagnostic the warning emitted by the Java compiler
     */
    @Override
    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        String message = diagnostic.getMessage(locale);
        if (message == null || message.isBlank()) {
            return;
        }
        MessageBuilder record = messageBuilderFactory.builder();
        record.a(message);
        JavaFileObject source = diagnostic.getSource();
        Diagnostic.Kind kind = diagnostic.getKind();
        String style;
        switch (kind) {
            case ERROR:
                style = ".error:-bold,f:red";
                break;
            case MANDATORY_WARNING:
            case WARNING:
                style = ".warning:-bold,f:yellow";
                break;
            default:
                style = ".info:-bold,f:blue";
                if (diagnostic.getLineNumber() == Diagnostic.NOPOS) {
                    source = null; // Some messages are generic, e.g. "Recompile with -Xlint:deprecation".
                }
                break;
        }
        if (source != null) {
            record.newline().a("    at ").a(relativize(source.getName()));
            long line = diagnostic.getLineNumber();
            long column = diagnostic.getColumnNumber();
            if (line != Diagnostic.NOPOS || column != Diagnostic.NOPOS) {
                record.style(style).a('[');
                if (line != Diagnostic.NOPOS) {
                    record.a(line);
                }
                if (column != Diagnostic.NOPOS) {
                    record.a(',').a(column);
                }
                record.a(']').resetStyle();
            }
        }
        String log = record.toString();
        switch (kind) {
            case ERROR:
                if (firstError == null) {
                    firstError = message;
                }
                logger.error(log);
                numErrors++;
                break;
            case MANDATORY_WARNING:
            case WARNING:
                logger.warn(log);
                numWarnings++;
                break;
            default:
                logger.info(log);
                break;
        }
        // Statistics
        String code = diagnostic.getCode();
        if (code != null) {
            codeCount.merge(code, 1, (old, initial) -> old + 1);
        }
        // Report structured diagnostic to the build report
        reportToBuildReport(diagnostic, message, code);
    }

    /**
     * Reports a structured {@link BuilderProblem} to the {@link DiagnosticReporter}.
     * <p>
     * Each diagnostic is reported with a per-type key ({@code "compiler:<code>"})
     * so that the build report deduplicates by diagnostic kind. For example, 50
     * unchecked warnings produce a single entry with count=50 in the summary.
     * Individual per-file details remain in the build log.
     */
    private void reportToBuildReport(Diagnostic<? extends JavaFileObject> diagnostic, String message, String code) {
        if (diagnosticReporter == null || code == null) {
            return;
        }
        BuilderProblem.Builder builder = BuilderProblem.builder()
                .severity(mapSeverity(diagnostic.getKind()))
                .message(message)
                .key("compiler:" + code);
        // Attach source location from the first occurrence (collector deduplicates by key)
        JavaFileObject sourceFile = diagnostic.getSource();
        if (sourceFile != null) {
            builder.source(relativize(sourceFile.getName()));
            long line = diagnostic.getLineNumber();
            if (line != Diagnostic.NOPOS) {
                builder.lineNumber((int) line);
            }
            long column = diagnostic.getColumnNumber();
            if (column != Diagnostic.NOPOS) {
                builder.columnNumber((int) column);
            }
        }
        diagnosticReporter.report(builder.build());
    }

    /**
     * Returns the first error, if any.
     *
     * @param cause if compilation failed with an exception, the cause
     */
    Optional<String> firstError(Throwable cause) {
        return Optional.ofNullable(cause != null && firstError == null ? cause.getMessage() : firstError);
    }

    /**
     * Reports summary after the compilation finished.
     */
    void logSummary() {
        MessageBuilder message = messageBuilderFactory.builder();
        final String patternForCount;
        if (!codeCount.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, Integer>[] entries = codeCount.entrySet().toArray(Map.Entry[]::new);
            Arrays.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
            patternForCount = patternForCount(Math.max(entries[0].getValue(), Math.max(numWarnings, numErrors)));
            message.strong("Summary of compiler messages:").newline();
            for (Map.Entry<String, Integer> entry : entries) {
                int count = entry.getValue();
                message.format(patternForCount, count, entry.getKey()).newline();
            }
        } else {
            patternForCount = patternForCount(Math.max(numWarnings, numErrors));
        }
        if ((numWarnings | numErrors) != 0) {
            message.strong("Total:");
        }
        if (numWarnings != 0) {
            writeCount(message, patternForCount, numWarnings, "warning");
        }
        if (numErrors != 0) {
            writeCount(message, patternForCount, numErrors, "error");
        }
        logger.info(message.toString());
    }

    /**
     * {@return the pattern for formatting the specified number followed by a label}
     * The given number should be the widest number to format.
     * A margin of 4 spaces is added at the beginning of the line.
     */
    private static String patternForCount(int n) {
        return "    %" + Integer.toString(n).length() + "d %s";
    }

    /**
     * Appends the count of warnings or errors, making them plural if needed.
     */
    private static void writeCount(MessageBuilder message, String patternForCount, int count, String name) {
        message.newline();
        message.format(patternForCount, count, name);
        if (count > 1) {
            message.append('s');
        }
    }
}
