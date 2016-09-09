/*
 * Copyright (c) 2016 Payara Foundation. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 */

package com.sun.corba.ee.impl.ior.iiop;

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

    @Override
    protected boolean isLocalServer() {
        return true;
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
