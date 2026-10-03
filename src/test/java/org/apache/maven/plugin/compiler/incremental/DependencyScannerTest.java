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

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyScannerTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path outputDir;

    @BeforeEach
    void setUp() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir, "dep", "Model", "package dep; public class Model { public String name() { return \"\"; } }");
        CompilerTestHelper.writeSource(
                sourceDir,
                "dep",
                "MyException",
                "package dep; public class MyException extends Exception { public MyException(String msg) { super(msg); } }");
        CompilerTestHelper.writeSource(
                sourceDir, "dep", "Helper", "package dep; public class Helper { public void help() {} }");
    }

    private SourceFileAnalysis analyze(String source) throws Exception {
        CompilerTestHelper.writeSource(sourceDir, "test", "Subject", source);
        Map<String, SourceFileAnalysis> results = CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);
        return results.get("test.Subject");
    }

    @Test
    void extendsIsSignatureDep() throws Exception {
        var a = analyze("package test; import dep.Model; public class Subject extends Model { }");
        assertTrue(a.signatureDeps().contains("dep.Model"), "extends type should be signature dep");
    }

    @Test
    void implementsIsSignatureDep() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir, "dep", "Iface", "package dep; public interface Iface { void run(); }");
        var a = analyze(
                "package test; import dep.Iface; public class Subject implements Iface { public void run() {} }");
        assertTrue(a.signatureDeps().contains("dep.Iface"), "implements type should be signature dep");
    }

    @Test
    void returnTypeIsSignatureDep() throws Exception {
        var a = analyze("package test; import dep.Model; public class Subject { public Model get() { return null; } }");
        assertTrue(a.signatureDeps().contains("dep.Model"), "return type should be signature dep");
    }

    @Test
    void parameterTypeIsSignatureDep() throws Exception {
        var a = analyze("package test; import dep.Model; public class Subject { public void set(Model m) { } }");
        assertTrue(a.signatureDeps().contains("dep.Model"), "parameter type should be signature dep");
    }

    @Test
    void throwsTypeIsSignatureDep() throws Exception {
        var a = analyze(
                "package test; import dep.MyException; public class Subject { public void run() throws MyException { } }");
        assertTrue(a.signatureDeps().contains("dep.MyException"), "throws type should be signature dep");
    }

    @Test
    void bodyOnlyUsageIsImplementationDep() throws Exception {
        var a = analyze(
                "package test; import dep.Helper; public class Subject { public void run() { new Helper().help(); } }");
        assertTrue(a.implementationDeps().contains("dep.Helper"), "body-only usage should be impl dep");
        assertFalse(a.signatureDeps().contains("dep.Helper"), "body-only usage should not be sig dep");
    }

    @Test
    void privateFieldIsImplementationDep() throws Exception {
        var a = analyze(
                "package test; import dep.Helper; public class Subject { private Helper helper = new Helper(); }");
        assertTrue(a.implementationDeps().contains("dep.Helper"), "private field type should be impl dep");
        assertFalse(a.signatureDeps().contains("dep.Helper"), "private field type should not be sig dep");
    }

    @Test
    void publicFieldTypeIsSignatureDep() throws Exception {
        var a = analyze("package test; import dep.Model; public class Subject { public Model model; }");
        assertTrue(a.signatureDeps().contains("dep.Model"), "public field type should be signature dep");
    }

    @Test
    void importDoesNotCreateFalseSignatureDep() throws Exception {
        var a = analyze(
                "package test; import dep.Helper; public class Subject { public void run() { new Helper().help(); } }");
        assertFalse(a.signatureDeps().contains("dep.Helper"), "import should not create false signature dep");
        assertTrue(a.implementationDeps().contains("dep.Helper"), "actual usage in body should be impl dep");
    }

    @Test
    void fieldInitializerIsImplementationDep() throws Exception {
        var a = analyze(
                "package test; import dep.Helper; public class Subject { public String name = new Helper().toString(); }");
        assertTrue(
                a.implementationDeps().contains("dep.Helper"), "field initializer type should be implementation dep");
    }

    @Test
    void noSelfReferences() throws Exception {
        var a = analyze("package test; public class Subject { public Subject self() { return this; } }");
        assertFalse(a.signatureDeps().contains("test.Subject"), "should not contain self-reference");
        assertFalse(a.implementationDeps().contains("test.Subject"), "should not contain self-reference");
    }

    @Test
    void jdkTypesFiltered() throws Exception {
        var a = analyze("package test; public class Subject { public String name() { return \"\"; } }");
        assertFalse(a.signatureDeps().contains("java.lang.String"), "JDK types should be filtered");
    }
}
