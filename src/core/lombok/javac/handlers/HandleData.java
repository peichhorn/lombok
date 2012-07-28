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
package lombok.javac.handlers;

import static lombok.javac.handlers.JavacHandlerUtil.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.core.AnnotationValues;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.handlers.HandleConstructor.FieldProvider;
import lombok.javac.handlers.HandleConstructor.ConstructorData;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.tree.JCTree.JCAnnotation;

/**
 * Handles the {@code lombok.Data} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class) public class HandleData extends JavacAnnotationHandler<Data> {
	@Override public void handle(AnnotationValues<Data> annotation, JCAnnotation ast, JavacNode annotationNode) {
		JavacNode typeNode = annotationNode.up();
		boolean notAClass = !isClass(typeNode);
		
		if (notAClass) {
			annotationNode.addError("@Data is only supported on a class.");
			return;
		}
		
		Data data = annotation.getInstance();
		String staticConstructorName = data.staticConstructor();
		boolean callSuper = data.callSuper();
		
		// TODO move this to the end OR move it to the top in eclipse.
		final ConstructorData cData = new ConstructorData() //
			.fieldProvider(FieldProvider.REQUIRED) //
			.accessLevel(AccessLevel.PUBLIC) //
			.staticName(staticConstructorName) //
			.callSuper(callSuper);
		if (!HandleConstructor.constructorOrConstructorAnnotationExists(typeNode)) {
			new HandleConstructor().generateConstructor(typeNode, ast, cData);
		} else {
			if (cData.staticConstructorRequired()) {
				annotationNode.addWarning("Ignoring static constructor name: explicit @XxxArgsConstructor annotation present; its `staticName` parameter will be used.");
			}
		}
		
		new HandleGetter().generateGetterForType(typeNode, annotationNode, AccessLevel.PUBLIC, true);
		new HandleSetter().generateSetterForType(typeNode, annotationNode, AccessLevel.PUBLIC, true);
		
		new HandleEqualsAndHashCode().generateEqualsAndHashCodeForType(typeNode, annotationNode, callSuper);
		new HandleToString().generateToStringForType(typeNode, annotationNode, callSuper);
	}
}
