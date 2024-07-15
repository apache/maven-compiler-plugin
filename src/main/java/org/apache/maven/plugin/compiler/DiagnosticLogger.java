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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;

/**
 * A Java compiler diagnostic listener which send the messages to the Maven logger.
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
     * The locale for compiler message.
     */
    private final Locale locale;

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
     *
     * @param logger the logger where to send diagnostics
     * @param messageBuilderFactory the factory for creating message builders
     * @param locale the locale for compiler message
     */
    DiagnosticLogger(Log logger, MessageBuilderFactory messageBuilderFactory, Locale locale) {
        this.logger = logger;
        this.messageBuilderFactory = messageBuilderFactory;
        this.locale = locale;
        codeCount = new LinkedHashMap<>();
    }

    /**
     * Invoked when the compiler emitted a warning.
     *
     * @param diagnostic the warning emitted by the Java compiler
     */
    @Override
    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        MessageBuilder record = messageBuilderFactory.builder();
        String message = diagnostic.getMessage(locale);
        record.a(message);
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
                break;
        }
        JavaFileObject source = diagnostic.getSource();
        if (source != null) {
            record.newline().a("    at ").a(source.getName());
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
    }

    /**
     * Returns the first error, if any.
     *
     * @param cause if compilation failed with an exception, the cause
     */
    Optional<String> firstError(Exception cause) {
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
            message.strong("Total:").newline();
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
     * {@return the pattern for formatting the specified number followed by a label}.
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
        message.format(patternForCount, count, name);
        if (count > 1) {
            message.append('s');
        }
        message.newline();
    }
}
