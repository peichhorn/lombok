/*
 * Copyright (C) 2012 The Project Lombok Authors.
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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Stack;

import org.eclipse.jdt.internal.compiler.ISourceElementRequestor;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.core.CompilationUnitStructureRequestor;
import org.eclipse.jdt.internal.core.JavaElement;
import org.eclipse.jdt.internal.core.SourceFieldElementInfo;

public class PatchConstructorAndDataEclipse {

	public static void onSourceElementRequestor_exitField(ISourceElementRequestor requestor, int initializationStart, int declarationEnd, int declarationSourceEnd) throws Exception {
		if ((requestor instanceof CompilationUnitStructureRequestor) && (initializationStart != -1)) {
			CompilationUnitStructureRequestor cusRequestor = (CompilationUnitStructureRequestor) requestor;
			JavaElement handle = (JavaElement) getHandleStack(cusRequestor).peek();
			requestor.exitField(initializationStart, declarationEnd, declarationSourceEnd);
			SourceFieldElementInfo info = (SourceFieldElementInfo) getNewElements(cusRequestor).get(handle);
			int length = declarationEnd - initializationStart;
			if (length > 0) {
				char[] initializer = new char[length];
				System.arraycopy(getParser(cusRequestor).scanner.source, initializationStart, initializer, 0, length);
				setInitializationSource(info, initializer);
			}
		} else {
			requestor.exitField(initializationStart, declarationEnd, declarationSourceEnd);
		}
	}
	
	private static Stack<?> getHandleStack(CompilationUnitStructureRequestor requestor) throws Exception {
		return (Stack<?>) Reflection.handleStackField.get(requestor);
	}
	
	private static Map<?, ?> getNewElements(CompilationUnitStructureRequestor requestor) throws Exception {
		return (Map<?, ?>) Reflection.newElementsField.get(requestor);
	}
	
	private static Parser getParser(CompilationUnitStructureRequestor requestor) throws Exception {
		return (Parser) Reflection.parserField.get(requestor);
	}
	
	private static void setInitializationSource(SourceFieldElementInfo info, char[] initializer) throws Exception {
		Reflection.initializationSourceField.set(info, initializer);
	}
	
	private static final class Reflection {
		private static final Field handleStackField;
		private static final Field newElementsField;
		private static final Field parserField;
		private static final Field initializationSourceField;
		
		static {
			Field a = null, b = null, c = null, d = null;
			
			try {
				a = CompilationUnitStructureRequestor.class.getDeclaredField("handleStack");
				a.setAccessible(true);
				b = CompilationUnitStructureRequestor.class.getDeclaredField("newElements");
				b.setAccessible(true);
				c = CompilationUnitStructureRequestor.class.getDeclaredField("parser");
				c.setAccessible(true);
				d = SourceFieldElementInfo.class.getDeclaredField("initializationSource");
				d.setAccessible(true);
			} catch (Throwable t) {
				//ignore
			}
			
			handleStackField = a;
			newElementsField = b;
			parserField = c;
			initializationSourceField = d;
		}
	}
}
