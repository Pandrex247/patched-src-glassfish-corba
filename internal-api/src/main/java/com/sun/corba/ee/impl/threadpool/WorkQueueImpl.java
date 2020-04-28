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
 *
 * Portions Copyright [2016] [Payara Foundation and/or its affiliates.]
 */
package com.sun.corba.ee.impl.threadpool;

import com.sun.corba.ee.spi.threadpool.ThreadPool;
import com.sun.corba.ee.spi.threadpool.Work;
import com.sun.corba.ee.spi.threadpool.WorkQueue;
import org.glassfish.gmbal.NameValue;

/**
 * Works with thread pool to implement producer/consumer queue
 * Complete re-write of the old WorkQueue / ThreadPool implementations
 * in terms of java.util.concurrent
 * 
 * @author lprimak
 */
public class WorkQueueImpl implements WorkQueue {
    public WorkQueueImpl() {
        this(new ThreadPoolImpl(WORKQUEUE_DEFAULT_NAME), WORKQUEUE_DEFAULT_NAME);
    }

    public WorkQueueImpl(ThreadPool workerThreadPool) {
        this(workerThreadPool, WORKQUEUE_DEFAULT_NAME);
    }

    public WorkQueueImpl(ThreadPool workerThreadPool, String name) {
        this.threadPool = (AbstractThreadPool)workerThreadPool;
        this.name = name;
    }

    @Override
    public void addWork(Work aWorkItem) {
        addWork(aWorkItem, false);
    }
    
    @Override
    public void addWork(Work aWorkItem, boolean isLongRunning) {
        if(!isLongRunning) {
            ++workItemsAdded;
          aWorkItem.setEnqueueTime(System.currentTimeMillis());
        }
        threadPool.submit(aWorkItem, isLongRunning);
    }

    @NameValue
    @Override
    public String getName() {
        return name;
    }

    @Override
    public long totalWorkItemsAdded() {
        return workItemsAdded;
    }

    @Override
    public int workItemsInQueue() {
        return threadPool.getQueue().size();
    }

    @Override
    public long averageTimeInQueue() {
        if (workItemsDequeued == 0) {
            return 0;
        } else { 
            return (totalTimeInQueue / workItemsDequeued);
        }
    }
    
    void incrDequeue(Work work) {
        ++workItemsDequeued;
        totalTimeInQueue += System.currentTimeMillis() - work.getEnqueueTime() ;
    }

    @Override
    public void setThreadPool(ThreadPool aThreadPool) {
        this.threadPool = (AbstractThreadPool)aThreadPool;
    }

    @Override
    public ThreadPool getThreadPool() {
        return threadPool;
    }
    
    
    private final String name;
    private AbstractThreadPool threadPool;
    
    private long workItemsAdded = 0;
    private long workItemsDequeued = 0;
    private long totalTimeInQueue = 0;

    public static final String WORKQUEUE_DEFAULT_NAME = "default-workqueue";
    private static final long serialVersionUID = 1L;
}
