package org.issue;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedSourceVersion(SourceVersion.RELEASE_6)
@SupportedAnnotationTypes("org.issue.SimpleAnnotation")
public class SimpleAnnotationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
	Filer filer = processingEnv.getFiler();

	Elements elementUtils = processingEnv.getElementUtils();

	Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(SimpleAnnotation.class);

	for (Element element : elements) {
	    Name name = element.getSimpleName();

	    PackageElement packageElement = elementUtils.getPackageOf(element);

	    try {
		FileObject resource = filer.createResource(StandardLocation.SOURCE_OUTPUT, packageElement.getQualifiedName(), name + ".txt", element);

		Writer writer = resource.openWriter();
		writer.write(name.toString());
		writer.close();
	    } catch (IOException e) {
		throw new RuntimeException(e);
	    }
	}

	return !elements.isEmpty();
    }

}
