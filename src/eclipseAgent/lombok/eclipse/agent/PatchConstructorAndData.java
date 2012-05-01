/*
 * Copyright (C) 2010-2012 The Project Lombok Authors.
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
package lombok.eclipse.agent;

import static lombok.eclipse.handlers.EclipseHandlerUtil.createAnnotation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.core.AnnotationValues.AnnotationValueDecodeFail;
import lombok.eclipse.EclipseAST;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.TransformEclipseAST;
import lombok.eclipse.handlers.HandleData;
import lombok.eclipse.handlers.HandleConstructor.HandleAllArgsConstructor;
import lombok.eclipse.handlers.HandleConstructor.HandleRequiredArgsConstructor;
import lombok.eclipse.handlers.HandleConstructor.HandleNoArgsConstructor;

import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

public final class PatchConstructorAndData {

	// TODO cleanup this mess
	public static boolean onClassScope_buildFieldsAndMethods(ClassScope scope) {
		TypeDeclaration decl = scope.referenceContext;
		if (decl == null) return false;
		EclipseNode typeNode = getTypeNode(decl);
		
		{ // @Data
			Annotation ann = getAnnotation(Data.class, decl);
			if ((ann != null) && (typeNode != null)) {
				EclipseNode annotationNode = typeNode.getNodeFor(ann);
				try {
					new HandleData().handle(createAnnotation(Data.class, annotationNode), ann, annotationNode);
				} catch (AnnotationValueDecodeFail fail) {
					fail.owner.setError(fail.getMessage(), fail.idx);
				}
			}
		}
		
		{ // @AllArgsConstructor
			Annotation ann = getAnnotation(AllArgsConstructor.class, decl);
			if ((ann != null) && (typeNode != null)) {
				EclipseNode annotationNode = typeNode.getNodeFor(ann);
				try {
					new HandleAllArgsConstructor().handle(createAnnotation(AllArgsConstructor.class, annotationNode), ann, annotationNode);
				} catch (AnnotationValueDecodeFail fail) {
					fail.owner.setError(fail.getMessage(), fail.idx);
				}
			}
		}
		
		{ // @RequiredArgsConstructor
			Annotation ann = getAnnotation(RequiredArgsConstructor.class, decl);
			if ((ann != null) && (typeNode != null)) {
				EclipseNode annotationNode = typeNode.getNodeFor(ann);
				try {
					new HandleRequiredArgsConstructor().handle(createAnnotation(RequiredArgsConstructor.class, annotationNode), ann, annotationNode);
				} catch (AnnotationValueDecodeFail fail) {
					fail.owner.setError(fail.getMessage(), fail.idx);
				}
			}
		}
		
		{ // @NoArgsConstructor
			Annotation ann = getAnnotation(NoArgsConstructor.class, decl);
			if ((ann != null) && (typeNode != null)) {
				EclipseNode annotationNode = typeNode.getNodeFor(ann);
				try {
					new HandleNoArgsConstructor().handle(createAnnotation(NoArgsConstructor.class, annotationNode), ann, annotationNode);
				} catch (AnnotationValueDecodeFail fail) {
					fail.owner.setError(fail.getMessage(), fail.idx);
				}
			}
		}
		
		return false;
	}
	
	public static Annotation getAnnotation(final Class<? extends java.lang.annotation.Annotation> expectedType, final TypeDeclaration decl) {
		if (decl.annotations != null) for (Annotation ann : decl.annotations) {
			if (matchesType(ann, expectedType, decl)) {
				return ann;
			}
		}
		return null;
	}
	
	private static boolean matchesType(final Annotation ann, final Class<?> expectedType, final TypeDeclaration decl) {
		if (ann.type == null) return false;
		TypeBinding tb = ann.resolvedType;
		if ((tb == null) && (ann.type != null)) {
			try {
				tb = ann.type.resolveType(decl.initializerScope);
			} catch (final Exception ignore) {
				// completion nodes may throw an exception here
			}
		}
		if (tb == null) return false;
		return new String(tb.readableName()).equals(expectedType.getName());
	}

	public static EclipseNode getTypeNode(final TypeDeclaration decl) {
		CompilationUnitDeclaration cud = decl.scope.compilationUnitScope().referenceContext;
		EclipseAST astNode = TransformEclipseAST.getAST(cud, false);
		EclipseNode node = astNode.get(decl);
		if (node == null) {
			astNode = TransformEclipseAST.getAST(cud, true);
			node = astNode.get(decl);
		}
		return node;
	}
}
