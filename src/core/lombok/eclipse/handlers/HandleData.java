/*
 * Copyright (C) 2009-2012 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import lombok.AccessLevel;
import lombok.Data;
import lombok.core.AnnotationValues;
import lombok.eclipse.DeferUntilBuildFieldsAndMethods;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.handlers.HandleConstructor;
import lombok.eclipse.handlers.HandleConstructor.ConstructorData;
import lombok.eclipse.handlers.HandleConstructor.FieldProvider;

import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.mangosdk.spi.ProviderFor;

/**
 * Handles the {@code lombok.Data} annotation for eclipse.
 */
@ProviderFor(EclipseAnnotationHandler.class)
@DeferUntilBuildFieldsAndMethods
public class HandleData extends EclipseAnnotationHandler<Data> {
	public void handle(AnnotationValues<Data> annotation, Annotation ast, EclipseNode annotationNode) {
		EclipseNode typeNode = annotationNode.up();
		
		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers &
				(ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation | ClassFileConstants.AccEnum)) != 0;
		
		if (typeDecl == null || notAClass) {
			annotationNode.addError("@Data is only supported on a class.");
			return;
		}
		
		//Careful: Generate the public static constructor (if there is one) LAST, so that any attempt to
		//'find callers' on the annotation node will find callers of the constructor, which is by far the
		//most useful of the many methods built by @Data. This trick won't work for the non-static constructor,
		//for whatever reason, though you can find callers of that one by focusing on the class name itself
		//and hitting 'find callers'.
		Data data = annotation.getInstance();
		String staticConstructorName = data.staticConstructor();
		boolean callSuper = data.callSuper();
		
		new HandleGetter().generateGetterForType(typeNode, annotationNode, AccessLevel.PUBLIC, true);
		new HandleSetter().generateSetterForType(typeNode, annotationNode, AccessLevel.PUBLIC, true);
		
		new HandleEqualsAndHashCode().generateEqualsAndHashCodeForType(typeNode, annotationNode, callSuper);
		new HandleToString().generateToStringForType(typeNode, annotationNode, callSuper);
		
		if (!HandleConstructor.constructorOrConstructorAnnotationExists(typeNode)) {
			final ConstructorData cData = new ConstructorData() //
				.fieldProvider(FieldProvider.REQUIRED) //
				.accessLevel(AccessLevel.PUBLIC) //
				.staticName(staticConstructorName) //
				.callSuper(callSuper);
			if (cData.staticConstructorRequired()) {
				annotationNode.addWarning("Ignoring static constructor name: explicit @XxxArgsConstructor annotation present; its `staticName` parameter will be used.");
			}
			new HandleConstructor().generateConstructor(typeNode, ast, cData);
		}
	}
}
