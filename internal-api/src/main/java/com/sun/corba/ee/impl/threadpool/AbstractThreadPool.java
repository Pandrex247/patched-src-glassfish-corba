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
package com.sun.corba.ee.impl.threadpool;

import com.sun.corba.ee.spi.threadpool.ThreadPool;
import com.sun.corba.ee.spi.threadpool.Work;
import java.util.concurrent.BlockingQueue;

/**
 * Private interfaces for Thread Pool Implementation
 * 
 * @author lprimak
 */
abstract class AbstractThreadPool implements ThreadPool {
   abstract void submit(Work item); 
   abstract BlockingQueue<Runnable> getQueue();
}
