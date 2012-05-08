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

import static lombok.eclipse.Eclipse.toQualifiedName;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import lombok.Lombok;
import lombok.core.AnnotationValues;
import lombok.core.PrintAST;
import lombok.core.SpiLoadUtil;
import lombok.core.TypeLibrary;
import lombok.core.TypeResolver;
import lombok.core.AnnotationValues.AnnotationValueDecodeFail;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.impl.ITypeRequestor;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

/**
 * This class tracks 'handlers' and knows how to invoke them for any given AST node.
 * 
 * This class can find the handlers (via SPI discovery) and will set up the given AST node, such as
 * building an AnnotationValues instance.
 */
public class HandlerLibrary {
	/**
	 * Creates a new HandlerLibrary. Errors will be reported to the Eclipse Error log.
	 * You probably want to use {@link #load()} instead.
	 */
	public HandlerLibrary() {}
	
	private TypeLibrary typeLibrary = new TypeLibrary();
	
	private static class AnnotationHandlerContainer<T extends Annotation> {
		private EclipseAnnotationHandler<T> handler;
		private Class<T> annotationClass;
		
		AnnotationHandlerContainer(EclipseAnnotationHandler<T> handler, Class<T> annotationClass) {
			this.handler = handler;
			this.annotationClass = annotationClass;
		}
		
		public void handle(org.eclipse.jdt.internal.compiler.ast.Annotation annotation,
				final EclipseNode annotationNode) {
			AnnotationValues<T> annValues = createAnnotation(annotationClass, annotationNode);
			handler.handle(annValues, annotation, annotationNode);
		}
		
		public void preHandle(org.eclipse.jdt.internal.compiler.ast.Annotation annotation,
				final EclipseNode annotationNode) {
			AnnotationValues<T> annValues = createAnnotation(annotationClass, annotationNode);
			handler.preHandle(annValues, annotation, annotationNode);
		}
		
		public boolean deferUntilPostDiet() {
			return handler.getClass().isAnnotationPresent(DeferUntilPostDiet.class);
		}
		
		public boolean deferUntilBuildFieldsAndMethods() {
			return handler.getClass().isAnnotationPresent(DeferUntilBuildFieldsAndMethods.class);
		}
	}
	
	private Map<String, AnnotationHandlerContainer<?>> annotationHandlers =
		new HashMap<String, AnnotationHandlerContainer<?>>();
	
	private Collection<EclipseASTVisitor> visitorHandlers = new ArrayList<EclipseASTVisitor>();

	/**
	 * Creates a new HandlerLibrary.  Errors will be reported to the Eclipse Error log.
	 * then uses SPI discovery to load all annotation and visitor based handlers so that future calls
	 * to the handle methods will defer to these handlers.
	 */
	public static HandlerLibrary load() {
		HandlerLibrary lib = new HandlerLibrary();
		
		loadAnnotationHandlers(lib);
		loadVisitorHandlers(lib);
		
		return lib;
	}
	
	/** Uses SPI Discovery to find implementations of {@link EclipseAnnotationHandler}. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void loadAnnotationHandlers(HandlerLibrary lib) {
		try {
			for (EclipseAnnotationHandler<?> handler : SpiLoadUtil.findServices(EclipseAnnotationHandler.class, EclipseAnnotationHandler.class.getClassLoader())) {
				try {
					Class<? extends Annotation> annotationClass =
						SpiLoadUtil.findAnnotationClass(handler.getClass(), EclipseAnnotationHandler.class);
					AnnotationHandlerContainer<?> container = new AnnotationHandlerContainer(handler, annotationClass);
					String annotationClassName = container.annotationClass.getName().replace("$", ".");
					if (lib.annotationHandlers.put(annotationClassName, container) != null) {
						error(null, "Duplicate handlers for annotation type: " + annotationClassName, null);
					}
					lib.typeLibrary.addType(container.annotationClass.getName());
				} catch (Throwable t) {
					error(null, "Can't load Lombok annotation handler for Eclipse: ", t);
				}
			}
		} catch (IOException e) {
			Lombok.sneakyThrow(e);
		}
	}
	
	/** Uses SPI Discovery to find implementations of {@link EclipseASTVisitor}. */
	private static void loadVisitorHandlers(HandlerLibrary lib) {
		try {
			for (EclipseASTVisitor visitor : SpiLoadUtil.findServices(EclipseASTVisitor.class, EclipseASTVisitor.class.getClassLoader())) {
				lib.visitorHandlers.add(visitor);
			}
		} catch (Throwable t) {
			throw Lombok.sneakyThrow(t);
		}
	}
	
	private static final Map<ASTNode, Object> handledMap = new WeakHashMap<ASTNode, Object>();
	private static final Object MARKER = new Object();
	
	private boolean checkAndSetHandled(ASTNode node) {
		synchronized (handledMap) {
			return handledMap.put(node, MARKER) != MARKER;
		}
	}
	
	private boolean needsHandling(ASTNode node) {
		synchronized (handledMap) {
			return handledMap.get(node) != MARKER;
		}
	}
	
	/**
	 * Handles the provided annotation node by first finding a qualifying instance of
	 * {@link EclipseAnnotationHandler} and if one exists, calling it with a freshly cooked up
	 * instance of {@link AnnotationValues}.
	 * 
	 * Note that depending on the printASTOnly flag, the {@link lombok.core.PrintAST} annotation
	 * will either be silently skipped, or everything that isn't {@code PrintAST} will be skipped.
	 * 
	 * The HandlerLibrary will attempt to guess if the given annotation node represents a lombok annotation.
	 * For example, if {@code lombok.*} is in the import list, then this method will guess that
	 * {@code Getter} refers to {@code lombok.Getter}, presuming that {@link lombok.eclipse.handlers.HandleGetter}
	 * has been loaded.
	 * 
	 * @param ast The Compilation Unit that contains the Annotation AST Node.
	 * @param annotationNode The Lombok AST Node representing the Annotation AST Node.
	 * @param annotation 'node.get()' - convenience parameter.
	 */
	public void handleAnnotation(CompilationUnitDeclaration ast, EclipseNode annotationNode, org.eclipse.jdt.internal.compiler.ast.Annotation annotation, boolean skipPrintAst) {
		String pkgName = annotationNode.getPackageDeclaration();
		Collection<String> imports = annotationNode.getImportStatements();
		
		TypeResolver resolver = new TypeResolver(pkgName, imports);
		TypeReference rawType = annotation.type;
		if (rawType == null) return;
		
		for (String fqn : resolver.findTypeMatches(annotationNode, typeLibrary, toQualifiedName(annotation.type.getTypeName()))) {
			boolean isPrintAST = fqn.equals(PrintAST.class.getName());
			if (isPrintAST == skipPrintAst) continue;
			AnnotationHandlerContainer<?> container = annotationHandlers.get(fqn);
			
			if (container == null) continue;
			if (container.deferUntilBuildFieldsAndMethods()) continue;
			if (!annotationNode.isCompleteParse() && container.deferUntilPostDiet()) {
				if (needsHandling(annotation)) container.preHandle(annotation, annotationNode);
				continue;
			}
			
			try {
				if (checkAndSetHandled(annotation)) container.handle(annotation, annotationNode);
			} catch (AnnotationValueDecodeFail fail) {
				fail.owner.setError(fail.getMessage(), fail.idx);
			} catch (Throwable t) {
				error(ast, String.format("Lombok annotation handler %s failed", container.handler.getClass()), t);
			}
		}
	}
	
	public void handleAnnotationOnBuildFieldsAndMethods(EclipseNode typeNode, org.eclipse.jdt.internal.compiler.ast.Annotation annotation) {
		TypeDeclaration decl = (TypeDeclaration) typeNode.get();
		TypeBinding tb = resolveAnnotation(decl, annotation);
		if (tb == null) return;
		AnnotationHandlerContainer<?> container = annotationHandlers.get(new String(tb.readableName()));
		if (container == null) return;
		if (!container.deferUntilBuildFieldsAndMethods()) return;
		EclipseNode annotationNode = typeNode.getAst().get(annotation);
		if (!typeNode.isCompleteParse() && (decl.scope != null)) {
			final CompilationUnitScope cus = decl.scope.compilationUnitScope();
			final ITypeRequestor typeRequestor = cus.environment().typeRequestor;
			if (typeRequestor instanceof org.eclipse.jdt.internal.compiler.Compiler) {
				final org.eclipse.jdt.internal.compiler.Compiler c = (org.eclipse.jdt.internal.compiler.Compiler) typeRequestor;
				try {
					c.parser.getMethodBodies(cus.referenceContext);
					typeNode.rebuild();
				} catch (Exception e) {
					// better break here
				}
			}
		}
		try {
			if (checkAndSetHandled(annotation)) container.handle(annotation, annotationNode);
		} catch (AnnotationValueDecodeFail fail) {
			fail.owner.setError(fail.getMessage(), fail.idx);
		}
	}
	
	private TypeBinding resolveAnnotation(TypeDeclaration decl, org.eclipse.jdt.internal.compiler.ast.Annotation ann) {
		TypeBinding tb = ann.resolvedType;
		if ((tb == null) && (ann.type != null)) {
			try {
				tb = ann.type.resolveType(decl.initializerScope);
			} catch (final Exception ignore) {
				// completion nodes may throw an exception here
			}
		}
		return tb;
	}
	
	/**
	 * Will call all registered {@link EclipseASTVisitor} instances.
	 */
	public void callASTVisitors(EclipseAST ast) {
		for (EclipseASTVisitor visitor : visitorHandlers) try {
			ast.traverse(visitor);
		} catch (Throwable t) {
			error((CompilationUnitDeclaration) ast.top().get(),
					String.format("Lombok visitor handler %s failed", visitor.getClass()), t);
		}
	}
}
