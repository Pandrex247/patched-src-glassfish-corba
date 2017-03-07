/*
 * Copyright (c) 2016 Payara Foundation. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 */
package com.sun.corba.ee.impl.threadpool;

import com.sun.corba.ee.spi.threadpool.NoSuchWorkQueueException;
import com.sun.corba.ee.spi.threadpool.ThreadPool;
import com.sun.corba.ee.spi.threadpool.ThreadStateValidator;
import com.sun.corba.ee.spi.threadpool.Work;
import com.sun.corba.ee.spi.threadpool.WorkQueue;
import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.glassfish.gmbal.Description;
import org.glassfish.gmbal.ManagedAttribute;
import org.glassfish.gmbal.ManagedObject;
import org.glassfish.gmbal.NameValue;

/**
 * Works with Work queue to implement thread pool
 * Complete re-write of the old WorkQueue / ThreadPool implementations
 * in terms of java.util.concurrent
 * 
 * @author lprimak
 */
@ManagedObject
@Description( "A ThreadPool API implementation used by the ORB" ) 
public class ThreadPoolImpl extends AbstractThreadPool implements ThreadPool {
    /** Create an unbounded thread pool in the current thread group
     * with the current context ClassLoader as the worker thread default
     * ClassLoader.
     * @param threadpoolName
     */
    public ThreadPoolImpl(String threadpoolName) {
        this( Thread.currentThread().getThreadGroup(), threadpoolName ) ; 
    }

    /** Create an unbounded thread pool in the given thread group
     * with the current context ClassLoader as the worker thread default
     * ClassLoader.
     * @param tg
     * @param threadpoolName
     */
    public ThreadPoolImpl(ThreadGroup tg, String threadpoolName ) {
        this( tg, threadpoolName, getDefaultClassLoader() ) ;
    }

    /** Create an unbounded thread pool in the given thread group
     * with the given ClassLoader as the worker thread default
     * ClassLoader.
     * @param tg
     * @param threadpoolName
     * @param defaultClassLoader
     */
    public ThreadPoolImpl(ThreadGroup tg, String threadpoolName, 
        ClassLoader defaultClassLoader) {
        this(0, Integer.MAX_VALUE, DEFAULT_INACTIVITY_TIMEOUT, threadpoolName, tg, defaultClassLoader);
    }
 
    /** Create a bounded thread pool in the current thread group
     * with the current context ClassLoader as the worker thread default
     * ClassLoader.
     * @param minSize
     * @param maxSize
     * @param timeout
     * @param threadpoolName
     */
    public ThreadPoolImpl( int minSize, int maxSize, long timeout, 
        String threadpoolName) {
        this( minSize, maxSize, timeout, threadpoolName, Thread.currentThread().getThreadGroup(),
                getDefaultClassLoader() ) ;
    }

    /** Create a bounded thread pool in the current thread group
     * with the given ClassLoader as the worker thread default
     * ClassLoader.
     * @param minSize
     * @param maxSize
     * @param timeout
     * @param threadpoolName
     * @param tg
     * @param defaultClassLoader
     */
    public ThreadPoolImpl( int minSize, int maxSize, long timeout, 
        String threadpoolName, ThreadGroup tg, ClassLoader defaultClassLoader ) 
    {
        queue = new WorkQueueImpl(this);
        name = threadpoolName;
        this.tg = tg;
        this.classLoader = defaultClassLoader;
        minSize = Math.max(minSize, 0);
        maxSize = Math.max(DEFAULT_MINIMUM_THREAD_POOL, maxSize);
        
        threadPool = new ThreadPoolExecutor(maxSize, maxSize, timeout, TimeUnit.MILLISECONDS, 
                new LinkedBlockingQueue<Runnable>(Math.max(maxSize * 100, Integer.MAX_VALUE)),
                new ORBThreadFactory(name, tg, defaultClassLoader, false));
        threadPool.allowCoreThreadTimeOut(true);
        for(int ii = 0; ii < minSize; ++ii) {
            threadPool.prestartCoreThread();
        }
        // TODO register with gmbal
    }

    
    static class ORBThreadFactory implements ThreadFactory {
        ORBThreadFactory(String name, ThreadGroup group, ClassLoader classLoader, boolean isLongRunning) {
            this.group = group;
            this.classLoader = classLoader;
            namePrefix = String.format("orb-%s (pool #%d)%s: worker", name, poolNumber.getAndIncrement(), isLongRunning? " - (long-running)" : "");
        }

        @Override
        public Thread newThread(final Runnable r) {
            if(System.getSecurityManager() == null) {
                return newThreadHelper(r, classLoader);
            } else {
                return AccessController.doPrivileged(new PrivilegedAction<Thread>() {
                    @Override
                    public Thread run() {
                        return newThreadHelper(r, classLoader);
                    }
                });
            }
        }
        
        private Thread newThreadHelper(Runnable r, ClassLoader cl) {
            Thread t = new Thread(group, r, String.format("%s-%d", namePrefix, threadNumber.getAndIncrement()), 0);
            t.setContextClassLoader(cl);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;            
        }
        
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final ClassLoader classLoader;
    }
    
    
    private class TaskRunner implements Runnable {
        public TaskRunner(Work item, boolean isLongRunning) {
            this.item = item;
            this.isLongRunning = isLongRunning;
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            try {
                if(!isLongRunning) {
                    queue.incrDequeue(item);
                }
                item.doWork();
            } catch (Throwable t) {
                Exceptions.self.workerThreadThrowableFromRequestWork(t,
                        ThreadPoolImpl.this,
                        queue.getName());
            } finally {
                ThreadStateValidator.checkValidators();
                if(!isLongRunning) {
                    long elapsedTime = System.currentTimeMillis() - start;
                    totalTimeTaken.addAndGet(elapsedTime);
                }
            }
        }
        
        private final Work item;
        private final boolean isLongRunning;
    }

    
    @Override
    void submit(final Work item, boolean isLongRunning) {
        ExecutorService task;
        if(isLongRunning) {
            task = Executors.newSingleThreadExecutor(new ORBThreadFactory(name, tg, classLoader, true));
            longRunningTasks.add(task);
        } else {
            task = threadPool;
        }
        task.submit(new TaskRunner(item, isLongRunning));
    }

    @Override
    BlockingQueue<Runnable> getQueue() {
        return threadPool.getQueue();
    }


    @Override
    public WorkQueue getAnyWorkQueue() {
        return queue;
    }

    @Override
    public WorkQueue getWorkQueue(int queueId) throws NoSuchWorkQueueException {
        if(queueId != 0) {
            throw new NoSuchWorkQueueException();
        }
        return queue;
    }

    @Override
    public int numberOfWorkQueues() {
        return 1;
    }

    @Override
    public int minimumNumberOfThreads() {
        return threadPool.getPoolSize();
    }

    @Override
    public int maximumNumberOfThreads() {
        return threadPool.getMaximumPoolSize();
    }

    @Override
    public long idleTimeoutForThreads() {
        return threadPool.getKeepAliveTime(TimeUnit.MILLISECONDS);
    }

    @Override
    @ManagedAttribute
    @Description( "The current number of threads" ) 
    public int currentNumberOfThreads() {
        return threadPool.getPoolSize();
    }

    @Override    
    @ManagedAttribute
    @Description( "The number of available threads in this ThreadPool" ) 
    public int numberOfAvailableThreads() {
        return threadPool.getPoolSize() - threadPool.getActiveCount();
    }

    @Override
    @ManagedAttribute
    @Description( "The number of threads busy processing work in this ThreadPool" ) 
    public int numberOfBusyThreads() {
        return threadPool.getActiveCount();
    }

    @Override
    @ManagedAttribute
    @Description( "The number of work items processed" ) 
    public long currentProcessedCount() 
    {
        return threadPool.getCompletedTaskCount();
    }

    @Override
    @ManagedAttribute
    @Description( "The average time needed to complete a work item" ) 
    public long averageWorkCompletionTime() {
        return totalTimeTaken.get() / (threadPool.getCompletedTaskCount() == 0? 
                1 : threadPool.getCompletedTaskCount());
    }

    @Override
    @NameValue
    public String getName() {
        return name;
    }

    @Override
    public void close() throws IOException {
        threadPool.shutdown();
        for(ExecutorService e : longRunningTasks) {
            e.shutdown();
        }
    }
    
    
    private static ClassLoader getDefaultClassLoader() {
        if (System.getSecurityManager() == null)
            return Thread.currentThread().getContextClassLoader() ;
        else {
            final ClassLoader cl = AccessController.doPrivileged( 
                new PrivilegedAction<ClassLoader>() {
                    @Override
                    public ClassLoader run() {
                        return Thread.currentThread().getContextClassLoader() ;
                    }
                } 
            ) ;

            return cl ;
        }
    }
    
    
    ThreadPoolExecutor getPoolImpl() {
        return threadPool;
    }

    
    private final ThreadPoolExecutor threadPool;
    private final WorkQueueImpl queue;
    private final String name;
    private final ThreadGroup tg;
    private final ClassLoader classLoader;
    private final Set<ExecutorService> longRunningTasks = new ConcurrentSkipListSet<ExecutorService>(new Comparator<ExecutorService>() {
        @Override
        public int compare(ExecutorService o1, ExecutorService o2) {
            return Integer.compare(System.identityHashCode(o1), System.identityHashCode(o2));
        }
    });

    // Running aggregate of the time taken in millis to execute work items
    // processed by the threads in the threadpool
    private final AtomicLong totalTimeTaken = new AtomicLong(0);

    public static final int DEFAULT_INACTIVITY_TIMEOUT = 120000;
    public static final int DEFAULT_MINIMUM_THREAD_POOL = 10;
}
