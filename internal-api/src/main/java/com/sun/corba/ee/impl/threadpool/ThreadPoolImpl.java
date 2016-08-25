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
import java.util.concurrent.BlockingQueue;
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
        // +++ FIXME TODO remove max()
        threadPool = new ThreadPoolExecutor(5, Math.max(5, maxSize), timeout, TimeUnit.MILLISECONDS, 
                new LinkedBlockingQueue<Runnable>(maxSize * 100), 
                new ORBThreadFactory(tg, defaultClassLoader));
        threadPool.allowCoreThreadTimeOut(true);
        // TODO register with gmbal
    }

    
    static class ORBThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final ClassLoader classLoader;

        ORBThreadFactory(ThreadGroup group, ClassLoader classLoader) {
            this.group = group;
            this.classLoader = classLoader;
            namePrefix = "orb-threadpool-"
                    + poolNumber.getAndIncrement()
                    + "-thread-";
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
            Thread t = new Thread(group, r,
                    namePrefix + threadNumber.getAndIncrement(),
                    0);
            t.setContextClassLoader(cl);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;            
        }
    }
    
    
    @Override
    void submit(final Work item) {
        threadPool.submit(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                try {
                    queue.incrDequeue(item);
                    item.doWork();
                }
                catch(Throwable t) {
                        Exceptions.self.workerThreadThrowableFromRequestWork(t, 
                                ThreadPoolImpl.this, 
                                queue.getName());
                } finally {
                    ThreadStateValidator.checkValidators();
                    long elapsedTime = System.currentTimeMillis() - start;
                    totalTimeTaken.addAndGet(elapsedTime);
                }                
            }
        });
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
    
    // Running aggregate of the time taken in millis to execute work items
    // processed by the threads in the threadpool
    private final AtomicLong totalTimeTaken = new AtomicLong(0);

    public static final int DEFAULT_INACTIVITY_TIMEOUT = 120000;
}
