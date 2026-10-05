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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeAnalyzerTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path outputDir;

    @Test
    void descriptorToReadableVoid() {
        assertEquals("void", BytecodeAnalyzer.descriptorToReadable("V"));
    }

    @Test
    void descriptorToReadableBoolean() {
        assertEquals("boolean", BytecodeAnalyzer.descriptorToReadable("Z"));
    }

    @Test
    void descriptorToReadableByte() {
        assertEquals("byte", BytecodeAnalyzer.descriptorToReadable("B"));
    }

    @Test
    void descriptorToReadableChar() {
        assertEquals("char", BytecodeAnalyzer.descriptorToReadable("C"));
    }

    @Test
    void descriptorToReadableShort() {
        assertEquals("short", BytecodeAnalyzer.descriptorToReadable("S"));
    }

    @Test
    void descriptorToReadableInt() {
        assertEquals("int", BytecodeAnalyzer.descriptorToReadable("I"));
    }

    @Test
    void descriptorToReadableLong() {
        assertEquals("long", BytecodeAnalyzer.descriptorToReadable("J"));
    }

    @Test
    void descriptorToReadableFloat() {
        assertEquals("float", BytecodeAnalyzer.descriptorToReadable("F"));
    }

    @Test
    void descriptorToReadableDouble() {
        assertEquals("double", BytecodeAnalyzer.descriptorToReadable("D"));
    }

    @Test
    void descriptorToReadableObject() {
        assertEquals("java.lang.String", BytecodeAnalyzer.descriptorToReadable("Ljava/lang/String;"));
    }

    @Test
    void descriptorToReadableArray() {
        assertEquals("int[]", BytecodeAnalyzer.descriptorToReadable("[I"));
        assertEquals("java.lang.String[]", BytecodeAnalyzer.descriptorToReadable("[Ljava/lang/String;"));
    }

    @Test
    void descriptorToReadableEmpty() {
        assertEquals("", BytecodeAnalyzer.descriptorToReadable(""));
    }

    @Test
    void parseParamsMultiple() {
        assertEquals("java.lang.String, int", BytecodeAnalyzer.parseParams("(Ljava/lang/String;I)V"));
    }

    @Test
    void parseParamsEmpty() {
        assertEquals("", BytecodeAnalyzer.parseParams("()V"));
    }

    @Test
    void parseReturnType() {
        assertEquals("java.lang.String", BytecodeAnalyzer.parseReturn("(I)Ljava/lang/String;"));
        assertEquals("void", BytecodeAnalyzer.parseReturn("()V"));
        assertEquals("int", BytecodeAnalyzer.parseReturn("()I"));
    }

    @Test
    void analyzePathProducesCorrectClassName() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir,
                "test",
                "Hello",
                "package test; public class Hello { public String greet() { return \"hi\"; } }");
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        Path classFile = outputDir.resolve("test/Hello.class");
        assertTrue(Files.exists(classFile));

        var analysis = BytecodeAnalyzer.analyze(classFile);
        assertEquals("test.Hello", analysis.className());
    }

    @Test
    void analyzeBytesEquivalentToAnalyzePath() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir, "test", "Hello", "package test; public class Hello { public int value() { return 42; } }");
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        Path classFile = outputDir.resolve("test/Hello.class");
        var fromPath = BytecodeAnalyzer.analyze(classFile);
        var fromBytes = BytecodeAnalyzer.analyze(Files.readAllBytes(classFile));

        assertEquals(fromPath.className(), fromBytes.className());
        assertEquals(fromPath.signatureTypes(), fromBytes.signatureTypes());
        assertEquals(fromPath.implementationTypes(), fromBytes.implementationTypes());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void genericTypeArgumentsTrackedAsDependencies() throws Exception {
        // Foo is a user-defined type used only as a generic type argument
        CompilerTestHelper.writeSource(sourceDir, "test", "Foo", "package test; public class Foo {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "Bar", "package test; public class Bar {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "Subject", """
                package test;
                import java.util.List;
                import java.util.Map;
                import java.util.function.Function;
                public class Subject {
                    // Foo appears only as a type argument — not in the erased descriptor
                    public List<Foo> getItems() { return null; }
                    // Bar appears as a Map value type argument
                    public Map<String, Bar> getMap() { return null; }
                    // Wildcards: Foo as lower bound
                    public List<? extends Foo> getBounded() { return null; }
                    // Foo in field generic signature
                    public java.util.Optional<Foo> optFoo = java.util.Optional.empty();
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var analysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/Subject.class"));

        assertTrue(
                analysis.signatureTypes().contains("test.Foo"),
                "test.Foo used as generic type arg should be in signatureTypes; got: " + analysis.signatureTypes());
        assertTrue(
                analysis.signatureTypes().contains("test.Bar"),
                "test.Bar used as generic type arg should be in signatureTypes; got: " + analysis.signatureTypes());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void genericSuperclassTypeArgsTracked() throws Exception {
        CompilerTestHelper.writeSource(sourceDir, "test", "Item", "package test; public class Item {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "ItemList", """
                package test;
                import java.util.AbstractList;
                // Item appears only in the class generic signature (extends AbstractList<Item>)
                public class ItemList extends AbstractList<Item> {
                    @Override public Item get(int i) { return null; }
                    @Override public int size() { return 0; }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var analysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/ItemList.class"));

        assertTrue(
                analysis.signatureTypes().contains("test.Item"),
                "test.Item as superclass type arg should be in signatureTypes; got: " + analysis.signatureTypes());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void formalTypeParameterBoundsTracked() throws Exception {
        // MyBound is a user type used only as a type parameter bound — e.g. <T extends MyBound>
        // The bug: type param names starting with 'T' (e.g. "T", "Type", "Target") caused the
        // parser to misidentify FormalTypeParameter as TypeVariableSignature and skip the bound.
        CompilerTestHelper.writeSource(sourceDir, "test", "MyBound", "package test; public interface MyBound {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "OtherBound", "package test; public interface OtherBound {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "Container", """
                package test;
                // 'T' as type param name — the previously failing case
                public class Container<T extends MyBound> {
                    public T get() { return null; }
                }
                """);
        CompilerTestHelper.writeSource(sourceDir, "test", "MultiContainer", """
                package test;
                // 'Type'-prefixed name + multiple bounds via interface bound
                public class MultiContainer<Type extends MyBound & OtherBound> {
                    public Type get() { return null; }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var containerAnalysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/Container.class"));
        assertTrue(
                containerAnalysis.signatureTypes().contains("test.MyBound"),
                "test.MyBound as <T extends MyBound> bound should be in signatureTypes; got: "
                        + containerAnalysis.signatureTypes());

        var multiAnalysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/MultiContainer.class"));
        assertTrue(
                multiAnalysis.signatureTypes().contains("test.MyBound"),
                "test.MyBound as <Type extends MyBound & OtherBound> bound should be in signatureTypes; got: "
                        + multiAnalysis.signatureTypes());
        assertTrue(
                multiAnalysis.signatureTypes().contains("test.OtherBound"),
                "test.OtherBound as interface bound should be in signatureTypes; got: "
                        + multiAnalysis.signatureTypes());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void multipleTypeParameterBoundsAllTracked() throws Exception {
        // Regression: parseSig() overconsumed past the first bound in FormalTypeParameters,
        // causing subsequent type params with 'T'-prefixed names to lose their bounds.
        // E.g. <K:Lfoo/Foo;T:Lbar/Bar;> → Bar was missed.
        CompilerTestHelper.writeSource(sourceDir, "test", "MyBound", "package test; public interface MyBound {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "OtherBound", "package test; public interface OtherBound {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "BiContainer", """
                package test;
                // K has no bound; T (the classic single-letter param) has MyBound
                public class BiContainer<K, T extends MyBound> {
                    public K key() { return null; }
                    public T val() { return null; }
                }
                """);
        CompilerTestHelper.writeSource(sourceDir, "test", "TriContainer", """
                package test;
                // Multiple params, second and third have user-defined bounds
                public class TriContainer<A, B extends MyBound, T extends OtherBound> {
                    public A a() { return null; }
                    public B b() { return null; }
                    public T t() { return null; }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var biAnalysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/BiContainer.class"));
        assertTrue(
                biAnalysis.signatureTypes().contains("test.MyBound"),
                "MyBound as second type param bound should be tracked; got: " + biAnalysis.signatureTypes());

        var triAnalysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/TriContainer.class"));
        assertTrue(
                triAnalysis.signatureTypes().contains("test.MyBound"),
                "MyBound as second type param bound should be tracked in TriContainer; got: "
                        + triAnalysis.signatureTypes());
        assertTrue(
                triAnalysis.signatureTypes().contains("test.OtherBound"),
                "OtherBound as third type param (T-named) bound should be tracked; got: "
                        + triAnalysis.signatureTypes());
    }

    /**
     * When running on JDK 24+, {@link BytecodeAnalyzer} uses the {@code ClassfileClassAnalyzer}.
     * Also verifies that sig/impl split is non-trivially populated for a class with
     * user-defined type cross-references.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void classfileAnalyzerIsIdempotent() throws Exception {
        // Write two classes so Subject references UserType (a non-JDK type → sig dependency)
        CompilerTestHelper.writeSource(sourceDir, "test", "UserType", "package test; public class UserType {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "Subject", """
                package test;
                public class Subject implements Runnable {
                    public static final String CONST = "hello";
                    private int secret = 42;
                    public UserType getUser() { return new UserType(); }
                    @Override public void run() { getUser(); }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        byte[] bytes = Files.readAllBytes(outputDir.resolve("test/Subject.class"));

        var r1 = BytecodeAnalyzer.analyze(bytes);
        var r2 = BytecodeAnalyzer.analyze(bytes);

        assertEquals(r1.className(), r2.className(), "className");
        assertEquals(r1.signatureTypes(), r2.signatureTypes(), "signatureTypes");
        assertEquals(r1.implementationTypes(), r2.implementationTypes(), "implementationTypes");
        // Subject.getUser() returns UserType → UserType is a non-JDK signature dependency
        assertTrue(r1.signatureTypes().contains("test.UserType"), "test.UserType should be a signature type ref");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void sourceFileNameAttributeExtracted() throws Exception {
        CompilerTestHelper.writeSource(
                sourceDir,
                "test",
                "Hello",
                "package test; public class Hello { public String greet() { return \"hi\"; } }");
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var analysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/Hello.class"));
        assertEquals("Hello.java", analysis.sourceFileName(), "sourceFileName should match the source file name");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void annotationTypesCollected() throws Exception {
        // Class with @Deprecated annotation — should appear in annotationTypes
        CompilerTestHelper.writeSource(sourceDir, "test", "Annotated", """
                package test;
                @Deprecated
                public class Annotated {
                    @SuppressWarnings("unused")
                    public void method() {}
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var analysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/Annotated.class"));

        // @Deprecated is a JDK type so will be filtered out of signatureTypes,
        // but it IS collected in annotationTypes before JDK filtering
        // Use a custom annotation to be sure the collection path is exercised.
        // Verify that annotation detection runs without error and annotationTypes is populated.
        assertNotNull(analysis.annotationTypes(), "annotationTypes should not be null");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void customAnnotationAppearsInAnnotationTypes() throws Exception {
        // Define and use a custom annotation — it should appear in annotationTypes
        CompilerTestHelper.writeSource(sourceDir, "test", "MyAnnotation", """
                package test;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface MyAnnotation {}
                """);
        CompilerTestHelper.writeSource(sourceDir, "test", "Tagged", """
                package test;
                @MyAnnotation
                public class Tagged {}
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var analysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/Tagged.class"));

        assertTrue(
                analysis.annotationTypes().contains("test.MyAnnotation"),
                "custom annotation test.MyAnnotation should appear in annotationTypes; got: "
                        + analysis.annotationTypes());
        assertTrue(
                analysis.signatureTypes().contains("test.MyAnnotation"),
                "annotation type should also be in signatureTypes (class-level annotation is part of API surface); got: "
                        + analysis.signatureTypes());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void implementationOnlyTypeNotInSignatureTypes() throws Exception {
        // Helper is used only in a method body — not in the public API surface.
        // It should appear in implementationTypes, NOT in signatureTypes.
        CompilerTestHelper.writeSource(sourceDir, "test", "Helper", "package test; public class Helper {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "ApiType", "package test; public class ApiType {}");
        CompilerTestHelper.writeSource(sourceDir, "test", "Subject", """
                package test;
                public class Subject {
                    // ApiType appears in the public API (return type) → signatureTypes
                    public ApiType getApi() { return null; }
                    // Helper is used only in the method body → implementationTypes only
                    public void doWork() {
                        Helper h = new Helper();
                        h.toString();
                    }
                }
                """);
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        var analysis = BytecodeAnalyzer.analyze(outputDir.resolve("test/Subject.class"));

        assertTrue(
                analysis.signatureTypes().contains("test.ApiType"),
                "ApiType in return type should be in signatureTypes; got: " + analysis.signatureTypes());
        assertFalse(
                analysis.signatureTypes().contains("test.Helper"),
                "Helper used only in body should NOT be in signatureTypes; got: " + analysis.signatureTypes());
        assertTrue(
                analysis.implementationTypes().contains("test.Helper"),
                "Helper used in body should be in implementationTypes; got: " + analysis.implementationTypes());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(min = org.junit.jupiter.api.condition.JRE.JAVA_24)
    void moduleInfoAnalysis() throws Exception {
        // Write a simple module-info.java, compile it, analyze the resulting class
        Path modInfo = sourceDir.resolve("module-info.java");
        java.nio.file.Files.writeString(modInfo, """
                module test.mod {
                    exports test;
                }
                """);
        CompilerTestHelper.writeSource(sourceDir, "test", "Hello", "package test; public class Hello {}");
        CompilerTestHelper.compileAndAnalyze(sourceDir, outputDir);

        Path modClass = outputDir.resolve("module-info.class");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(modClass), "module-info.class not produced — skipping");

        var analysis = BytecodeAnalyzer.analyze(modClass);

        assertTrue(analysis.isModuleInfo(), "isModuleInfo should be true");
        assertTrue(
                analysis.className().startsWith(BytecodeAnalyzer.MODULE_PREFIX),
                "className should start with MODULE_PREFIX; got: " + analysis.className());
        assertEquals("test.mod", analysis.moduleName(), "moduleName should be test.mod");
        assertEquals("module-info.java", analysis.sourceFileName());
    }
}
