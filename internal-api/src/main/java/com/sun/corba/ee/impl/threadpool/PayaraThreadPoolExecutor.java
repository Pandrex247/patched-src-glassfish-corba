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

import java.util.Collection;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;
import lombok.experimental.Delegate;

/**
 * This class provides a more suitable implementation of ThreadPoolExecutor.
 * Stock implementation prefers to queue workers when current threads >= core
 * threads, however, this implementation will prefer to start more threads, if all threads are busy
 * and pool is not completely allocated.
 *
 *
 * This will provide something that's as close to the previous CORBA
 * thread pool implementation as possible without deadlocks present
 * in the old thread pool implementation.
 *
 * @author pdudits
 * @author lprimak
 *
 * @see <a href="https://dzone.com/articles/scalable-java-thread-pool-executor">Article describing this approach (Workaround #2)</a>
 */
public class PayaraThreadPoolExecutor extends ThreadPoolExecutor {
    public PayaraThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, ThreadFactory factory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, new WorkItemQueue(), factory);
        this.queue = (WorkItemQueue) super.getQueue();
        RejectedExecutionHandler defaultHandler = super.getRejectedExecutionHandler();
        super.setRejectedExecutionHandler(rejectionHandler);
        rejectionHandler.downstream = defaultHandler;
    }

    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler rejectedExecutionHandler) {
       rejectionHandler.downstream = Objects.requireNonNull(rejectedExecutionHandler);
    }

    @Override
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return rejectionHandler.downstream;
    }

    private class QueueingRejectionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor threadPoolExecutor) {
            if (!putToQueue(runnable) && downstream != null) {
                downstream.rejectedExecution(runnable, threadPoolExecutor);
            }
        }

        private RejectedExecutionHandler downstream;
    }

    private boolean putToQueue(Runnable runnable) {
        // Our queue is unbounded, so this likely won't fail.
        // but we shouldn't add more items when we're shutting down.
        return !isTerminating() && !isShutdown() && queue.add(runnable);
    }

    private static class WorkItemQueue implements BlockingQueue<Runnable> {
        @Override
        public boolean offer(Runnable runnable) {
            // to completement logic of ThreadPoolExecutor, we only succeed offering if there is a consumer to pass to.
            // rejection handler will add the task to the queue
            return delegate.tryTransfer(runnable);
        }

        @Override
        public boolean offer(Runnable runnable, long l, TimeUnit timeUnit) throws InterruptedException {
            return delegate.tryTransfer(runnable, l, timeUnit);
        }


        private interface Excludes {
            boolean offer(Runnable runnable);
            boolean offer(Runnable runnable, long l, TimeUnit timeUnit) throws InterruptedException;
        }

        private interface RunnableTransferQueue extends BlockingQueue<Runnable>, TransferQueue<Runnable>, Queue<Runnable>, Collection<Runnable> { }

        private final @Delegate(excludes = Excludes.class, types = RunnableTransferQueue.class) TransferQueue<Runnable> delegate = new LinkedTransferQueue<Runnable>();
    }


    private final WorkItemQueue queue;
    private final QueueingRejectionHandler rejectionHandler = new QueueingRejectionHandler();
}
