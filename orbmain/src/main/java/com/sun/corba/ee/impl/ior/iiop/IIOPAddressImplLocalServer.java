/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2016-2017] Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
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

package com.sun.corba.ee.impl.ior.iiop;

import com.sun.corba.ee.spi.ior.TaggedProfileTemplate;
import com.sun.corba.ee.spi.ior.iiop.IIOPAddress;
import com.sun.corba.ee.spi.ior.iiop.IIOPProfileTemplate;

/**
 * Creates overridable localhost server, on a thread (Tx) basis
 * This is so on a multi-homed host, the server address is always
 * the address on an interface that the client is actually connecting to,
 * not the originally initialized address, which could not be reachable from
 * a particular client
 * 
 * @author lprimak
 */
public class IIOPAddressImplLocalServer extends IIOPAddressBase {
    public IIOPAddressImplLocalServer(String host, int port) {
        delegate = new IIOPAddressImpl(host, port);
    }

    public IIOPAddressImplLocalServer(IIOPAddressImpl delegate) {
        this.delegate = delegate;
    }
    
    @Override
    public String getHost() {
        if(localHostOverride.get().isEmpty()) {
            return delegate.getHost();
        }
        else {
            return localHostOverride.get();
        }
    }

    @Override
    public int getPort() {
        return delegate.getPort();
    }
        
    public static void setHostOverride(String host) {
        localHostOverride.set(host);
    }
    
    public static void removeHostOverride() {
        localHostOverride.set(empty);
    }

    public static boolean isStale(TaggedProfileTemplate taggedProfileTemplate) {
        IIOPProfileTemplate templ = (IIOPProfileTemplate)taggedProfileTemplate;
        IIOPAddress addr = templ.getPrimaryAddress();
        if(addr instanceof IIOPAddressImplLocalServer) {
            IIOPAddressImplLocalServer localAddr = (IIOPAddressImplLocalServer)addr;
            return !localAddr.getDelegateHost().equals(localAddr.getHost());
        }
        return false;
    }

    String getDelegateHost() {
        return delegate.getHost();
    }

    
    private final IIOPAddressImpl delegate;
    static private final String empty = "";
    static private final ThreadLocal<String> localHostOverride = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return empty;
        }
    };
}
