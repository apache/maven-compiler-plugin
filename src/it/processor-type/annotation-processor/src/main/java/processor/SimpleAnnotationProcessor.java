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
package processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes("user.SimpleAnnotation")
public class SimpleAnnotationProcessor extends AbstractProcessor {
    public SimpleAnnotationProcessor() {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Verifies that transitive dependencies worked.
        // TODO: pending https://github.com/apache/maven/pull/11373
        // dependency.AnnotationProcessorDependency.foo();

        boolean claimed = false;
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                Name packageName =
                        processingEnv.getElementUtils().getPackageOf(element).getQualifiedName();
                Name name = element.getSimpleName();
                try {
                    FileObject resource = processingEnv
                            .getFiler()
                            .createResource(StandardLocation.SOURCE_OUTPUT, packageName, name + ".txt", element);
                    try (Writer writer = resource.openWriter()) {
                        writer.write(name.toString());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                claimed = true;
            }
        }
        return claimed;
    }
}
