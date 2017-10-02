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
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;

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
 * @see <a href="https://dzone.com/articles/scalable-java-thread-pool-executor">Article describing this approach (Workaround #2)</a>
 */
public class PayaraThreadPoolExecutor extends ThreadPoolExecutor {
    private WorkItemQueue queue;
    private final QueueingRejectionHandler rejectionHandler = new QueueingRejectionHandler();

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
        private RejectedExecutionHandler downstream;

        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor threadPoolExecutor) {
            if (!putToQueue(runnable) && downstream != null) {
                downstream.rejectedExecution(runnable, threadPoolExecutor);
            }
        }
    }

    private boolean putToQueue(Runnable runnable) {
        // Our queue is unbounded, so this likely won't fail.
        // but we shouldn't add more items when we're shutting down.
        return !isTerminating() && !isShutdown() && queue.add(runnable);
    }

    private static class WorkItemQueue implements BlockingQueue<Runnable> {
        private final TransferQueue<Runnable> delegate = new LinkedTransferQueue<>();

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

        // the rest is just plain delegation
        @Override
        public boolean add(Runnable runnable) {
            return delegate.add(runnable);
        }


        @Override
        public void put(Runnable runnable) throws InterruptedException {
            delegate.put(runnable);
        }


        @Override
        public Runnable take() throws InterruptedException {
            return delegate.take();
        }

        @Override
        public Runnable poll(long l, TimeUnit timeUnit) throws InterruptedException {
            return delegate.poll(l, timeUnit);
        }

        @Override
        public int remainingCapacity() {
            return delegate.remainingCapacity();
        }

        @Override
        public boolean remove(Object o) {
            return delegate.remove(o);
        }

        @Override
        public boolean contains(Object o) {
            return delegate.contains(o);
        }

        @Override
        public int drainTo(Collection<? super Runnable> collection) {
            return delegate.drainTo(collection);
        }

        @Override
        public int drainTo(Collection<? super Runnable> collection, int i) {
            return delegate.drainTo(collection, i);
        }

        @Override
        public Runnable remove() {
            return delegate.remove();
        }

        @Override
        public Runnable poll() {
            return delegate.poll();
        }

        @Override
        public Runnable element() {
            return delegate.element();
        }

        @Override
        public Runnable peek() {
            return delegate.peek();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public Iterator<Runnable> iterator() {
            return delegate.iterator();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] ts) {
            return delegate.toArray(ts);
        }

        @Override
        public boolean containsAll(Collection<?> collection) {
            return delegate.containsAll(collection);
        }

        @Override
        public boolean addAll(Collection<? extends Runnable> collection) {
            return delegate.addAll(collection);
        }

        @Override
        public boolean removeAll(Collection<?> collection) {
            return delegate.removeAll(collection);
        }

        @Override
        public boolean retainAll(Collection<?> collection) {
            return delegate.retainAll(collection);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

    }
}
