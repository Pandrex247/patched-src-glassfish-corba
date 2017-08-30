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
package com.sun.corba.ee.impl.threadpool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class provides a more suitable implementation of ThreadPoolExecutor
 * Stock implementation prefers to queue workers when current threads >= core
 * threads however, this implementation will go up to the max threads first,
 * then will start to queue work items
 *
 * This will provide something that's as close to the previous implementation as
 * possible
 *
 * @author lprimak
 */
public class PayaraThreadPoolExecutor extends ThreadPoolExecutor {
    public PayaraThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory factory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, factory);
        try {
            Field ctlField = ThreadPoolExecutor.class.getDeclaredField("ctl");
            ctlField.setAccessible(true);
            ctl = (AtomicInteger) ctlField.get(this);

            Field capacityField = ThreadPoolExecutor.class.getDeclaredField("CAPACITY");
            capacityField.setAccessible(true);
            this.CAPACITY = (Integer) capacityField.get(this);
            
            Field shutdownField = ThreadPoolExecutor.class.getDeclaredField("SHUTDOWN");
            shutdownField.setAccessible(true);
            this.SHUTDOWN = (Integer) shutdownField.get(this);

            addWorkerMethod = ThreadPoolExecutor.class.getDeclaredMethod("addWorker", Runnable.class, boolean.class);
            addWorkerMethod.setAccessible(true);

            rejectMethod = ThreadPoolExecutor.class.getDeclaredMethod("reject", Runnable.class);
            rejectMethod.setAccessible(true);
        } catch (ReflectiveOperationException ex) {
            log.log(Level.SEVERE, "Cannot get parent class' fields", ex);
            throw new IllegalStateException(ex);
        }
    }

    /**
     * taken from it's parent class
     * reversed the order of operations,
     * so threads up to maximum are created, before starting to queue
     * 
     * @param command
     */
    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }

        int c = ctl.get();
        // first try to add thread to the core thread pool
        if (workerCountOf(c) < getCorePoolSize()) {
            if (addWorker(command, true)) {
                return;
            }
        }
        // otherwise, try the non-core threads
        if (addWorker(command, false)) {
            return;
        }
        c = ctl.get();
        // now try to queue
        if (isRunning(c) && getQueue().offer(command)) {
            int recheck = ctl.get();
            if (!isRunning(recheck) && remove(command)) {
                reject(command);
            } else if (workerCountOf(recheck) == 0) {
                addWorker(null, false);
            }
        }
    }

    private int workerCountOf(int c) {
        return c & CAPACITY;
    }

    private boolean addWorker(Runnable command, boolean b) {
        try {
            return (Boolean) addWorkerMethod.invoke(this, command, b);
        } catch (ReflectiveOperationException ex) {
            log.log(Level.SEVERE, "Cannot call addWorker()", ex);
            throw new IllegalStateException(ex);
        }
    }

    private boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    private void reject(Runnable command) {
        try {
            rejectMethod.invoke(this, command);
        } catch (ReflectiveOperationException ex) {
            log.log(Level.SEVERE, "Cannot call rejectMethod()", ex);
            throw new IllegalStateException(ex);
        }
    }


    private final AtomicInteger ctl;
    private final int CAPACITY;
    private final int SHUTDOWN;
    private final Method addWorkerMethod;
    private final Method rejectMethod;
    private static final Logger log = Logger.getLogger(PayaraThreadPoolExecutor.class.getName());
}
