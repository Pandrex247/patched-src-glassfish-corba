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

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test the Payara CORBA thread pool, make sure it behaves
 * appropriately, not creating either too many or too few threads
 *
 * @author lprimak
 */
public class PayaraThreadPoolTest {
    @Test
    public void oneElementPool() {
        newExecutorTest(0, 1, 5, 0, false);
        assertEquals("Largest Pool Size", 1, executor.getLargestPoolSize());
        assertEquals("Core Pool Size", 0, executor.getCorePoolSize());
    }

    @Test
    public void maxConcurrentThreads() {
        newExecutorTest(10, 100, 500, 0, true);
        assertEquals("Largest Pool Size", 100, executor.getLargestPoolSize());
        assertEquals("Core Pool Size", 10, executor.getCorePoolSize());
    }

    @Test
    public void maxTasksMinConcurrentThreads() {
        newExecutorTest(10, 100, 500, 0, false);
        assertTrue("Largest Pool Size", executor.getLargestPoolSize() < 12);
        assertEquals("Core Pool Size", 10, executor.getCorePoolSize());
    }

    @Test
    public void queue() {
        newExecutorTest(10, 10, 500, 0, false);
        assertEquals("Largest Pool Size", 10, executor.getLargestPoolSize());
        assertEquals("Core Pool Size", 10, executor.getCorePoolSize());
    }

    @Test
    public void minPossibleThreads() {
        newExecutorTest(10, 100, 500, 50, true);
        assertTrue("Largest Pool Size", executor.getLargestPoolSize() < 52 && executor.getLargestPoolSize() > 48);
        assertEquals("Core Pool Size", 10, executor.getCorePoolSize());
    }

    @Test
    public void maxConcurrentThreadsLimit() {
        newExecutorTest(10, 20, 500, 50, true);
        assertEquals("Largest Pool Size", 20, executor.getLargestPoolSize());
        assertEquals("Core Pool Size", 10, executor.getCorePoolSize());
    }

    @Test
    public void minConcurrentThreadsLimit() {
        newExecutorTest(10, 20, 500, 50, false);
        assertEquals("Largest Pool Size", 10, executor.getLargestPoolSize());
        assertEquals("Core Pool Size", 10, executor.getCorePoolSize());
    }

    @Test
    public void minConcurrentThreadsLimit2() {
        newExecutorTest(10, 20, 500, 9, false);
        assertEquals("Largest Pool Size", 10, executor.getLargestPoolSize());
        assertEquals("Core Pool Size", 10, executor.getCorePoolSize());
    }

    private void newExecutorTest(int min, int max, int iterations, int numConcurrent, final boolean sleepAfterWorkerRan) {
        executor = new PayaraThreadPoolExecutor(min, max, 0, TimeUnit.SECONDS,
                new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "TestThread");
            }
        });
        executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                throw new IllegalStateException("Rejected Queue");
            }
        });

        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    executedTasks.incrementAndGet();
                    if(sleepAfterWorkerRan) {
                        Thread.sleep(20);
                    }
                } catch (InterruptedException ex) {
                    log.log(Level.SEVERE, "Interrupted", ex);
                }
            }
        };

        for(int iter = 0; iter < iterations; ++iter) {
            while ((numConcurrent > 0) && (executor.getActiveCount() > (numConcurrent - 1))) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    log.log(Level.SEVERE, "interrupted", ex);
                }
            }
            executor.execute(r);
            if (!sleepAfterWorkerRan) {
                // we want to demonstrate, that new threads are not spawned when idle threads are present. For that we need
                // to put a little less pressure on the executor, because even that AtomicInteger.increment takes a bit of time, and
                // we may run out of core threads.
                LockSupport.parkNanos(50);
            }
            ++scheduledTasks;
        }
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException ex) {
            log.log(Level.SEVERE, "Interrupted", ex);
        }
    }

    @Before
    public void resetVariables() {
        executedTasks = new AtomicInteger(0);
        scheduledTasks = 0;
    }

    @After
    public void checkExecutedTasks() {
        assertEquals("Executed Tasks", scheduledTasks, executedTasks.get());
        assertEquals("completedTaskCount()", scheduledTasks, executor.getCompletedTaskCount());
        assertEquals("taskCount()", executor.getTaskCount(), executor.getCompletedTaskCount());
    }


    private ThreadPoolExecutor executor = null;
    private AtomicInteger executedTasks = null;
    private volatile int scheduledTasks = -1;

    private final static Logger log = Logger.getLogger(PayaraThreadPoolTest.class.getName());
}
