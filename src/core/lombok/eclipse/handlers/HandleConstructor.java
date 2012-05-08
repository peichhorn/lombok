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
package lombok.eclipse.handlers;

import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.MemberExistsResult.EXISTS_BY_USER;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.TransformationsUtil;
import lombok.eclipse.DeferUntilBuildFieldsAndMethods;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleMemberAnnotation;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.mangosdk.spi.ProviderFor;

public class HandleConstructor {
	@ProviderFor(EclipseAnnotationHandler.class)
	@DeferUntilBuildFieldsAndMethods
	public static class HandleNoArgsConstructor extends EclipseAnnotationHandler<NoArgsConstructor> {
		@Override public void handle(AnnotationValues<NoArgsConstructor> annotation, Annotation ast, EclipseNode annotationNode) {
			final NoArgsConstructor instance = annotation.getInstance();
			final ConstructorData data = new ConstructorData() //
					.fieldProvider(FieldProvider.NO) //
					.accessLevel(instance.access()) //
					.staticName(instance.staticName()) //
					.callSuper(instance.callSuper());
			new HandleConstructor().handle(annotationNode, NoArgsConstructor.class, data);
		}
	}
	
	@ProviderFor(EclipseAnnotationHandler.class)
	@DeferUntilBuildFieldsAndMethods
	public static class HandleRequiredArgsConstructor extends EclipseAnnotationHandler<RequiredArgsConstructor> {
		@Override public void handle(AnnotationValues<RequiredArgsConstructor> annotation, Annotation ast, EclipseNode annotationNode) {
			final RequiredArgsConstructor instance = annotation.getInstance();
			final ConstructorData data = new ConstructorData() //
					.fieldProvider(FieldProvider.REQUIRED) //
					.accessLevel(instance.access()) //
					.staticName(instance.staticName()) //
					.callSuper(instance.callSuper()) //
					.suppressConstructorProperties(instance.suppressConstructorProperties());
			new HandleConstructor().handle(annotationNode, RequiredArgsConstructor.class, data);
		}
	}
	
	@ProviderFor(EclipseAnnotationHandler.class)
	@DeferUntilBuildFieldsAndMethods
	public static class HandleAllArgsConstructor extends EclipseAnnotationHandler<AllArgsConstructor> {
		@Override public void handle(AnnotationValues<AllArgsConstructor> annotation, Annotation ast, EclipseNode annotationNode) {
			final AllArgsConstructor instance = annotation.getInstance();
			final ConstructorData data = new ConstructorData() //
					.fieldProvider(FieldProvider.ALL) //
					.accessLevel(instance.access()) //
					.staticName(instance.staticName()) //
					.callSuper(instance.callSuper()) //
					.suppressConstructorProperties(instance.suppressConstructorProperties());
			new HandleConstructor().handle(annotationNode, AllArgsConstructor.class, data);
		}
	}
	
	private void handle(final EclipseNode annotationNode, final Class<? extends java.lang.annotation.Annotation> annotationType, final ConstructorData data) {
		EclipseNode typeNode = annotationNode.up();
		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers & (ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation)) != 0;
		if (typeDecl == null || notAClass) {
			annotationNode.addError(String.format("%s is only supported on a class or an enum.", annotationType.getSimpleName()));
			return;
		}
		if (data.accessLevel == AccessLevel.NONE) return;
		new HandleConstructor().generateConstructor(typeNode, annotationNode.get(), data);
	}
	
	public static boolean constructorOrConstructorAnnotationExists(final EclipseNode typeNode) {
		boolean constructorExists = constructorExists(typeNode) == EXISTS_BY_USER;
		if (!constructorExists) for (EclipseNode child : typeNode.down()) {
			if (child.getKind() == Kind.ANNOTATION) {
				if (annotationTypeMatches(NoArgsConstructor.class, child) //
					|| annotationTypeMatches(AllArgsConstructor.class, child) //
					|| annotationTypeMatches(RequiredArgsConstructor.class, child)) {
					constructorExists = true;
					break;
				}
			}
		}
		return constructorExists;
	}
	
	public void generateConstructor(final EclipseNode typeNode, final ASTNode source, final ConstructorData data) {
		final List<SuperConstructor> superConstructors = data.callSuper ? getSuperConstructors(typeNode, source) : Collections.singletonList(SuperConstructor.implicit());
		for (SuperConstructor superConstructor : superConstructors) {
			final ConstructorDeclaration constr = createConstructor(typeNode, source, data, superConstructor);
			injectMethod(typeNode, constr);
			if (data.staticConstructorRequired()) {
				MethodDeclaration staticConstr = createStaticConstructor(typeNode, source, data, superConstructor);
				injectMethod(typeNode, staticConstr);
			}
			typeNode.rebuild();
		}
	}
	
	private static final char[][] JAVA_BEANS_CONSTRUCTORPROPERTIES = new char[][] {"java".toCharArray(), "beans".toCharArray(), "ConstructorProperties".toCharArray()};
	
	private static Annotation[] createConstructorProperties(ASTNode source, Annotation[] originalAnnotationArray, List<Argument> params) {
		if (params.isEmpty()) return originalAnnotationArray;
		
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		long[] poss = new long[3];
		Arrays.fill(poss, p);
		QualifiedTypeReference constructorPropertiesType = new QualifiedTypeReference(JAVA_BEANS_CONSTRUCTORPROPERTIES, poss);
		setGeneratedBy(constructorPropertiesType, source);
		SingleMemberAnnotation ann = new SingleMemberAnnotation(constructorPropertiesType, pS);
		ann.declarationSourceEnd = pE;
		
		ArrayInitializer fieldNames = new ArrayInitializer();
		fieldNames.sourceStart = pS;
		fieldNames.sourceEnd = pE;
		fieldNames.expressions = new Expression[params.size()];
		
		int ctr = 0;
		for (Argument param : params) {
			fieldNames.expressions[ctr] = new StringLiteral(param.name, pS, pE, 0);
			setGeneratedBy(fieldNames.expressions[ctr], source);
			ctr++;
		}
		
		ann.memberValue = fieldNames;
		setGeneratedBy(ann, source);
		setGeneratedBy(ann.memberValue, source);
		if (originalAnnotationArray == null) return new Annotation[] {ann};
		Annotation[] newAnnotationArray = Arrays.copyOf(originalAnnotationArray, originalAnnotationArray.length + 1);
		newAnnotationArray[originalAnnotationArray.length] = ann;
		return newAnnotationArray;
	}
	
	private ConstructorDeclaration createConstructor(final EclipseNode typeNode, final ASTNode source, final ConstructorData data, final SuperConstructor superConstructor) {
		long p = (long) source.sourceStart << 32 | source.sourceEnd;
		
		boolean isEnum = (((TypeDeclaration) typeNode.get()).modifiers & ClassFileConstants.AccEnum) != 0;
		AccessLevel level = (isEnum | data.staticConstructorRequired()) ? AccessLevel.PRIVATE : data.accessLevel;
		
		ConstructorDeclaration constructor = new ConstructorDeclaration(((CompilationUnitDeclaration) typeNode.top().get()).compilationResult);
		setGeneratedBy(constructor, source);
		
		constructor.modifiers = toEclipseModifier(level);
		constructor.selector = ((TypeDeclaration) typeNode.get()).name;
		constructor.thrownExceptions = null;
		constructor.typeParameters = null;
		constructor.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		constructor.bodyStart = constructor.declarationSourceStart = constructor.sourceStart = source.sourceStart;
		constructor.bodyEnd = constructor.declarationSourceEnd = constructor.sourceEnd = source.sourceEnd;
		constructor.arguments = null;
		
		List<Argument> params = new ArrayList<Argument>();
		List<Statement> assigns = new ArrayList<Statement>();
		List<Statement> nullChecks = new ArrayList<Statement>();
		
		if (superConstructor.isImplicit) {
			constructor.constructorCall = new ExplicitConstructorCall(ExplicitConstructorCall.ImplicitSuper);
			setGeneratedBy(constructor.constructorCall, source);
		} else {
			constructor.constructorCall = new ExplicitConstructorCall(ExplicitConstructorCall.Super);
			setGeneratedBy(constructor.constructorCall, source);
			constructor.constructorCall.arguments = superConstructor.getArgs(source).toArray(new Expression[0]);
			params.addAll(superConstructor.params);
		}
		
		final List<EclipseNode> fields = data.fieldProvider.findFields(typeNode);
		for (EclipseNode fieldNode : fields) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			FieldReference thisX = new FieldReference(field.name, p);
			setGeneratedBy(thisX, source);
			thisX.receiver = new ThisReference((int) (p >> 32), (int) p);
			setGeneratedBy(thisX.receiver, source);
			
			SingleNameReference assignmentNameRef = new SingleNameReference(field.name, p);
			setGeneratedBy(assignmentNameRef, source);
			Assignment assignment = new Assignment(thisX, assignmentNameRef, (int) p);
			assignment.sourceStart = (int) (p >> 32);
			assignment.sourceEnd = assignment.statementEnd = (int) (p >> 32);
			setGeneratedBy(assignment, source);
			assigns.add(assignment);
			long fieldPos = (((long) field.sourceStart) << 32) | field.sourceEnd;
			Argument parameter = new Argument(field.name, fieldPos, copyType(field.type, source), Modifier.FINAL);
			setGeneratedBy(parameter, source);
			Annotation[] nonNulls = findAnnotations(field, TransformationsUtil.NON_NULL_PATTERN);
			Annotation[] nullables = findAnnotations(field, TransformationsUtil.NULLABLE_PATTERN);
			if (nonNulls.length != 0) {
				Statement nullCheck = generateNullCheck(field, source);
				if (nullCheck != null) nullChecks.add(nullCheck);
			}
			Annotation[] copiedAnnotations = copyAnnotations(source, nonNulls, nullables);
			if (copiedAnnotations.length != 0) parameter.annotations = copiedAnnotations;
			params.add(parameter);
		}
		
		nullChecks.addAll(assigns);
		constructor.statements = nullChecks.isEmpty() ? null : nullChecks.toArray(new Statement[nullChecks.size()]);
		constructor.arguments = params.isEmpty() ? null : params.toArray(new Argument[params.size()]);
		
		if (!data.suppressConstructorProperties && level != AccessLevel.PRIVATE && !isLocalType(typeNode)) {
			constructor.annotations = createConstructorProperties(source, constructor.annotations, params);
		}
		
		return constructor;
	}
	
	private boolean isLocalType(EclipseNode type) {
		EclipseNode typeNode = type.up();
		while ((typeNode != null) && !(typeNode.get() instanceof TypeDeclaration)) {
			typeNode = typeNode.up();
		}
		return typeNode != null;
	}
	
	private MethodDeclaration createStaticConstructor(final EclipseNode typeNode, final ASTNode source, final ConstructorData data, final SuperConstructor superConstructor) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		
		MethodDeclaration constructor = new MethodDeclaration(((CompilationUnitDeclaration) typeNode.top().get()).compilationResult);
		setGeneratedBy(constructor, source);
		
		constructor.modifiers = Modifier.STATIC | toEclipseModifier(data.accessLevel);
		TypeDeclaration typeDecl = (TypeDeclaration) typeNode.get();
		if (typeDecl.typeParameters != null && typeDecl.typeParameters.length > 0) {
			TypeReference[] refs = new TypeReference[typeDecl.typeParameters.length];
			int idx = 0;
			for (TypeParameter param : typeDecl.typeParameters) {
				TypeReference typeRef = new SingleTypeReference(param.name, (long) param.sourceStart << 32 | param.sourceEnd);
				setGeneratedBy(typeRef, source);
				refs[idx++] = typeRef;
			}
			constructor.returnType = new ParameterizedSingleTypeReference(typeDecl.name, refs, 0, p);
		} else
			constructor.returnType = new SingleTypeReference(((TypeDeclaration) typeNode.get()).name, p);
		setGeneratedBy(constructor.returnType, source);
		constructor.annotations = null;
		constructor.selector = data.staticName.toCharArray();
		constructor.thrownExceptions = null;
		constructor.typeParameters = copyTypeParams(((TypeDeclaration) typeNode.get()).typeParameters, source);
		constructor.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		constructor.bodyStart = constructor.declarationSourceStart = constructor.sourceStart = source.sourceStart;
		constructor.bodyEnd = constructor.declarationSourceEnd = constructor.sourceEnd = source.sourceEnd;
		
		List<Argument> params = new ArrayList<Argument>();
		List<Expression> args = new ArrayList<Expression>();
		
		if (!superConstructor.isImplicit) {
			params.addAll(superConstructor.params);
			args.addAll(superConstructor.getArgs(source));
		}
		
		AllocationExpression statement = new AllocationExpression();
		statement.sourceStart = pS;
		statement.sourceEnd = pE;
		setGeneratedBy(statement, source);
		statement.type = copyType(constructor.returnType, source);
		
		final List<EclipseNode> fields = data.fieldProvider.findFields(typeNode);
		for (EclipseNode fieldNode : fields) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			long fieldPos = (((long) field.sourceStart) << 32) | field.sourceEnd;
			SingleNameReference nameRef = new SingleNameReference(field.name, fieldPos);
			setGeneratedBy(nameRef, source);
			args.add(nameRef);
			
			Argument parameter = new Argument(field.name, fieldPos, copyType(field.type, source), Modifier.FINAL);
			setGeneratedBy(parameter, source);
			
			Annotation[] copiedAnnotations = copyAnnotations(source, findAnnotations(field, TransformationsUtil.NON_NULL_PATTERN), findAnnotations(field, TransformationsUtil.NULLABLE_PATTERN));
			if (copiedAnnotations.length != 0) parameter.annotations = copiedAnnotations;
			params.add(parameter);
		}
		
		statement.arguments = args.isEmpty() ? null : args.toArray(new Expression[args.size()]);
		constructor.arguments = params.isEmpty() ? null : params.toArray(new Argument[params.size()]);
		constructor.statements = new Statement[] {new ReturnStatement(statement, (int) (p >> 32), (int) p)};
		setGeneratedBy(constructor.statements[0], source);
		return constructor;
	}
	
	public List<SuperConstructor> getSuperConstructors(final EclipseNode typeNode, final ASTNode source) {
		final List<SuperConstructor> superConstructors = new ArrayList<SuperConstructor>();
		final TypeDeclaration typeDecl = (TypeDeclaration) typeNode.get();
		if (typeDecl.superclass != null) {
			TypeBinding binding = typeDecl.superclass.resolveType(typeDecl.initializerScope);
			ensureAllClassScopeMethodWereBuild(binding);
			if (binding instanceof ReferenceBinding) {
				ReferenceBinding rb = (ReferenceBinding) binding;
				MethodBinding[] availableMethods = rb.availableMethods();
				if (availableMethods != null) for (MethodBinding mb : availableMethods) {
					if (!mb.isConstructor()) continue;
					if (mb.isSynthetic()) continue;
					if (!mb.isPublic() && !mb.isProtected()) continue;
					final List<Argument> params = new ArrayList<Argument>();
					int argCounter = 0;
					if (mb.parameters != null) for (TypeBinding argtype : mb.parameters) {
						final String name = "arg" + (argCounter++);
						final TypeReference varType = makeType(argtype, source, false);
						long pos = (((long) source.sourceStart) << 32) | source.sourceEnd;
						Argument param = new Argument(name.toCharArray(), pos, varType, Modifier.FINAL);
						setGeneratedBy(param, source);
						params.add(param);	
					}
					superConstructors.add(new SuperConstructor(params));
				}
			}
		}
		if (superConstructors.isEmpty()) superConstructors.add(SuperConstructor.implicit());
		return superConstructors;
	}
	
	private void ensureAllClassScopeMethodWereBuild(final TypeBinding binding) {
		if (binding instanceof SourceTypeBinding) {
			ClassScope cs = ((SourceTypeBinding) binding).scope;
			if (cs != null) {
				try {
					Reflection.classScopeBuildFieldsAndMethodsMethod.invoke(cs);
				} catch (final Exception e) {
					// See 'Reflection' class for why we ignore this exception.
				}
			}
		}
	}
	
	private static final class Reflection {
		public static final Method classScopeBuildFieldsAndMethodsMethod;
		
		static {
			Method m = null;
			try {
				m = ClassScope.class.getDeclaredMethod("buildFieldsAndMethods");
				m.setAccessible(true);
			} catch (final Exception e) {
				// That's problematic, but as long as no local classes are used we don't actually need it.
				// Better fail on local classes than crash altogether.
			}
			
			classScopeBuildFieldsAndMethodsMethod = m;
		}
	}
	
	public static class ConstructorData {
		FieldProvider fieldProvider;
		AccessLevel accessLevel;
		String staticName;
		boolean callSuper;
		boolean suppressConstructorProperties;
		
		public ConstructorData fieldProvider(final FieldProvider provider) {
			this.fieldProvider = provider;
			return this;
		}
		
		public ConstructorData accessLevel(final AccessLevel accessLevel) {
			this.accessLevel = accessLevel;
			return this;
		}
		
		public ConstructorData staticName(final String name) {
			this.staticName = name;
			return this;
		}
		
		public ConstructorData callSuper(final boolean b) {
			this.callSuper = b;
			return this;
		}
		
		public ConstructorData suppressConstructorProperties(final boolean b) {
			this.suppressConstructorProperties = b;
			return this;
		}

		public boolean staticConstructorRequired() {
			return staticName != null && !staticName.equals("");
		}
	}

	public static class SuperConstructor {
		final List<Argument> params;
		boolean isImplicit;
		
		static SuperConstructor implicit() {
			final SuperConstructor superConstructor = new SuperConstructor(Collections.<Argument>emptyList());
			superConstructor.isImplicit = true;
			return superConstructor;
		}
		
		SuperConstructor(final List<Argument> params) {
			this.params = params;
		}
		
		public List<Expression> getArgs(final ASTNode source) {
			final List<Expression> args = new ArrayList<Expression>();
			for (Argument param : params) {
				long fieldPos = (((long) param.sourceStart) << 32) | param.sourceEnd;
				SingleNameReference nameRef = new SingleNameReference(param.name, fieldPos);
				setGeneratedBy(nameRef, source);
				args.add(nameRef);
			}
			return args;
		}
	}
	
	public static enum FieldProvider {
		REQUIRED {
			public List<EclipseNode> findFields(final EclipseNode typeNode) {
				final List<EclipseNode> fields = new ArrayList<EclipseNode>();
				for (final EclipseNode child : typeNode.down()) {
					if (child.getKind() != Kind.FIELD) continue;
					final FieldDeclaration fieldDecl = (FieldDeclaration) child.get();
					if (!filterField(fieldDecl)) continue;
					boolean isFinal = (fieldDecl.modifiers & ClassFileConstants.AccFinal) != 0;
					boolean isNonNull = findAnnotations(fieldDecl, TransformationsUtil.NON_NULL_PATTERN).length != 0;
					if ((isFinal || isNonNull) && (fieldDecl.initialization == null)) fields.add(child);
				}
				return fields;
			}
		},
		ALL {
			public List<EclipseNode> findFields(final EclipseNode typeNode) {
				final List<EclipseNode> fields = new ArrayList<EclipseNode>();
				for (EclipseNode child : typeNode.down()) {
					if (child.getKind() != Kind.FIELD) continue;
					final FieldDeclaration fieldDecl = (FieldDeclaration) child.get();
					if (!filterField(fieldDecl)) continue;
					boolean isFinal = (fieldDecl.modifiers & ClassFileConstants.AccFinal) != 0;
					if (isFinal && (fieldDecl.initialization != null)) continue;
					fields.add(child);
				}
				return fields;
			}
		},
		NO {
			public List<EclipseNode> findFields(final EclipseNode typeNode) {
				return Collections.emptyList();
			}
		};
		
		public abstract List<EclipseNode> findFields(final EclipseNode typeNode);
	}
}
