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
package lombok.eclipse;

import static lombok.eclipse.handlers.EclipseHandlerUtil.error;

import java.lang.reflect.Field;

import lombok.core.debug.DebugSnapshotStore;
import lombok.patcher.Symbols;

import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.parser.Parser;

/**
 * Entry point for the Eclipse Parser patch that lets lombok modify the Abstract Syntax Tree as generated by
 * Eclipse's parser implementations. This class is injected into the appropriate OSGi ClassLoader and can thus
 * use any classes that belong to org.eclipse.jdt.(apt.)core.
 * 
 * Note that, for any Method body, if Bit24 is set, the Eclipse parser has been patched to never attempt to
 * (re)parse it. You should set Bit24 on any MethodDeclaration object you inject into the AST:
 * 
 * {@code methodDeclaration.bits |= ASTNode.Bit24; //0x800000}
 * 
 * @author rzwitserloot
 * @author rspilker
 */
public class TransformEclipseAST {
	private final EclipseAST ast;
	//The patcher hacks this field onto CUD. It's public.
	private static final Field astCacheField;
	private static final HandlerLibrary handlers;
	
	public static boolean disableLombok = false;
	
	static {
		Field f = null;
		HandlerLibrary h = null;
		
		try {
			h = HandlerLibrary.load();
		} catch (Throwable t) {
			try {
				error(null, "Problem initializing lombok", t);
			} catch (Throwable t2) {
				System.err.println("Problem initializing lombok");
				t.printStackTrace();
			}
			disableLombok = true;
		}
		try {
			f = CompilationUnitDeclaration.class.getDeclaredField("$lombokAST");
		} catch (Throwable t) {
			//I guess we're in an ecj environment; we'll just not cache stuff then.
		}
		
		astCacheField = f;
		handlers = h;
	}
	
	public static void transform_swapped(CompilationUnitDeclaration ast, Parser parser) {
		transform(parser, ast);
	}
	
	public static EclipseAST getAST(CompilationUnitDeclaration ast, boolean forceRebuild) {
		EclipseAST existing = null;
		if (astCacheField != null) {
			try {
				existing = (EclipseAST)astCacheField.get(ast);
			} catch (Exception e) {
				// existing remains null
			}
		}
		
		if (existing == null) {
			existing = new EclipseAST(ast);
			if (astCacheField != null) try {
				astCacheField.set(ast, existing);
			} catch (Exception ignore) {
			}
		} else {
			existing.rebuild(forceRebuild);
		}
		
		return existing;
	}
	
	/**
	 * This method is called immediately after Eclipse finishes building a CompilationUnitDeclaration, which is
	 * the top-level AST node when Eclipse parses a source file. The signature is 'magic' - you should not
	 * change it!
	 * 
	 * Eclipse's parsers often operate in diet mode, which means many parts of the AST have been left blank.
	 * Be ready to deal with just about anything being null, such as the Statement[] arrays of the Method AST nodes.
	 * 
	 * @param parser The Eclipse parser object that generated the AST. Not actually used; mostly there to satisfy parameter rules for lombok.patcher scripts.
	 * @param ast The AST node belonging to the compilation unit (java speak for a single source file).
	 */
	public static void transform(Parser parser, CompilationUnitDeclaration ast) {
		if (disableLombok) return;
		
		if (Symbols.hasSymbol("lombok.disable")) return;
		
		// Do NOT abort if (ast.bits & ASTNode.HasAllMethodBodies) != 0 - that doesn't work.
		
		try {
			DebugSnapshotStore.INSTANCE.snapshot(ast, "transform entry");
			EclipseAST existing = getAST(ast, false);
			new TransformEclipseAST(existing).go();
			DebugSnapshotStore.INSTANCE.snapshot(ast, "transform exit");
		} catch (Throwable t) {
			DebugSnapshotStore.INSTANCE.snapshot(ast, "transform error: %s", t.getClass().getSimpleName());
			try {
				String message = "Lombok can't parse this source: " + t.toString();
				
				EclipseAST.addProblemToCompilationResult(ast, false, message, 0, 0);
				t.printStackTrace();
			} catch (Throwable t2) {
				try {
					error(ast, "Can't create an error in the problems dialog while adding: " + t.toString(), t2);
				} catch (Throwable t3) {
					//This seems risky to just silently turn off lombok, but if we get this far, something pretty
					//drastic went wrong. For example, the eclipse help system's JSP compiler will trigger a lombok call,
					//but due to class loader shenanigans we'll actually get here due to a cascade of
					//ClassNotFoundErrors. This is the right action for the help system (no lombok needed for that JSP compiler,
					//of course). 'disableLombok' is static, but each context classloader (e.g. each eclipse OSGi plugin) has
					//it's own edition of this class, so this won't turn off lombok everywhere.
					disableLombok = true;
				}
			}
		}
	}
	
	public TransformEclipseAST(EclipseAST ast) {
		this.ast = ast;
	}
	
	/**
	 * First handles all lombok annotations except PrintAST, then calls all non-annotation based handlers.
	 * then handles any PrintASTs.
	 */
	public void go() {
		handlers.callASTVisitors(ast);
		ast.traverse(new AnnotationVisitor(true));
		ast.traverse(new AnnotationVisitor(false));
	}
	
	private static class AnnotationVisitor extends EclipseASTAdapter {
		private final boolean skipPrintAst;
		
		public AnnotationVisitor(boolean skipAllButPrintAST) {
			this.skipPrintAst = skipAllButPrintAST;
		}
		
		@Override public void visitAnnotationOnField(FieldDeclaration field, EclipseNode annotationNode, Annotation annotation) {
			CompilationUnitDeclaration top = (CompilationUnitDeclaration) annotationNode.top().get();
			handlers.handleAnnotation(top, annotationNode, annotation, skipPrintAst);
		}
		
		@Override public void visitAnnotationOnMethodArgument(Argument arg, AbstractMethodDeclaration method, EclipseNode annotationNode, Annotation annotation) {
			CompilationUnitDeclaration top = (CompilationUnitDeclaration) annotationNode.top().get();
			handlers.handleAnnotation(top, annotationNode, annotation, skipPrintAst);
		}
		
		@Override public void visitAnnotationOnLocal(LocalDeclaration local, EclipseNode annotationNode, Annotation annotation) {
			CompilationUnitDeclaration top = (CompilationUnitDeclaration) annotationNode.top().get();
			handlers.handleAnnotation(top, annotationNode, annotation, skipPrintAst);
		}
		
		@Override public void visitAnnotationOnMethod(AbstractMethodDeclaration method, EclipseNode annotationNode, Annotation annotation) {
			CompilationUnitDeclaration top = (CompilationUnitDeclaration) annotationNode.top().get();
			handlers.handleAnnotation(top, annotationNode, annotation, skipPrintAst);
		}
		
		@Override public void visitAnnotationOnType(TypeDeclaration type, EclipseNode annotationNode, Annotation annotation) {
			CompilationUnitDeclaration top = (CompilationUnitDeclaration) annotationNode.top().get();
			handlers.handleAnnotation(top, annotationNode, annotation, skipPrintAst);
		}
	}
}
