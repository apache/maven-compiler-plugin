package org.issue;

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
