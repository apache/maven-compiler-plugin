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
package org.apache.maven.plugin.compiler.incremental;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

/**
 * A {@link TaskListener} that intercepts javac's {@code ANALYZE} phase to extract
 * per-type dependency and ABI information from a compilation.
 *
 * <p>For each type element analyzed, it runs a {@link DependencyScanner} over the
 * compilation unit's AST to collect type references — classified as signature
 * dependencies (appear in the public API surface) or implementation dependencies
 * (body-only) — and uses {@link AbiExtractor} to compute an ABI fingerprint.
 * Results are collected into {@link SourceFileAnalysis} records accessible
 * via {@link #getResults()}.
 *
 * <p>JDK-internal types ({@code java.*}, {@code javax.*}, {@code jdk.*},
 * {@code sun.*}) are filtered from the dependency sets since they never change
 * across incremental builds.
 */
public class CompilationAnalyzer implements TaskListener {

    private final Trees trees;
    private final Map<String, SourceFileAnalysis> analyses = new LinkedHashMap<>();

    public CompilationAnalyzer(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    @Override
    public void finished(TaskEvent e) {
        if (e.getKind() != TaskEvent.Kind.ANALYZE) {
            return;
        }

        TypeElement typeElement = e.getTypeElement();
        var cu = e.getCompilationUnit();
        if (typeElement == null || cu == null) {
            return;
        }

        String qualifiedName = typeElement.getQualifiedName().toString();
        String sourceFile = cu.getSourceFile().getName();

        var scanner = new DependencyScanner(trees);
        scanner.scan(cu, null);

        String abiFingerprint = AbiExtractor.computeFingerprint(typeElement);
        String abiCanonical = AbiExtractor.canonicalForm(typeElement);

        var sigDeps = new TreeSet<>(scanner.getSignatureDeps());
        var implDeps = new TreeSet<>(scanner.getImplementationDeps());

        sigDeps.remove(qualifiedName);
        implDeps.remove(qualifiedName);
        implDeps.removeAll(sigDeps);
        sigDeps.removeIf(CompilationAnalyzer::isJdkType);
        implDeps.removeIf(CompilationAnalyzer::isJdkType);

        var annotationTypes = new TreeSet<String>();
        // Type-level annotations
        for (AnnotationMirror am : typeElement.getAnnotationMirrors()) {
            String annotName = am.getAnnotationType().toString();
            if (!isJdkType(annotName)) {
                annotationTypes.add(annotName);
            }
        }
        // Member-level annotations (methods, fields, constructors)
        for (javax.lang.model.element.Element member : typeElement.getEnclosedElements()) {
            for (AnnotationMirror am : member.getAnnotationMirrors()) {
                String annotName = am.getAnnotationType().toString();
                if (!isJdkType(annotName)) {
                    annotationTypes.add(annotName);
                }
            }
        }

        analyses.put(
                qualifiedName,
                new SourceFileAnalysis(
                        qualifiedName, sourceFile, sigDeps, implDeps, abiFingerprint, abiCanonical, annotationTypes));
    }

    public Map<String, SourceFileAnalysis> getResults() {
        return Collections.unmodifiableMap(analyses);
    }

    static boolean isJdkType(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.");
    }
}
