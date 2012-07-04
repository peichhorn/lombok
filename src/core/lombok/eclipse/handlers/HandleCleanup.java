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
package lombok.eclipse.handlers;

import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.util.Arrays;

import lombok.Cleanup;
import lombok.core.AnnotationValues;
import lombok.eclipse.DeferUntilPostDiet;
import lombok.eclipse.Eclipse;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.Block;
import org.eclipse.jdt.internal.compiler.ast.CaseStatement;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.InstanceOfExpression;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MemberValuePair;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.OperatorIds;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.SwitchStatement;
import org.eclipse.jdt.internal.compiler.ast.TryStatement;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.mangosdk.spi.ProviderFor;

/**
 * Handles the {@code lombok.Cleanup} annotation for eclipse.
 */
@ProviderFor(EclipseAnnotationHandler.class)
@DeferUntilPostDiet
public class HandleCleanup extends EclipseAnnotationHandler<Cleanup> {
	public void handle(AnnotationValues<Cleanup> annotation, Annotation ast, EclipseNode annotationNode) {
		String cleanupName = annotation.getInstance().value();
		if (cleanupName.length() == 0) {
			annotationNode.addError("cleanupName cannot be the empty string.");
			return;
		}
		
		boolean isLocalDeclaration = false;
		
		switch(annotationNode.up().getKind()) {
		case ARGUMENT:
			isLocalDeclaration = false;
			break;
		case LOCAL:
			isLocalDeclaration = true;
			break;
		default:
			annotationNode.addError("@Cleanup is legal only on local variable declarations.");
			return;
		}
		
		LocalDeclaration decl = (LocalDeclaration)annotationNode.up().get();
		
		if (isLocalDeclaration && decl.initialization == null) {
			annotationNode.addError("@Cleanup variable declarations need to be initialized.");
			return;
		}
		
		EclipseNode ancestor = annotationNode.up().directUp();
		ASTNode blockNode = ancestor.get();
		
		final boolean isSwitch;
		final Statement[] statements;
		if (blockNode instanceof AbstractMethodDeclaration) {
			isSwitch = false;
			statements = ((AbstractMethodDeclaration)blockNode).statements;
		} else if (blockNode instanceof Block) {
			isSwitch = false;
			statements = ((Block)blockNode).statements;
		} else if (blockNode instanceof SwitchStatement) {
			isSwitch = true;
			statements = ((SwitchStatement)blockNode).statements;
		} else {
			annotationNode.addError("@Cleanup is legal only on a local variable declaration inside a block.");
			return;
		}
		
		if (statements == null) {
			annotationNode.addError("LOMBOK BUG: Parent block does not contain any statements.");
			return;
		}
		
		int start = 0;
		if (isLocalDeclaration) {
			for (; start < statements.length ; start++) {
				if (statements[start] == decl) break;
			}
			
			if (start == statements.length) {
				annotationNode.addError("LOMBOK BUG: Can't find this local variable declaration inside its parent.");
				return;
			}
			start++;  //We start with try{} *AFTER* the var declaration.
		}
		
		int end;
		if (isSwitch) {
			end = start + 1;
			for (; end < statements.length ; end++) {
				if (statements[end] instanceof CaseStatement) {
					break;
				}
			}
		} else end = statements.length;
		
		int pS = ast.sourceStart, pE = ast.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		//At this point:
		//  start-1 = Local Declaration marked with @Cleanup
		//  start = first instruction that needs to be wrapped into a try block
		//  end = last instruction of the scope -OR- last instruction before the next case label in switch statements.
		//  hence:
		//  [start, end) = statements for the try block.
		
		Statement[] tryBlock = new Statement[end - start];
		System.arraycopy(statements, start, tryBlock, 0, end-start);
		//Remove the stuff we just dumped into the tryBlock, and then leave room for the try node.
		int newStatementsLength = statements.length - (end-start); //Remove room for every statement moved into try block...
		newStatementsLength += 1; //But add room for the TryStatement node itself.
		Statement[] newStatements = new Statement[newStatementsLength];
		System.arraycopy(statements, 0, newStatements, 0, start); //copy all statements before the try block verbatim.
		System.arraycopy(statements, end, newStatements, start+1, statements.length - end); //For switch statements.
		
		doAssignmentCheck(annotationNode, tryBlock, decl.name);
		
		TryStatement tryStatement = new TryStatement();
		setGeneratedBy(tryStatement, ast);
		tryStatement.tryBlock = new Block(0);
		tryStatement.tryBlock.statements = tryBlock;
		setGeneratedBy(tryStatement.tryBlock, ast);
		
		// Positions for in-method generated nodes are special
		int ss = decl.declarationSourceEnd + 1;
		int se = ss;
		if (tryBlock.length > 0) {
			se = tryBlock[tryBlock.length - 1].sourceEnd + 1; //+1 for the closing semicolon. Yes, there could be spaces. Bummer.
			tryStatement.sourceStart = ss;
			tryStatement.sourceEnd = se;
			tryStatement.tryBlock.sourceStart = ss;
			tryStatement.tryBlock.sourceEnd = se;
		}
		
		
		newStatements[start] = tryStatement;
		
		Statement[] finallyBlock = new Statement[1];
		
		if ("close".equals(cleanupName) && !annotation.isExplicit("value")) {
			SingleNameReference varName = new SingleNameReference(decl.name, p);
			setGeneratedBy(varName, ast);
			final CastExpression castExpression = makeCastExpression(varName, generateQualifiedTypeRef(ast, "java".toCharArray(), "io".toCharArray(), "Closeable".toCharArray()), ast);
			setGeneratedBy(castExpression, ast);
			
			MessageSend safeClose = new MessageSend();
			setGeneratedBy(safeClose, ast);
			safeClose.sourceStart = ast.sourceStart;
			safeClose.sourceEnd = ast.sourceEnd;
			safeClose.receiver = castExpression;
			long nameSourcePosition = (long)ast.sourceStart << 32 | ast.sourceEnd;
			if (ast.memberValuePairs() != null) for (MemberValuePair pair : ast.memberValuePairs()) {
				if (pair.name != null && new String(pair.name).equals("value")) {
					nameSourcePosition = (long)pair.value.sourceStart << 32 | pair.value.sourceEnd;
					break;
				}
			}
			safeClose.nameSourcePosition = nameSourcePosition;
			safeClose.selector = cleanupName.toCharArray();
			
			varName = new SingleNameReference(decl.name, p);
			setGeneratedBy(varName, ast);
			final InstanceOfExpression isClosable = new InstanceOfExpression(varName, generateQualifiedTypeRef(ast, "java".toCharArray(), "io".toCharArray(), "Closeable".toCharArray()));
			setGeneratedBy(isClosable, ast);
			
			Block closeBlock = new Block(0);
			closeBlock.statements = new Statement[1];
			closeBlock.statements[0] = safeClose;
			setGeneratedBy(closeBlock, ast);
			IfStatement ifStatement = new IfStatement(isClosable, closeBlock, 0, 0);
			setGeneratedBy(ifStatement, ast);
			
			finallyBlock[0] = ifStatement;
		} else {
			MessageSend unsafeClose = new MessageSend();
			setGeneratedBy(unsafeClose, ast);
			unsafeClose.sourceStart = ast.sourceStart;
			unsafeClose.sourceEnd = ast.sourceEnd;
			SingleNameReference receiver = new SingleNameReference(decl.name, 0);
			setGeneratedBy(receiver, ast);
			unsafeClose.receiver = receiver;
			long nameSourcePosition = (long)ast.sourceStart << 32 | ast.sourceEnd;
			if (ast.memberValuePairs() != null) for (MemberValuePair pair : ast.memberValuePairs()) {
				if (pair.name != null && new String(pair.name).equals("value")) {
					nameSourcePosition = (long)pair.value.sourceStart << 32 | pair.value.sourceEnd;
					break;
				}
			}
			unsafeClose.nameSourcePosition = nameSourcePosition;
			unsafeClose.selector = cleanupName.toCharArray();
			
			SingleNameReference varName = new SingleNameReference(decl.name, p);
			setGeneratedBy(varName, ast);
			NullLiteral nullLiteral = new NullLiteral(pS, pE);
			setGeneratedBy(nullLiteral, ast);
			
			MessageSend preventNullAnalysis = preventNullAnalysis(ast, varName);
			
			EqualExpression equalExpression = new EqualExpression(preventNullAnalysis, nullLiteral, OperatorIds.NOT_EQUAL);
			equalExpression.sourceStart = pS; equalExpression.sourceEnd = pE;
			setGeneratedBy(equalExpression, ast);
			
			Block closeBlock = new Block(0);
			closeBlock.statements = new Statement[1];
			closeBlock.statements[0] = unsafeClose;
			setGeneratedBy(closeBlock, ast);
			IfStatement ifStatement = new IfStatement(equalExpression, closeBlock, 0, 0);
			setGeneratedBy(ifStatement, ast);
			
			finallyBlock[0] = ifStatement;
		}
		
		tryStatement.finallyBlock = new Block(0);
		
		// Positions for in-method generated nodes are special
		if (!isSwitch) {
			tryStatement.finallyBlock.sourceStart = blockNode.sourceEnd;
			tryStatement.finallyBlock.sourceEnd = blockNode.sourceEnd;
		}
		setGeneratedBy(tryStatement.finallyBlock, ast);
		tryStatement.finallyBlock.statements = finallyBlock;
		
		tryStatement.catchArguments = null;
		tryStatement.catchBlocks = null;
		
		if (blockNode instanceof AbstractMethodDeclaration) {
			((AbstractMethodDeclaration)blockNode).statements = newStatements;
		} else if (blockNode instanceof Block) {
			((Block)blockNode).statements = newStatements;
		} else if (blockNode instanceof SwitchStatement) {
			((SwitchStatement)blockNode).statements = newStatements;
		}
		
		ancestor.rebuild();
	}
	
	private MessageSend preventNullAnalysis(Annotation ast, Expression expr) {
		MessageSend singletonList = new MessageSend();
		setGeneratedBy(singletonList, ast);
		
		int pS = ast.sourceStart, pE = ast.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		singletonList.receiver = createNameReference("java.util.Collections", ast);
		singletonList.selector = "singletonList".toCharArray();
		
		singletonList.arguments = new Expression[] { expr };
		singletonList.nameSourcePosition = p;
		singletonList.sourceStart = pS;
		singletonList.sourceEnd = singletonList.statementEnd = pE;
		
		MessageSend preventNullAnalysis = new MessageSend();
		setGeneratedBy(preventNullAnalysis, ast);
		
		preventNullAnalysis.receiver = singletonList;
		preventNullAnalysis.selector = "get".toCharArray();
		
		preventNullAnalysis.arguments = new Expression[] { makeIntLiteral("0".toCharArray(), ast) };
		preventNullAnalysis.nameSourcePosition = p;
		preventNullAnalysis.sourceStart = pS;
		preventNullAnalysis.sourceEnd = singletonList.statementEnd = pE;
		
		return preventNullAnalysis;
	}
	
	private TypeReference generateQualifiedTypeRef(ASTNode source, char[]... varNames) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		TypeReference ref;
		
		long[] poss = Eclipse.poss(source, varNames.length);
		if (varNames.length > 1) ref = new QualifiedTypeReference(varNames, poss);
		else ref = new SingleTypeReference(varNames[0], p);
		setGeneratedBy(ref, source);
		return ref;
	}
	
	private void doAssignmentCheck(EclipseNode node, Statement[] tryBlock, char[] varName) {
		for (Statement statement : tryBlock) doAssignmentCheck0(node, statement, varName);
	}
	
	private void doAssignmentCheck0(EclipseNode node, Statement statement, char[] varName) {
		if (statement instanceof Assignment)
			doAssignmentCheck0(node, ((Assignment)statement).expression, varName);
		else if (statement instanceof LocalDeclaration)
			doAssignmentCheck0(node, ((LocalDeclaration)statement).initialization, varName);
		else if (statement instanceof CastExpression)
			doAssignmentCheck0(node, ((CastExpression)statement).expression, varName);
		else if (statement instanceof SingleNameReference) {
			if (Arrays.equals(((SingleNameReference)statement).token, varName)) {
				EclipseNode problemNode = node.getNodeFor(statement);
				if (problemNode != null) problemNode.addWarning(
						"You're assigning an auto-cleanup variable to something else. This is a bad idea.");
			}
		}
	}
}
