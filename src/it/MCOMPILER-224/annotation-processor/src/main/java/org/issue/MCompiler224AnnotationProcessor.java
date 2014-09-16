package org.issue;

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


import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/* @formatter:off */
@SupportedAnnotationTypes( { 
    "org.issue.MCompiler224"
} )
/* @formatter:on */
@SupportedSourceVersion( SourceVersion.RELEASE_6 )
public class MCompiler224AnnotationProcessor
    extends AbstractProcessor
{

    @Override
    public boolean process( final Set<? extends TypeElement> elts, final RoundEnvironment env )
    {
        if ( elts.isEmpty() )
        {
            return true;
        }

        final Messager messager = this.processingEnv.getMessager();

        for ( final Kind kind : Kind.values() )
        {
            if ( Kind.ERROR == kind )
            {
                continue;
            }

            System.out.println( "Testing message for: " + kind );
            messager.printMessage( kind, kind + " Test message." );
        }

        return true;
    }

}
