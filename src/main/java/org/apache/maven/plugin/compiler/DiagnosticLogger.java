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

/**
 * A Java compiler diagnostic listener which sends the messages to the Maven logger.
 * When the logger supports structured problem reporting ({@link Log#problem(BuilderProblem)}),
 * each diagnostic is also reported as a {@link BuilderProblem} with a per-location dedup key,
 * enabling the build report, {@code mvnlog --diagnostics}, and warning suppression.
 *
 * @author Martin Desruisseaux
 */
final class DiagnosticLogger implements DiagnosticListener<JavaFileObject> {
    /**
     * The logger where to send diagnostics and structured problems.
     * This should be a child logger (e.g. {@code "compiler:compile.diagnostics"})
     * obtained via {@link Log#child(String)}.
     */
    private final Log logger;

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
     * Creates a listener which will send the diagnostics to the given logger.
     * Structured problems are reported via {@link Log#problem(BuilderProblem)},
     * which handles dedup, suppression, and thread-safe interaction with the
     * {@code BuildReportCollector} automatically.
     *
     * @param logger the logger where to send diagnostics (typically a child logger)
     * @param locale the locale for compiler message
     * @param directory the base directory with which to relativize the paths to source files
     */
    DiagnosticLogger(Log logger, Locale locale, Path directory) {
        this.logger = logger;
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
     * Maps a {@link Diagnostic.Kind} to a {@link BuilderProblem.Severity},
     * or {@code null} if the kind has no corresponding severity (e.g. {@code NOTE}).
     */
    private static BuilderProblem.Severity mapSeverity(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> BuilderProblem.Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> BuilderProblem.Severity.WARNING;
            default -> null;
        };
    }

    /**
     * Invoked when the compiler emitted a diagnostic.
     * <p>
     * When the diagnostic has a code and a mappable severity (error or warning),
     * a structured {@link BuilderProblem} is reported via {@link Log#problem(BuilderProblem)}
     * with a per-location dedup key ({@code "compiler:<code>:<source>:<line>"}).
     * This avoids double-counting with the {@code BuildReportCollector}'s WARN auto-promotion,
     * because {@code Log.problem()} sets the structured-problem flag internally.
     * <p>
     * Informational diagnostics (notes, other) are logged at INFO level without
     * creating a structured problem, since they are not actionable warnings.
     *
     * @param diagnostic the diagnostic emitted by the Java compiler
     */
    @Override
    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        String message = diagnostic.getMessage(locale);
        if (message == null || message.isBlank()) {
            return;
        }
        Diagnostic.Kind kind = diagnostic.getKind();
        JavaFileObject source = diagnostic.getSource();
        // Track counts for the summary
        switch (kind) {
            case ERROR:
                if (firstError == null) {
                    firstError = message;
                }
                numErrors++;
                break;
            case MANDATORY_WARNING:
            case WARNING:
                numWarnings++;
                break;
            default:
                if (diagnostic.getLineNumber() == Diagnostic.NOPOS) {
                    source = null; // Some messages are generic, e.g. "Recompile with -Xlint:deprecation".
                }
                break;
        }
        // Statistics
        String code = diagnostic.getCode();
        if (code != null) {
            codeCount.merge(code, 1, (old, initial) -> old + 1);
        }
        // Report as a structured problem when we have a diagnostic code and a mappable severity.
        // Log.problem() handles both the console log and the build report,
        // setting the STRUCTURED_PROBLEM_ACTIVE flag to prevent double-counting.
        BuilderProblem.Severity severity = mapSeverity(kind);
        if (code != null && severity != null) {
            logger.problem(buildProblem(diagnostic, message, code, source, severity));
        } else {
            // Informational diagnostic or no code — fall back to plain logging
            switch (kind) {
                case ERROR -> logger.error(message);
                case MANDATORY_WARNING, WARNING -> logger.warn(message);
                default -> logger.info(message);
            }
        }
    }

    /**
     * Builds a structured {@link BuilderProblem} from the given compiler diagnostic.
     * Each diagnostic gets a per-location key ({@code "compiler:<code>:<source>:<line>"})
     * so each unique file+line keeps its own entry in the build report.
     * <p>
     * The message includes the source location as plain text (e.g.
     * {@code "unchecked cast\n    at src/main/java/Foo.java[42,10]"})
     * so it remains visible in the console output.
     */
    private BuilderProblem buildProblem(
            Diagnostic<? extends JavaFileObject> diagnostic,
            String message,
            String code,
            JavaFileObject source,
            BuilderProblem.Severity severity) {
        BuilderProblem.Builder builder = BuilderProblem.builder().severity(severity);
        // Build message with source location and per-location dedup key
        var fullMessage = new StringBuilder(message);
        var keyBuilder = new StringBuilder("compiler:").append(code);
        if (source != null) {
            String relPath = relativize(source.getName());
            builder.source(relPath);
            keyBuilder.append(':').append(relPath);
            long line = diagnostic.getLineNumber();
            long column = diagnostic.getColumnNumber();
            fullMessage.append(System.lineSeparator()).append("    at ").append(relPath);
            if (line != Diagnostic.NOPOS || column != Diagnostic.NOPOS) {
                fullMessage.append('[');
                if (line != Diagnostic.NOPOS) {
                    fullMessage.append(line);
                    builder.lineNumber((int) line);
                    keyBuilder.append(':').append(line);
                }
                if (column != Diagnostic.NOPOS) {
                    fullMessage.append(',').append(column);
                    builder.columnNumber((int) column);
                }
                fullMessage.append(']');
            }
        }
        return builder.key(keyBuilder.toString())
                .message(fullMessage.toString())
                .build();
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
        var message = new StringBuilder();
        final String patternForCount;
        if (!codeCount.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, Integer>[] entries = codeCount.entrySet().toArray(Map.Entry[]::new);
            Arrays.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
            patternForCount = patternForCount(Math.max(entries[0].getValue(), Math.max(numWarnings, numErrors)));
            message.append("Summary of compiler messages:").append(System.lineSeparator());
            for (Map.Entry<String, Integer> entry : entries) {
                int count = entry.getValue();
                message.append(String.format(patternForCount, count, entry.getKey()))
                        .append(System.lineSeparator());
            }
        } else {
            patternForCount = patternForCount(Math.max(numWarnings, numErrors));
        }
        if ((numWarnings | numErrors) != 0) {
            message.append("Total:");
        }
        if (numWarnings != 0) {
            writeCount(message, patternForCount, numWarnings, "warning");
        }
        if (numErrors != 0) {
            writeCount(message, patternForCount, numErrors, "error");
        }
        logger.info(message);
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
    private static void writeCount(StringBuilder message, String patternForCount, int count, String name) {
        message.append(System.lineSeparator());
        message.append(String.format(patternForCount, count, name));
        if (count > 1) {
            message.append('s');
        }
    }
}
