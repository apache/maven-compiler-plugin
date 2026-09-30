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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * Walks the AST after ANALYZE to extract type dependencies, classifying each
 * as signature-level (appears in public API) or implementation-level (body-only).
 */
public class DependencyScanner extends TreePathScanner<Void, Void> {

    private final Trees trees;
    private final Set<String> signatureDeps = new TreeSet<>();
    private final Set<String> implementationDeps = new TreeSet<>();
    private int bodyDepth = 0;
    private boolean inPrivateMember = false;

    public DependencyScanner(Trees trees) {
        this.trees = trees;
    }

    public Set<String> getSignatureDeps() {
        return Collections.unmodifiableSet(signatureDeps);
    }

    public Set<String> getImplementationDeps() {
        return Collections.unmodifiableSet(implementationDeps);
    }

    @Override
    public Void visitImport(ImportTree node, Void p) {
        // Skip imports — they don't create dependencies, the actual usage sites do.
        // Without this, all imported types appear as signature deps regardless of
        // where they're actually used.
        return null;
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        Element el = resolveElement(getCurrentPath());
        boolean wasPrivate = inPrivateMember;
        if (el != null && el.getModifiers().contains(Modifier.PRIVATE)) {
            inPrivateMember = true;
        }

        // Signature parts: return type, parameters, throws, type params, annotations
        scan(node.getModifiers(), p);
        scan(node.getTypeParameters(), p);
        scan(node.getReturnType(), p);
        scan(node.getParameters(), p);
        scan(node.getThrows(), p);
        scan(node.getDefaultValue(), p);

        // Body: implementation context
        if (node.getBody() != null) {
            bodyDepth++;
            scan(node.getBody(), p);
            bodyDepth--;
        }

        inPrivateMember = wasPrivate;
        return null;
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        TreePath parentPath = getCurrentPath().getParentPath();
        if (parentPath != null && parentPath.getLeaf() instanceof ClassTree) {
            // Field: type is signature (if non-private), initializer is implementation
            Element el = resolveElement(getCurrentPath());
            boolean wasPrivate = inPrivateMember;
            if (el != null && el.getModifiers().contains(Modifier.PRIVATE)) {
                inPrivateMember = true;
            }

            scan(node.getModifiers(), p);
            scan(node.getType(), p);

            if (node.getInitializer() != null) {
                bodyDepth++;
                scan(node.getInitializer(), p);
                bodyDepth--;
            }

            inPrivateMember = wasPrivate;
            return null;
        }
        return super.visitVariable(node, p);
    }

    @Override
    public Void visitBlock(BlockTree node, Void p) {
        // Static/instance initializer blocks (direct children of ClassTree)
        TreePath parentPath = getCurrentPath().getParentPath();
        if (parentPath != null && parentPath.getLeaf() instanceof ClassTree) {
            bodyDepth++;
            var result = super.visitBlock(node, p);
            bodyDepth--;
            return result;
        }
        return super.visitBlock(node, p);
    }

    @Override
    public Void visitAnnotation(AnnotationTree node, Void p) {
        // Record the annotation type itself
        recordReference(getCurrentPath());
        // Visit annotation arguments — this catches types used in annotation values
        // such as @Foo(SomeEnum.VALUE) or @Foo(SomeType.class), where SomeEnum/SomeType
        // need to be tracked as dependencies even if not in any method signature.
        return super.visitAnnotation(node, p);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Void p) {
        recordReference(getCurrentPath());
        return super.visitIdentifier(node, p);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void p) {
        recordReference(getCurrentPath());
        return super.visitMemberSelect(node, p);
    }

    private void recordReference(TreePath path) {
        Element element = resolveElement(path);
        if (element == null) {
            return;
        }

        TypeElement typeElement;
        if (element instanceof TypeElement te) {
            typeElement = te;
        } else if (element instanceof ExecutableElement ee) {
            typeElement = enclosingType(ee);
        } else if (element instanceof VariableElement ve) {
            typeElement = enclosingType(ve);
        } else {
            typeElement = null;
        }

        if (typeElement == null) {
            return;
        }

        String qname = typeElement.getQualifiedName().toString();
        if (qname.isEmpty()) {
            return;
        }

        if (bodyDepth > 0 || inPrivateMember) {
            implementationDeps.add(qname);
        } else {
            signatureDeps.add(qname);
        }
    }

    private TypeElement enclosingType(Element element) {
        Element enclosing = element.getEnclosingElement();
        return enclosing instanceof TypeElement te ? te : null;
    }

    private Element resolveElement(TreePath path) {
        try {
            return trees.getElement(path);
        } catch (IllegalArgumentException | NullPointerException e) {
            // javac may throw for synthetic elements, error types, or unresolved symbols
            return null;
        }
    }
}
