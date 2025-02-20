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
package foo;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

@SupportedAnnotationTypes("foo.MyAnnotation")
public class MyProcessor extends AbstractProcessor {

    private int procRun = 0;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            generate(annotations, roundEnv);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return false;
    }

    private void generate(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) throws IOException {
        Messager msg = processingEnv.getMessager();
        msg.printMessage(Diagnostic.Kind.NOTE, String.format("[%d]: Hello Info", procRun));
        msg.printMessage(Diagnostic.Kind.WARNING, String.format("[%d]: Hello Warning", procRun));
        procRun++;

        if (procRun > 1) {
            return;
        }

        JavaFileObject file = processingEnv.getFiler().createSourceFile("bar.MyGeneratedClass");
        try (Writer writer = file.openWriter()) {
            writer.write("package bar;\n\npublic class MyGeneratedClass {\n}\n");
        }
    }
}
