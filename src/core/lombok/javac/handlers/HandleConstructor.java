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
package lombok.javac.handlers;

import static lombok.core.AST.Kind.ANNOTATION;
import static lombok.javac.handlers.JavacHandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.MemberExistsResult.EXISTS_BY_USER;

import java.lang.annotation.Annotation;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.core.AnnotationValues;
import lombok.core.TransformationsUtil;
import lombok.core.AST.Kind;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacResolution;
import lombok.javac.JavacResolution.TypeNotConvertibleException;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

public class HandleConstructor {
	@ProviderFor(JavacAnnotationHandler.class) public static class HandleNoArgsConstructor extends JavacAnnotationHandler<NoArgsConstructor> {
		@Override public void handle(final AnnotationValues<NoArgsConstructor> annotation, final JCAnnotation ast, final JavacNode annotationNode) {
			final NoArgsConstructor instance = annotation.getInstance();
			final ConstructorData data = new ConstructorData() //
					.fieldProvider(FieldProvider.NO) //
					.accessLevel(instance.access()) //
					.staticName(instance.staticName()) //
					.callSuper(instance.callSuper());
			new HandleConstructor().handle(annotationNode, NoArgsConstructor.class, data);
		}
	}
	
	@ProviderFor(JavacAnnotationHandler.class) public static class HandleRequiredArgsConstructor extends JavacAnnotationHandler<RequiredArgsConstructor> {
		@Override public void handle(final AnnotationValues<RequiredArgsConstructor> annotation, final JCAnnotation ast, final JavacNode annotationNode) {
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
	
	@ProviderFor(JavacAnnotationHandler.class) public static class HandleAllArgsConstructor extends JavacAnnotationHandler<AllArgsConstructor> {
		@Override public void handle(final AnnotationValues<AllArgsConstructor> annotation, final JCAnnotation ast, final JavacNode annotationNode) {
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
	
	private void handle(final JavacNode annotationNode, final Class<? extends Annotation> annotationType, final ConstructorData data) {
		deleteAnnotationIfNeccessary(annotationNode, annotationType);
		deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
		
		JavacNode typeNode = annotationNode.up();
		JCClassDecl typeDecl = null;
		if (typeNode.get() instanceof JCClassDecl) typeDecl = (JCClassDecl) typeNode.get();
		long modifiers = typeDecl == null ? 0 : typeDecl.mods.flags;
		boolean notAClass = (modifiers & (Flags.INTERFACE | Flags.ANNOTATION)) != 0;
		if (typeDecl == null || notAClass) {
			annotationNode.addError(String.format("%s is only supported on a class or an enum.", annotationType.getSimpleName()));
			return;
		}
		if (data.accessLevel == AccessLevel.NONE) return;
		generateConstructor(typeNode, annotationNode.get(), data);
	}
	
	public static boolean constructorOrConstructorAnnotationExists(final JavacNode typeNode) {
		boolean constructorExists = constructorExists(typeNode) == EXISTS_BY_USER;
		if (!constructorExists) for (JavacNode child : typeNode.down()) {
			if (child.getKind() == ANNOTATION) {
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
	
	public void generateConstructor(final JavacNode typeNode, final JCTree source, final ConstructorData data) {
		final List<SuperConstructor> superConstructors = data.callSuper ? getSuperConstructors(typeNode) : List.of(SuperConstructor.implicit());
		for (SuperConstructor superConstructor : superConstructors) {
			final JCMethodDecl constr = createConstructor(typeNode, source, data, superConstructor);
			injectMethod(typeNode, constr);
			if (data.staticConstructorRequired()) {
				JCMethodDecl staticConstr = createStaticConstructor(typeNode, source, data, superConstructor);
				injectMethod(typeNode, staticConstr);
			}
			typeNode.rebuild();
		}
	}
	
	private void addConstructorProperties(final JCModifiers mods, final JavacNode node, final List<JCVariableDecl> params) {
		if (params.isEmpty()) return;
		TreeMaker maker = node.getTreeMaker();
		JCExpression constructorPropertiesType = chainDotsString(node, "java.beans.ConstructorProperties");
		ListBuffer<JCExpression> fieldNames = ListBuffer.lb();
		for (JCVariableDecl param : params) {
			fieldNames.append(maker.Literal(param.name.toString()));
		}
		JCExpression fieldNamesArray = maker.NewArray(null, List.<JCExpression>nil(), fieldNames.toList());
		JCAnnotation annotation = maker.Annotation(constructorPropertiesType, List.of(fieldNamesArray));
		mods.annotations = mods.annotations.append(annotation);
	}
	
	private JCMethodDecl createConstructor(JavacNode typeNode, JCTree source, final ConstructorData data, final SuperConstructor superConstructor) {
		TreeMaker maker = typeNode.getTreeMaker();
		
		boolean isEnum = (((JCClassDecl) typeNode.get()).mods.flags & Flags.ENUM) != 0;
		AccessLevel level = (isEnum | data.staticConstructorRequired()) ? AccessLevel.PRIVATE : data.accessLevel;
		
		ListBuffer<JCStatement> statements = ListBuffer.lb();
		ListBuffer<JCStatement> assigns = ListBuffer.lb();
		ListBuffer<JCVariableDecl> params = ListBuffer.lb();
		
		if (!superConstructor.isImplicit) {
			params.appendList(superConstructor.params);
			statements.append(maker.Exec(maker.Apply(List.<JCExpression>nil(), maker.Ident(typeNode.toName("super")), superConstructor.getArgs(typeNode))));
		}
		
		final List<JavacNode> fields = data.fieldProvider.findFields(typeNode);
		for (JavacNode fieldNode : fields) {
			JCVariableDecl field = (JCVariableDecl) fieldNode.get();
			List<JCAnnotation> nonNulls = findAnnotations(fieldNode, TransformationsUtil.NON_NULL_PATTERN);
			List<JCAnnotation> nullables = findAnnotations(fieldNode, TransformationsUtil.NULLABLE_PATTERN);
			JCVariableDecl param = maker.VarDef(maker.Modifiers(Flags.FINAL, nonNulls.appendList(nullables)), field.name, field.vartype, null);
			params.append(param);
			JCFieldAccess thisX = maker.Select(maker.Ident(fieldNode.toName("this")), field.name);
			JCAssign assign = maker.Assign(thisX, maker.Ident(field.name));
			assigns.append(maker.Exec(assign));
			
			if (!nonNulls.isEmpty()) {
				JCStatement nullCheck = generateNullCheck(maker, fieldNode);
				if (nullCheck != null) statements.append(nullCheck);
			}
		}
		
		JCModifiers mods = maker.Modifiers(toJavacModifier(level), List.<JCAnnotation>nil());
		if (!data.suppressConstructorProperties && level != AccessLevel.PRIVATE && !isLocalType(typeNode)) {
			addConstructorProperties(mods, typeNode, params.toList());
		}
		JCBlock body = maker.Block(0L, statements.appendList(assigns).toList());
		return recursiveSetGeneratedBy(maker.MethodDef(mods, typeNode.toName("<init>"), null, List.<JCTypeParameter>nil(), params.toList(), List.<JCExpression>nil(), body, null), source);
	}
	
	private boolean isLocalType(JavacNode type) {
		JavacNode typeNode = type.up();
		while ((typeNode != null) && !(typeNode.get() instanceof JCClassDecl)) {
			typeNode = typeNode.up();
		}
		return typeNode != null;
	}
	
	private JCMethodDecl createStaticConstructor(JavacNode typeNode, JCTree source, final ConstructorData data, final SuperConstructor superConstructor) {
		TreeMaker maker = typeNode.getTreeMaker();
		JCClassDecl type = (JCClassDecl) typeNode.get();
		
		JCModifiers mods = maker.Modifiers(Flags.STATIC | toJavacModifier(data.accessLevel));
		
		JCExpression returnType, constructorType;
		
		ListBuffer<JCTypeParameter> typeParams = ListBuffer.lb();
		ListBuffer<JCVariableDecl> params = ListBuffer.lb();
		ListBuffer<JCExpression> typeArgs1 = ListBuffer.lb();
		ListBuffer<JCExpression> typeArgs2 = ListBuffer.lb();
		ListBuffer<JCExpression> args = ListBuffer.lb();

		if (!superConstructor.isImplicit) {
			params.appendList(superConstructor.params);
			args.appendList(superConstructor.getArgs(typeNode));
		}
		
		if (!type.typarams.isEmpty()) {
			for (JCTypeParameter param : type.typarams) {
				typeArgs1.append(maker.Ident(param.name));
				typeArgs2.append(maker.Ident(param.name));
				typeParams.append(maker.TypeParameter(param.name, param.bounds));
			}
			returnType = maker.TypeApply(maker.Ident(type.name), typeArgs1.toList());
			constructorType = maker.TypeApply(maker.Ident(type.name), typeArgs2.toList());
		} else {
			returnType = maker.Ident(type.name);
			constructorType = maker.Ident(type.name);
		}
		
		final List<JavacNode> fields = data.fieldProvider.findFields(typeNode);
		for (JavacNode fieldNode : fields) {
			JCVariableDecl field = (JCVariableDecl) fieldNode.get();
			JCExpression pType = cloneType(maker, field.vartype, source);
			List<JCAnnotation> nonNulls = findAnnotations(fieldNode, TransformationsUtil.NON_NULL_PATTERN);
			List<JCAnnotation> nullables = findAnnotations(fieldNode, TransformationsUtil.NULLABLE_PATTERN);
			JCVariableDecl param = maker.VarDef(maker.Modifiers(Flags.FINAL, nonNulls.appendList(nullables)), field.name, pType, null);
			params.append(param);
			args.append(maker.Ident(field.name));
		}
		JCReturn returnStatement = maker.Return(maker.NewClass(null, List.<JCExpression>nil(), constructorType, args.toList(), null));
		JCBlock body = maker.Block(0, List.<JCStatement>of(returnStatement));
		
		return recursiveSetGeneratedBy(maker.MethodDef(mods, typeNode.toName(data.staticName), returnType, typeParams.toList(), params.toList(), List.<JCExpression>nil(), body, null), source);
	}
	
	public List<SuperConstructor> getSuperConstructors(final JavacNode typeNode) {
		final ListBuffer<SuperConstructor> superConstructors = ListBuffer.lb();
		final JCClassDecl typeDecl = (JCClassDecl) typeNode.get();
		if (typeDecl.extending != null) {
			Type type = typeDecl.extending.type;
			if (type == null) {
				try {
					JCExpression resolvedExpression = ((JCExpression) new JavacResolution(typeNode.getContext()).resolveMethodMember(typeNode).get(typeDecl.extending));
					if (resolvedExpression != null) type = resolvedExpression.type;
				} catch (Exception ignore) {
				}
			}
			final TreeMaker maker = typeNode.getTreeMaker();
			final TypeSymbol typeSymbol = type.asElement();
			if (typeSymbol != null) for (Symbol member : typeSymbol.getEnclosedElements()) {
				if (member.getKind() != ElementKind.CONSTRUCTOR) continue;
				if (!member.getModifiers().contains(Modifier.PUBLIC) && !member.getModifiers().contains(Modifier.PROTECTED)) continue;
				try {
					final MethodSymbol superConstructor = (MethodSymbol) member;
					final MethodType superConstructorType = superConstructor.type.asMethodType();
					final ListBuffer<JCVariableDecl> params = ListBuffer.lb();
					int argCounter = 0;
					if (superConstructorType.argtypes != null) for (Type argtype : superConstructorType.argtypes) {
						final JCModifiers paramMods = maker.Modifiers(Flags.FINAL);
						final Name name = typeNode.toName("arg" + (argCounter++));
						final JCExpression varType = JavacResolution.typeToJCTree(argtype, typeNode.getAst(), true);
						final JCVariableDecl varDef = maker.VarDef(paramMods, name, varType, null);
						params.append(varDef);
					}
					superConstructors.append(new SuperConstructor(params.toList()));
				} catch (TypeNotConvertibleException e) {
					typeNode.addError("Can't create super constructor call: " + e.getMessage());
				}
			}
		}
		if (superConstructors.isEmpty()) superConstructors.append(SuperConstructor.implicit());
		return superConstructors.toList();
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
		final List<JCVariableDecl> params;
		boolean isImplicit;
		
		static SuperConstructor implicit() {
			final SuperConstructor superConstructor = new SuperConstructor(List.<JCVariableDecl>nil());
			superConstructor.isImplicit = true;
			return superConstructor;
		}
		
		SuperConstructor(final List<JCVariableDecl> params) {
			this.params = params;
		}
		
		public List<JCExpression> getArgs(final JavacNode typeNode) {
			final TreeMaker maker = typeNode.getTreeMaker();
			final ListBuffer<JCExpression> args = ListBuffer.lb();
			for (JCVariableDecl param : params) {
				args.append(maker.Ident(param.name));
			}
			return args.toList();
		}
	}
	
	public static enum FieldProvider {
		REQUIRED {
			public List<JavacNode> findFields(final JavacNode typeNode) {
				final ListBuffer<JavacNode> fields = ListBuffer.lb();
				for (final JavacNode child : typeNode.down()) {
					if (child.getKind() != Kind.FIELD) continue;
					JCVariableDecl fieldDecl = (JCVariableDecl) child.get();
					if (!filterField(fieldDecl)) continue;
					boolean isFinal = (fieldDecl.mods.flags & Flags.FINAL) != 0;
					boolean isNonNull = !findAnnotations(child, TransformationsUtil.NON_NULL_PATTERN).isEmpty();
					if ((isFinal || isNonNull) && fieldDecl.init == null) fields.append(child);
				}
				return fields.toList();
			}
		},
		ALL {
			public List<JavacNode> findFields(final JavacNode typeNode) {
				final ListBuffer<JavacNode> fields = ListBuffer.lb();
				for (JavacNode child : typeNode.down()) {
					if (child.getKind() != Kind.FIELD) continue;
					JCVariableDecl fieldDecl = (JCVariableDecl) child.get();
					if (!filterField(fieldDecl)) continue;
					boolean isFinal = (fieldDecl.mods.flags & Flags.FINAL) != 0;
					if (isFinal && fieldDecl.init != null) continue;
					fields.append(child);
				}
				return fields.toList();
			}
		},
		NO {
			public List<JavacNode> findFields(final JavacNode typeNode) {
				return List.nil();
			}
		};
		
		public abstract List<JavacNode> findFields(final JavacNode typeNode);
	}
}
