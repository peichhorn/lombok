/*
 * Copyright (C) 2009-2011 The Project Lombok Authors.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import lombok.SneakyThrows;
import lombok.core.AnnotationValues;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;

/**
 * Handles the {@code lombok.SneakyThrows} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleSneakyThrows extends JavacAnnotationHandler<SneakyThrows> {
	@Override public void handle(AnnotationValues<SneakyThrows> annotation, JCAnnotation ast, JavacNode annotationNode) {
		Collection<String> exceptionNames = annotation.getRawExpressions("value");
		if (exceptionNames.isEmpty()) {
			exceptionNames = Collections.singleton("java.lang.Throwable");
		}
		
		java.util.List<String> exceptions = new ArrayList<String>();
		for (String exception : exceptionNames) {
			if (exception.endsWith(".class")) exception = exception.substring(0, exception.length() - 6);
			exceptions.add(exception);
		}
		
		JavacNode owner = annotationNode.up();
		switch (owner.getKind()) {
		case METHOD:
			handleMethod(annotationNode, (JCMethodDecl)owner.get(), exceptions);
			break;
		default:
			annotationNode.addError("@SneakyThrows is legal only on methods and constructors.");
			break;
		}
	}
	
	private void handleMethod(JavacNode annotation, JCMethodDecl method, Collection<String> exceptions) {
		JavacNode methodNode = annotation.up();
		
		if ( (method.mods.flags & Flags.ABSTRACT) != 0) {
			annotation.addError("@SneakyThrows can only be used on concrete methods.");
			return;
		}
		
		if (method.body == null) return;
		if (method.body.stats.isEmpty()) return;
		
		final JCStatement constructorCall = method.body.stats.get(0);
		final boolean isConstructorCall = isConstructorCall(constructorCall);
		List<JCStatement> contents = isConstructorCall ? method.body.stats.tail : method.body.stats;
		
		for (String exception : exceptions) {
			contents = List.of(buildTryCatchBlock(methodNode, contents, exception, annotation.get()));
		}
		
		method.body.stats = isConstructorCall ? List.of(constructorCall).appendList(contents) : contents;
		methodNode.rebuild();
	}
	
	private boolean isConstructorCall(final JCStatement supect) {
		if (!(supect instanceof JCExpressionStatement)) return false;
		final JCExpression supectExpression = ((JCExpressionStatement) supect).expr;
		if (!(supectExpression instanceof JCMethodInvocation)) return false;
		final String methodName = ((JCMethodInvocation) supectExpression).meth.toString();
		return "super".equals(methodName) || "this".equals(methodName);
	}
	
	private JCStatement buildTryCatchBlock(JavacNode node, List<JCStatement> contents, String exception, JCTree source) {
		TreeMaker maker = node.getTreeMaker();
		
		JCBlock tryBlock = setGeneratedBy(maker.Block(0, contents), source);
		
		JCExpression varType = chainDots(node, exception.split("\\."));
		
		JCVariableDecl catchParam = maker.VarDef(maker.Modifiers(Flags.FINAL), node.toName("$ex"), varType, null);
		JCExpression lombokLombokSneakyThrowNameRef = chainDots(node, "lombok", "Lombok", "sneakyThrow");
		JCBlock catchBody = maker.Block(0, List.<JCStatement>of(maker.Throw(maker.Apply(
				List.<JCExpression>nil(), lombokLombokSneakyThrowNameRef,
				List.<JCExpression>of(maker.Ident(node.toName("$ex")))))));
		
		return setGeneratedBy(maker.Try(tryBlock, List.of(recursiveSetGeneratedBy(maker.Catch(catchParam, catchBody), source)), null), source);
	}
}
