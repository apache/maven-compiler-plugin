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

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.ExportsTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.OpensTree;
import com.sun.source.tree.ProvidesTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.tree.UsesTree;

/**
 * Computes ABI (Application Binary Interface) fingerprints for Java types
 * using the {@link javax.lang.model} element API.
 *
 * <p>The canonical form includes the type's modifiers, name, type parameters,
 * superclass, interfaces, and all non-private members (fields, constructors,
 * methods, nested types). For {@code static final} fields, the compile-time
 * constant value is included so that changes to inlined constants are detected.
 *
 * <p>The fingerprint is a truncated SHA-256 hash of this canonical form.
 * Two types have the same fingerprint if and only if their public API surfaces
 * are identical — method body changes do not affect it.
 *
 * @see CompilationAnalyzer
 */
public class AbiExtractor {

    public static String computeFingerprint(TypeElement type) {
        return Sha256.hash(canonicalForm(type));
    }

    public static String canonicalForm(TypeElement type) {
        var sb = new StringBuilder();
        appendType(sb, type, 0);
        return sb.toString();
    }

    public static String computeModuleFingerprint(ModuleTree moduleTree) {
        return Sha256.hash(moduleCanonicalForm(moduleTree));
    }

    public static String moduleCanonicalForm(ModuleTree moduleTree) {
        var sb = new StringBuilder();
        if (moduleTree.getModuleType() == ModuleTree.ModuleKind.OPEN) {
            sb.append("open ");
        }
        sb.append("module ").append(moduleTree.getName()).append('\n');

        var requires = new TreeSet<String>();
        var exports = new TreeSet<String>();
        var opens = new TreeSet<String>();
        var uses = new TreeSet<String>();
        var provides = new TreeSet<String>();

        for (DirectiveTree directive : moduleTree.getDirectives()) {
            if (directive instanceof RequiresTree r) {
                var entry = new StringBuilder("requires ");
                if (r.isTransitive()) {
                    entry.append("transitive ");
                }
                if (r.isStatic()) {
                    entry.append("static ");
                }
                entry.append(r.getModuleName());
                requires.add(entry.toString());
            } else if (directive instanceof ExportsTree e) {
                var entry = new StringBuilder("exports ").append(e.getPackageName());
                var moduleNames = e.getModuleNames();
                if (moduleNames != null && !moduleNames.isEmpty()) {
                    entry.append(" to ");
                    entry.append(
                            moduleNames.stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
                }
                exports.add(entry.toString());
            } else if (directive instanceof OpensTree o) {
                var entry = new StringBuilder("opens ").append(o.getPackageName());
                var moduleNames = o.getModuleNames();
                if (moduleNames != null && !moduleNames.isEmpty()) {
                    entry.append(" to ");
                    entry.append(
                            moduleNames.stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
                }
                opens.add(entry.toString());
            } else if (directive instanceof UsesTree u) {
                uses.add("uses " + u.getServiceName());
            } else if (directive instanceof ProvidesTree p) {
                var entry = new StringBuilder("provides ").append(p.getServiceName());
                var implNames = p.getImplementationNames();
                if (implNames != null && !implNames.isEmpty()) {
                    entry.append(" with ");
                    entry.append(
                            implNames.stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
                }
                provides.add(entry.toString());
            }
        }

        for (String r : requires) {
            sb.append("  ").append(r).append('\n');
        }
        for (String e : exports) {
            sb.append("  ").append(e).append('\n');
        }
        for (String o : opens) {
            sb.append("  ").append(o).append('\n');
        }
        for (String u : uses) {
            sb.append("  ").append(u).append('\n');
        }
        for (String p : provides) {
            sb.append("  ").append(p).append('\n');
        }

        return sb.toString();
    }

    private static void appendType(StringBuilder sb, TypeElement type, int indent) {
        String prefix = "  ".repeat(indent);

        sb.append(prefix);
        appendModifiers(sb, type.getModifiers());
        sb.append(type.getKind().toString().toLowerCase(java.util.Locale.ROOT)).append(' ');
        sb.append(type.getQualifiedName());

        var typeParams = type.getTypeParameters();
        if (!typeParams.isEmpty()) {
            sb.append('<');
            sb.append(typeParams.stream()
                    .map(tp -> {
                        var tpSb = new StringBuilder(tp.getSimpleName());
                        var bounds = tp.getBounds().stream()
                                .filter(b -> !"java.lang.Object".equals(b.toString()))
                                .toList();
                        if (!bounds.isEmpty()) {
                            tpSb.append(" extends ");
                            tpSb.append(
                                    bounds.stream().map(TypeMirror::toString).collect(Collectors.joining(" & ")));
                        }
                        return tpSb.toString();
                    })
                    .collect(Collectors.joining(", ")));
            sb.append('>');
        }
        sb.append('\n');

        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.NONE && !"java.lang.Object".equals(superclass.toString())) {
            sb.append(prefix).append("  extends ").append(superclass).append('\n');
        }

        for (TypeMirror iface : type.getInterfaces()) {
            sb.append(prefix).append("  implements ").append(iface).append('\n');
        }

        var members = type.getEnclosedElements().stream()
                .filter(e -> !e.getModifiers().contains(Modifier.PRIVATE))
                .sorted(Comparator.<Element, Integer>comparing(e -> switch (e.getKind()) {
                            case FIELD, ENUM_CONSTANT -> 0;
                            case CONSTRUCTOR -> 1;
                            case METHOD -> 2;
                            default -> e.getKind().isClass() || e.getKind().isInterface() ? 3 : 4;
                        })
                        .thenComparing(e -> e.getSimpleName().toString())
                        .thenComparing(e -> e instanceof ExecutableElement ee
                                ? ee.getParameters().stream()
                                        .map(p -> p.asType().toString())
                                        .collect(Collectors.joining(","))
                                : ""))
                .toList();

        for (Element member : members) {
            if (member instanceof VariableElement ve) {
                appendField(sb, ve, indent + 1);
            } else if (member instanceof ExecutableElement ee) {
                appendMethod(sb, ee, indent + 1);
            } else if (member instanceof TypeElement te) {
                appendType(sb, te, indent + 1);
            }
        }
    }

    private static void appendField(StringBuilder sb, VariableElement field, int indent) {
        sb.append("  ".repeat(indent));
        appendModifiers(sb, field.getModifiers());
        sb.append(field.asType()).append(' ');
        sb.append(field.getSimpleName());
        Object constValue = field.getConstantValue();
        if (constValue != null) {
            if (constValue instanceof String s) {
                sb.append(" = \"").append(s).append('"');
            } else {
                sb.append(" = ").append(constValue);
            }
        }
        sb.append('\n');
    }

    private static void appendMethod(StringBuilder sb, ExecutableElement method, int indent) {
        sb.append("  ".repeat(indent));
        appendModifiers(sb, method.getModifiers());

        var typeParams = method.getTypeParameters();
        if (!typeParams.isEmpty()) {
            sb.append('<');
            sb.append(
                    typeParams.stream().map(tp -> tp.getSimpleName().toString()).collect(Collectors.joining(", ")));
            sb.append("> ");
        }

        if (method.getKind() != ElementKind.CONSTRUCTOR) {
            sb.append(method.getReturnType()).append(' ');
        }

        sb.append(method.getSimpleName()).append('(');
        sb.append(
                method.getParameters().stream().map(p -> p.asType().toString()).collect(Collectors.joining(", ")));
        sb.append(')');

        var thrown = method.getThrownTypes();
        if (!thrown.isEmpty()) {
            sb.append(" throws ");
            sb.append(thrown.stream().map(TypeMirror::toString).collect(Collectors.joining(", ")));
        }
        sb.append('\n');
    }

    private static void appendModifiers(StringBuilder sb, Set<Modifier> modifiers) {
        for (Modifier m : List.of(
                Modifier.PUBLIC,
                Modifier.PROTECTED,
                Modifier.ABSTRACT,
                Modifier.STATIC,
                Modifier.FINAL,
                Modifier.SYNCHRONIZED,
                Modifier.NATIVE,
                Modifier.STRICTFP,
                Modifier.DEFAULT,
                Modifier.SEALED,
                Modifier.NON_SEALED)) {
            if (modifiers.contains(m)) {
                sb.append(m).append(' ');
            }
        }
    }
}
