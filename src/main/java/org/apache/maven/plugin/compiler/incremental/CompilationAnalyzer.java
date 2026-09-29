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
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.ProvidesTree;
import com.sun.source.tree.UsesTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
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
 * <p>Also handles {@code module-info.java} by extracting module directive
 * fingerprints via {@link AbiExtractor#computeModuleFingerprint}.
 *
 * <p>JDK-internal types ({@code java.*}, {@code javax.*}, {@code jdk.*},
 * {@code sun.*}) are filtered from the dependency sets since they never change
 * across incremental builds.
 */
public class CompilationAnalyzer implements TaskListener {

    private final Trees trees;
    private final Elements elements;
    private final Map<String, SourceFileAnalysis> analyses = new LinkedHashMap<>();

    public CompilationAnalyzer(JavacTask task) {
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
    }

    static final String MODULE_PREFIX = "module:";

    @Override
    public void finished(TaskEvent e) {
        if (e.getKind() != TaskEvent.Kind.ANALYZE) {
            return;
        }

        var cu = e.getCompilationUnit();
        if (cu == null) {
            return;
        }

        ModuleTree moduleTree = cu.getModule();
        if (moduleTree != null) {
            analyzeModule(moduleTree, cu);
            return;
        }

        TypeElement typeElement = e.getTypeElement();
        if (typeElement == null) {
            return;
        }

        String qualifiedName = typeElement.getQualifiedName().toString();
        String sourceFile = cu.getSourceFile().getName();

        // Scope the scan to only the ClassTree of the current type element.
        // Scanning the full CU would pollute each type's dependency set with
        // references from other top-level types in the same compilation unit
        // (over-cascading). Trees.getPath() gives us the TreePath rooted at
        // this type's ClassTree; DependencyScanner is a TreePathScanner so
        // scanning from that path restricts traversal to this type's subtree.
        var scanner = new DependencyScanner(trees);
        TreePath typePath = trees.getPath(typeElement);
        if (typePath != null && typePath.getLeaf() instanceof ClassTree) {
            scanner.scan(typePath, null);
        } else {
            // Fallback: scan the full CU (safe but may over-cascade)
            scanner.scan(cu, null);
        }

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

        String moduleName = resolveModuleName(typeElement);

        analyses.put(
                qualifiedName,
                new SourceFileAnalysis(
                        qualifiedName,
                        sourceFile,
                        sigDeps,
                        implDeps,
                        abiFingerprint,
                        abiCanonical,
                        annotationTypes,
                        moduleName));
    }

    private String resolveModuleName(TypeElement typeElement) {
        try {
            ModuleElement module = elements.getModuleOf(typeElement);
            if (module != null && !module.isUnnamed()) {
                return module.getQualifiedName().toString();
            }
        } catch (Exception e) {
            // Some javac versions may not support getModuleOf — fall back to empty
        }
        return "";
    }

    private void analyzeModule(ModuleTree moduleTree, CompilationUnitTree cu) {
        String moduleName = moduleTree.getName().toString();
        String qualifiedName = MODULE_PREFIX + moduleName;
        String sourceFile = cu.getSourceFile().getName();

        String abiFingerprint = AbiExtractor.computeModuleFingerprint(moduleTree);
        String abiCanonical = AbiExtractor.moduleCanonicalForm(moduleTree);

        var sigDeps = new TreeSet<String>();
        for (DirectiveTree directive : moduleTree.getDirectives()) {
            if (directive instanceof UsesTree u) {
                String typeName = u.getServiceName().toString();
                if (!isJdkType(typeName)) {
                    sigDeps.add(typeName);
                }
            } else if (directive instanceof ProvidesTree p) {
                String typeName = p.getServiceName().toString();
                if (!isJdkType(typeName)) {
                    sigDeps.add(typeName);
                }
                if (p.getImplementationNames() != null) {
                    for (var impl : p.getImplementationNames()) {
                        String implName = impl.toString();
                        if (!isJdkType(implName)) {
                            sigDeps.add(implName);
                        }
                    }
                }
            }
        }

        analyses.put(
                qualifiedName,
                new SourceFileAnalysis(
                        qualifiedName,
                        sourceFile,
                        sigDeps,
                        Set.of(),
                        abiFingerprint,
                        abiCanonical,
                        Set.of(),
                        moduleName));
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
