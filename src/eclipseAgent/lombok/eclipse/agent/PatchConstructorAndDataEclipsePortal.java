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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import lombok.Lombok;

public class PatchConstructorAndDataEclipsePortal {
	
	public static void onSourceElementRequestor_exitField(Object requestor, int initializationStart, int declarationEnd, int declarationSourceEnd) {
		try {
			Reflection.onSourceElementRequestor_exitFieldMethod.invoke(null, requestor, initializationStart, declarationEnd, declarationSourceEnd);
		} catch (NoClassDefFoundError e) {
			//ignore, we don't have access to the correct ECJ classes, so lombok can't possibly
			//do anything useful here.
		} catch (IllegalAccessException e) {
			Lombok.sneakyThrow(e);
		} catch (InvocationTargetException e) {
			Lombok.sneakyThrow(e);
		} catch (NullPointerException e) {
			e.initCause(Reflection.problem);
			throw e;
		}
	}
	
	private static final class Reflection {
		public static final Method onSourceElementRequestor_exitFieldMethod;
		public static final Throwable problem;
		
		static {
			Method m = null;
			Throwable problem_ = null;
			try {
				m = PatchConstructorAndDataEclipse.class.getMethod("onSourceElementRequestor_exitField", Class.forName("org.eclipse.jdt.internal.compiler.ISourceElementRequestor"), int.class, int.class, int.class);
			} catch (Throwable t) {
				// That's problematic, but as long as no local classes are used we don't actually need it.
				// Better fail on local classes than crash altogether.
				problem_ = t;
			}
			onSourceElementRequestor_exitFieldMethod = m;
			problem = problem_;
		}
	}
}
