/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2018 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.corba.ee.impl.presentation.rmi.codegen ;

import com.sun.corba.ee.impl.presentation.rmi.StubFactoryDynamicBase;
import com.sun.corba.ee.impl.presentation.rmi.StubInvocationHandlerImpl;
import com.sun.corba.ee.impl.util.Utility;
import com.sun.corba.ee.spi.logging.ORBUtilSystemException;
import com.sun.corba.ee.spi.presentation.rmi.IDLNameTranslator;
import com.sun.corba.ee.spi.presentation.rmi.PresentationManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.Map;

public class StubFactoryCodegenImpl extends StubFactoryDynamicBase  
{
    private static final ORBUtilSystemException wrapper =
        ORBUtilSystemException.self ;

    private static final String CODEGEN_KEY = "CodegenStubClass" ;
    private final PresentationManager pm ;

    public StubFactoryCodegenImpl( PresentationManager pm,
        PresentationManager.ClassData classData, ClassLoader loader ) 
    {
        super( classData, loader ) ;
        this.pm = pm ;
    }

    private Class<?> getStubClass() {
        // IMPORTANT: A get & put to classData's dictionary can occur
        //            by two or more threads in this method at the same
        //            time. Therefore, classData must be synchronized here.

        synchronized (classData) {
            final Map<String,Object> dictionary = classData.getDictionary();
            return (Class<?>) dictionary.computeIfAbsent(CODEGEN_KEY, k -> createStubClass());
        }
    }

    private Class<?> createStubClass() {
        final IDLNameTranslator nt = classData.getIDLNameTranslator() ;
        final Class<?> theClass = classData.getMyClass() ;
        final String stubClassName = Utility.dynamicStubName(theClass.getName() ) ;
        final Class<?> baseClass = CodegenStubBase.class ;
        final Class<?>[] interfaces = nt.getInterfaces() ;
        final Method[] methods = nt.getMethods() ;

        // Create a StubGenerator that generates this stub class
        final CodegenProxyCreator creator = new CodegenProxyCreator(stubClassName, baseClass, interfaces, methods);

        // Invoke creator in a doPrivileged block if there is a security manager installed.
        return System.getSecurityManager() == null
              ? createStubClass(creator)
              : AccessController.doPrivileged((PrivilegedAction<Class<?>>) () -> createStubClass(creator)
        );
    }

    private Class<?> createStubClass(CodegenProxyCreator creator) {
        return creator.create(classData.getMyClass(), pm.getDebug(), pm.getPrintStream());
    }

    public org.omg.CORBA.Object makeStub() {
        final Class<?> stubClass = getStubClass( ) ;

        CodegenStubBase stub = null ;

        try {
            // Added doPriv for issue 778
            stub = AccessController.doPrivileged(
                  (PrivilegedExceptionAction<CodegenStubBase>) () -> (CodegenStubBase) stubClass.newInstance()
            ) ;
        } catch (Exception exc) {
            wrapper.couldNotInstantiateStubClass(exc, stubClass.getName()) ;
        }
        
        InvocationHandler handler = new StubInvocationHandlerImpl(pm, classData, stub) ;

        stub.initialize( classData, handler ) ;

        return stub ;
    }
}
