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
import javax.tools.ToolProvider;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;

import static java.util.Optional.ofNullable;

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
     * The base directory with which to relativize the paths to source files.
     */
    private final Path directory;

    /**
     * Type of output of the logger.
     */
    private final MessageLogType logType;

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
     * @param logger                the logger where to send diagnostics
     * @param messageBuilderFactory the factory for creating message builders
     * @param locale                the locale for compiler message
     * @param directory             the base directory with which to relativize the paths to source files
     * @param logType               output flavor
     */
    DiagnosticLogger(
            Log logger,
            MessageBuilderFactory messageBuilderFactory,
            Locale locale,
            Path directory,
            MessageLogType logType) {
        this.logger = logger;
        this.messageBuilderFactory = messageBuilderFactory;
        this.locale = locale;
        this.directory = directory;
        this.logType = logType;
        codeCount = new LinkedHashMap<>();
    }

    /**
     * Makes the given file relative to the base directory.
     *
     * @param file the path to make relative to the base directory
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
        if (logType == MessageLogType.COMPILER || logType == MessageLogType.ALL) {
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
        }
        if (logType == MessageLogType.SUMMARY || logType == MessageLogType.ALL) {
            // Statistics
            String code = diagnostic.getCode();
            if (code != null) {
                codeCount.merge(code, 1, (old, initial) -> old + 1);
            }
        }
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
        if (!codeCount.isEmpty()) {
            final var bundle = tryGetCompilerBundle();
            for (final var entry : codeCount.entrySet().stream()
                    // sort occurrence then key to have an absolute ordering
                    .sorted(Map.Entry.<String, Integer>comparingByValue()
                            .reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .toList()) {
                int count = entry.getValue();
                String key = entry.getKey();
                if (bundle != null) {
                    try {
                        // not great but the code is worse when you read it
                        key = key + " (" + bundle.getString(key) + ")";
                    } catch (final RuntimeException re) {
                        // ignore, use the plain key
                    }
                }
                Consumer<String> log;
                if (entry.getKey().startsWith("compiler.")) {
                    final var sub = entry.getKey().substring("compiler.".length());
                    if (sub.startsWith("err.")) {
                        log = logger::error;
                    } else if (sub.startsWith("warn.")) {
                        log = logger::warn;
                    } else {
                        log = logger::info;
                    }
                } else {
                    log = logger::info;
                }
                log.accept(messageBuilderFactory
                        .builder()
                        .strong(key + ": ")
                        .append(String.valueOf(count))
                        .build());
            }
        }

        if ((numWarnings | numErrors) != 0) {
            MessageBuilder message = messageBuilderFactory.builder();
            message.strong("Total:");
            if (numWarnings != 0) {
                message.append(' ').append(String.valueOf(numWarnings)).append(" warning");
                if (numWarnings > 1) {
                    message.append('s');
                }
            }
            if (numErrors != 0) {
                message.append(' ').append(String.valueOf(numErrors)).append(" error");
                if (numErrors > 1) {
                    message.append('s');
                }
            }
            logger.info(message.build());
        }
    }

    // we mainly know the one for javac as of today, this impl is best effort
    private ResourceBundle tryGetCompilerBundle() {
        // ignore the locale since we do log everything in english,
        // use only default bundle to avoid mixed outputs
        final var bundleName = "com.sun.tools.javac.resources.compiler";
        try {
            final var clazz = ToolProvider.getSystemJavaCompiler().getClass();
            final var resources = bundleName.replace('.', '/') + ".class";
            final var is = clazz.getModule() == null
                    ? ofNullable(clazz.getClassLoader())
                            .orElseGet(ClassLoader::getSystemClassLoader)
                            .getResourceAsStream(resources)
                    : clazz.getModule().getResourceAsStream(resources);
            if (is == null) {
                return null;
            }
            try (is) {
                final var bytes = is.readAllBytes();
                final var rbClass = new ClassLoader() {
                    {
                        super.defineClass(bundleName, bytes, 0, bytes.length);
                    }
                }.loadClass(bundleName);
                final var cons = rbClass.getConstructor();
                cons.setAccessible(true);
                return (ResourceBundle) cons.newInstance();
            }
        } catch (final Exception e) {
            return null;
        }
    }
}
