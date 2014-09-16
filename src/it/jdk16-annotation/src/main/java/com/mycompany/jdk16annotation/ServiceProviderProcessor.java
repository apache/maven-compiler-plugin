package com.mycompany.jdk16annotation;

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


import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class ServiceProviderProcessor extends AbstractProcessor {

    public @Override Set<String> getSupportedAnnotationTypes() {
        return new HashSet<String>(Arrays.asList(
            ServiceProvider.class.getCanonicalName()
        ));
    }

    /** public for ServiceLoader */
    public ServiceProviderProcessor() {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.errorRaised()) {
            return false;
        }
        if (roundEnv.processingOver()) {
            writeServices();
            return true;
        } else {
            return true;
        }

    }

    private void writeServices() {
        try {
            FileObject out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/one",new Element[0]);
            OutputStream os = out.openOutputStream();
            OutputStream os2 = processingEnv.getFiler().createSourceFile("org.Milos", new Element[0]).openOutputStream();
            OutputStreamWriter osr = new OutputStreamWriter(os2);
            try {
                PrintWriter w = new PrintWriter(new OutputStreamWriter(os, "UTF-8"));
                w.write("test");
                w.flush();
                String clazz = "package org;\n class Milos {}";
                osr.write(clazz.toCharArray());
                osr.flush();
            } finally {
                osr.close();
                os.close();
            }


        } catch (IOException x) {
            processingEnv.getMessager().printMessage(Kind.ERROR, "Failed to write to one: " + x.toString());
        }
    }

}
