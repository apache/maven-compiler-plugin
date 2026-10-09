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

import java.util.List;
import java.util.Locale;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.impl.DefaultMessageBuilderFactory;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosticLoggerTest {
    @Test
    void fallsBackToKindAndCodeWhenDiagnosticMessageCannotBeFormatted() {
        var logger = mock(Log.class);
        @SuppressWarnings("unchecked")
        Diagnostic<JavaFileObject> diagnostic = mock(Diagnostic.class);
        when(diagnostic.getMessage(nullable(Locale.class))).thenThrow(new RuntimeException("missing type"));
        when(diagnostic.getKind()).thenReturn(Diagnostic.Kind.WARNING);
        when(diagnostic.getSource()).thenReturn(null);
        when(diagnostic.getLineNumber()).thenReturn(Diagnostic.NOPOS);
        when(diagnostic.getColumnNumber()).thenReturn(Diagnostic.NOPOS);
        when(diagnostic.getCode()).thenReturn("compiler.warn.has.been.deprecated");

        var listener = new DiagnosticLogger(logger, new DefaultMessageBuilderFactory(), null, null);
        listener.report(diagnostic);

        verify(logger).warn((String)
                argThat(message -> ((String) message).contains("WARNING: compiler.warn.has.been.deprecated")));
    }

    @Test
    void fallsBackToKindWhenDiagnosticCodeIsNull() {
        var logger = mock(Log.class);
        @SuppressWarnings("unchecked")
        Diagnostic<JavaFileObject> diagnostic = mock(Diagnostic.class);
        when(diagnostic.getMessage(nullable(Locale.class))).thenThrow(new RuntimeException("missing type"));
        when(diagnostic.getKind()).thenReturn(Diagnostic.Kind.WARNING);
        when(diagnostic.getSource()).thenReturn(null);
        when(diagnostic.getLineNumber()).thenReturn(Diagnostic.NOPOS);
        when(diagnostic.getColumnNumber()).thenReturn(Diagnostic.NOPOS);
        when(diagnostic.getCode()).thenReturn(null);

        var listener = new DiagnosticLogger(logger, new DefaultMessageBuilderFactory(), null, null);
        listener.report(diagnostic);

        verify(logger).warn((String) argThat(message -> ((String) message).contains("WARNING")));
        verify(logger).warn((String) argThat(message -> !((String) message).contains("WARNING: null")));
    }

    @Test
    void logsFormattingFailureOnlyOnceForEachDiagnosticCode() {
        var logger = mock(Log.class);
        @SuppressWarnings("unchecked")
        Diagnostic<JavaFileObject> first = mock(Diagnostic.class);
        @SuppressWarnings("unchecked")
        Diagnostic<JavaFileObject> second = mock(Diagnostic.class);
        var failure = new RuntimeException("missing type");
        for (Diagnostic<JavaFileObject> diagnostic : List.of(first, second)) {
            when(diagnostic.getMessage(nullable(Locale.class))).thenThrow(failure);
            when(diagnostic.getKind()).thenReturn(Diagnostic.Kind.WARNING);
            when(diagnostic.getSource()).thenReturn(null);
            when(diagnostic.getLineNumber()).thenReturn(Diagnostic.NOPOS);
            when(diagnostic.getColumnNumber()).thenReturn(Diagnostic.NOPOS);
            when(diagnostic.getCode()).thenReturn("compiler.warn.has.been.deprecated");
        }

        var listener = new DiagnosticLogger(logger, new DefaultMessageBuilderFactory(), null, null);
        listener.report(first);
        listener.report(second);

        verify(logger, times(1))
                .debug(eq("Cannot format compiler diagnostic; falling back to its kind and code."), same(failure));
        verify(logger, times(2)).warn((String)
                argThat(message -> ((String) message).contains("WARNING: compiler.warn.has.been.deprecated")));
    }
}
