/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1996-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
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

package com.sun.corba.se.impl.transport;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import org.omg.CORBA.CompletionStatus;
import org.omg.CORBA.INTERNAL;
import org.omg.CORBA.SystemException;

import com.sun.org.omg.SendingContext.CodeBase;
import com.sun.corba.se.impl.encoding.CDRInputObject;
import com.sun.corba.se.impl.encoding.CDROutputObject;
import com.sun.corba.se.spi.transport.CorbaAcceptor;
import com.sun.corba.se.spi.transport.CorbaConnectionCache;
import com.sun.corba.se.spi.transport.EventHandler;
import com.sun.corba.se.spi.transport.CorbaInboundConnectionCache;
import com.sun.corba.se.spi.transport.CorbaOutboundConnectionCache;
import com.sun.corba.se.spi.transport.Selector;

import com.sun.corba.se.spi.ior.IOR;
import com.sun.corba.se.spi.ior.iiop.GIOPVersion;
import com.sun.corba.se.spi.orb.ORB ;
import com.sun.corba.se.spi.orbutil.threadpool.NoSuchThreadPoolException;
import com.sun.corba.se.spi.orbutil.threadpool.NoSuchWorkQueueException;
import com.sun.corba.se.spi.orbutil.threadpool.Work;
import com.sun.corba.se.spi.protocol.CorbaMessageMediator;
import com.sun.corba.se.spi.protocol.CorbaRequestId;
import com.sun.corba.se.spi.protocol.MessageParser;
import com.sun.corba.se.spi.transport.CorbaContactInfo;
import com.sun.corba.se.spi.transport.CorbaConnection;
import com.sun.corba.se.spi.transport.CorbaResponseWaitingRoom;
import com.sun.corba.se.spi.transport.TcpTimeouts;

import com.sun.corba.se.impl.encoding.CachedCodeBase;
import com.sun.corba.se.impl.encoding.CodeSetComponentInfo;
import com.sun.corba.se.impl.encoding.OSFCodeSetRegistry;
import com.sun.corba.se.impl.logging.ORBUtilSystemException;
import com.sun.corba.se.spi.orbutil.ORBConstants;
import com.sun.corba.se.impl.protocol.CorbaMessageMediatorImpl;
import com.sun.corba.se.impl.protocol.giopmsgheaders.Message;
import com.sun.corba.se.impl.protocol.giopmsgheaders.MessageBase;
import com.sun.corba.se.impl.protocol.MessageParserImpl;
import com.sun.corba.se.spi.orbutil.tf.annotation.InfoMethod;
import com.sun.corba.se.spi.trace.Transport;

/**
 * @author Harold Carr
 * 
 * Note: this is the version WITHOUT the purgeCalls changes.
 * The changes are in the 1.106 version, which is saved as
 * SocketOrChannelConnectionImpl.1.106.sjava.
 */
@Transport
public class SocketOrChannelConnectionImpl
    extends
	EventHandlerBase
    implements
        CorbaConnection,
	Work
{

    final private static boolean dprintWriteLocks = false;

    ///
    // New transport.
    //

    protected SocketChannel socketChannel;
    public SocketChannel getSocketChannel()
    {
	return socketChannel;
    }

    protected ByteBuffer byteBuffer = null;
    protected long enqueueTime;

    // REVISIT:
    // protected for test: genericRPCMSGFramework.IIOPConnection constructor.
    protected CorbaContactInfo contactInfo;
    protected CorbaAcceptor acceptor;
    protected CorbaConnectionCache connectionCache;
        
    //
    // From iiop.Connection.java
    //

    protected Socket socket;    // The socket used for this connection.
    protected long timeStamp = 0;
    protected boolean isServer = false;

    // Start at some value other than zero since this is a magic
    // value in some protocols.
    protected AtomicInteger requestId = new AtomicInteger(5);
    protected CorbaResponseWaitingRoom responseWaitingRoom;
    protected int state;
    protected final java.lang.Object stateEvent = new java.lang.Object();
    protected final java.lang.Object writeEvent = new java.lang.Object();
    protected boolean writeLocked;
    protected int serverRequestCount = 0;
    
    // Server request map: used on the server side of Connection
    // Maps request ID to IIOPInputStream.
    Map<Integer,CorbaMessageMediator> serverRequestMap = null;

    // This is a flag associated per connection telling us if the
    // initial set of sending contexts were sent to the receiver
    // already...
    protected boolean postInitialContexts = false;
 
    // Remote reference to CodeBase server (supplies
    // FullValueDescription, among other things)
    protected IOR codeBaseServerIOR;

    // CodeBase cache for this connection.  This will cache remote operations,
    // handle connecting, and ensure we don't do any remote operations until
    // necessary.
    protected CachedCodeBase cachedCodeBase = new CachedCodeBase(this);

    protected ORBUtilSystemException wrapper ;

    // transport read / write timeout values
    protected TcpTimeouts tcpTimeouts;

    // A temporary selector for reading from non-blocking SocketChannels
    // when entire message is not read in one read.
    protected TemporarySelector tmpReadSelector;
    // A lock used for lazily initializing tmpReadSelector
    protected final java.lang.Object tmpReadSelectorLock = new java.lang.Object();
    // A temporary selector for writing to non-blocking SocketChannels
    // when entire message is not written in one write().
    protected TemporarySelector tmpWriteSelector;
    // A lock used for lazily initializing tmpWriteSelector
    protected final java.lang.Object tmpWriteSelectorLock = new java.lang.Object();

    // Mapping of a fragmented messages by request id and its corresponding
    // fragmented messages stored in a queue. This mapping is used in the
    // optimized read strategy when message fragments arrive for a given
    // request id to ensure that message fragments get processed in the order
    // in which they arrive.
    // This is a ConcurrentHashMap because one Worker Thread can be putting
    // new entries into the ConcurrentHashMap after parsing new messages
    // via the MessageParser while a different Worker Thread can be removing
    // a different entry as a result of a different request id's final
    // message fragment having just been processed by a CorbaMessageMediator
    // via one of its handleInput methods.
    protected ConcurrentHashMap<CorbaRequestId, Queue> fragmentMap;

    // Used in genericRPCMSGFramework test.
    protected SocketOrChannelConnectionImpl(ORB orb)
    {
	this.orb = orb;
	wrapper =  orb.getLogWrapperTable().get_RPC_TRANSPORT_ORBUtil() ;

	setWork(this);
	responseWaitingRoom = new CorbaResponseWaitingRoomImpl(orb, this);
	setTcpTimeouts(orb.getORBData().getTransportTcpTimeouts());
    }

    // Both client and servers.
    protected SocketOrChannelConnectionImpl(ORB orb,
					    boolean useSelectThreadToWait,
					    boolean useWorkerThread)
    {
	this(orb) ;
	setUseSelectThreadToWait(useSelectThreadToWait);
	setUseWorkerThreadForEvent(useWorkerThread);
        
        if (useSelectThreadToWait) {
            // initialize fragmentMap
            fragmentMap = new ConcurrentHashMap<CorbaRequestId,Queue>();
        }
    }

    @Transport
    private void connectionCreated( Socket socket ) { }

    // Client constructor.
    public SocketOrChannelConnectionImpl(ORB orb,
					 CorbaContactInfo contactInfo,
					 boolean useSelectThreadToWait,
					 boolean useWorkerThread,
					 String socketType,
					 String hostname,
					 int port)
    {
	this(orb, useSelectThreadToWait, useWorkerThread);

	this.contactInfo = contactInfo;

	try {
	    socket = orb.getORBData().getSocketFactory()
		.createSocket(socketType,
			      new InetSocketAddress(hostname, port));
	    socketChannel = socket.getChannel();

	    if (socketChannel != null) {
		boolean isBlocking = !useSelectThreadToWait;
		socketChannel.configureBlocking(isBlocking);
	    } else {
		// IMPORTANT: non-channel-backed sockets must use
		// dedicated reader threads.
		setUseSelectThreadToWait(false);
	    }
            connectionCreated( socket ) ;
	} catch (Throwable t) {
	    throw wrapper.connectFailure(t, socketType, hostname, 
					 Integer.toString(port));
	}
	state = OPENING;
    }

    // Client-side convenience.
    public SocketOrChannelConnectionImpl(ORB orb,
					 CorbaContactInfo contactInfo,
					 String socketType,
					 String hostname,
					 int port)
    {
	this(orb, contactInfo,
	     orb.getORBData().connectionSocketUseSelectThreadToWait(),
	     orb.getORBData().connectionSocketUseWorkerThreadForEvent(),
	     socketType, hostname, port);
    }

    // Server-side constructor.
    public SocketOrChannelConnectionImpl(ORB orb,
					 CorbaAcceptor acceptor,
					 Socket socket,
					 boolean useSelectThreadToWait,
					 boolean useWorkerThread)
    {
	this(orb, useSelectThreadToWait, useWorkerThread);

	this.socket = socket;
	socketChannel = socket.getChannel();
	if (socketChannel != null) {
	    // REVISIT
            try {
                boolean isBlocking = !useSelectThreadToWait;
                socketChannel.configureBlocking(isBlocking);
                
            } catch (IOException e) {
                RuntimeException rte = new RuntimeException();
                rte.initCause(e);
                throw rte;
            }
	}
	this.acceptor = acceptor;

	serverRequestMap = Collections.synchronizedMap(
	    new HashMap<Integer,CorbaMessageMediator>());
        isServer = true;

	state = ESTABLISHED;
    }

    // Server-side convenience
    public SocketOrChannelConnectionImpl(ORB orb,
					 CorbaAcceptor acceptor,
					 Socket socket)
    {
	this(orb, acceptor, socket,
	     (socket.getChannel() == null 
	      ? false 
	      : orb.getORBData().connectionSocketUseSelectThreadToWait()),
	     (socket.getChannel() == null
	      ? false		     
	      : orb.getORBData().connectionSocketUseWorkerThreadForEvent()));
    }

    ////////////////////////////////////////////////////
    //
    // framework.transport.Connection
    //

    public boolean shouldRegisterReadEvent()
    {
	return true;
    }

    public boolean shouldRegisterServerReadEvent()
    {
	return true;
    }

    @Transport
    public boolean read() {
        CorbaMessageMediator messageMediator = readBits();
        if (messageMediator != null) {
            // Null can happen when client closes stream
            // causing purgecalls.
            return messageMediator.dispatch();
        }
        return true;
    }

    private void unregisterForEventAndPurgeCalls(SystemException ex)
    {
	// REVISIT - make sure reader thread is killed.
	orb.getTransportManager().getSelector(0).unregisterForEvent(this);
	// Notify anyone waiting.
	purgeCalls(ex, true, false);
    }

    @Transport
    protected CorbaMessageMediator readBits() {
	try {
	    CorbaMessageMediator messageMediator;
	    // REVISIT - use common factory base class.
	    if (contactInfo != null) {
		messageMediator =
		    contactInfo.createMessageMediator(orb, this);
	    } else if (acceptor != null) {
		messageMediator = acceptor.createMessageMediator(orb, this);
	    } else {
		throw 
		    new RuntimeException("SocketOrChannelConnectionImpl.readBits");
	    }
	    return (CorbaMessageMediator) messageMediator;

	} catch (ThreadDeath td) {
	    try {
		purgeCalls(wrapper.connectionAbort(td), false, false);
	    } catch (Throwable t) {
                exceptionInfo( "purgeCalls", t ) ;
	    }
	    throw td;
	} catch (Throwable ex) {
            exceptionInfo( "readBits", ex);

	    if (ex instanceof SystemException) {
		SystemException se = (SystemException)ex;
	        if (se.minor == ORBUtilSystemException.CONNECTION_REBIND) {
	            unregisterForEventAndPurgeCalls(se);
		    throw se;
		} else {
	            try {
		        if (se instanceof INTERNAL) {
		            sendMessageError(GIOPVersion.DEFAULT_VERSION);
		        }
	            } catch (IOException e) {
                        exceptionInfo( "sendMessageError", e ) ;
	            }
		}
	    }
	    unregisterForEventAndPurgeCalls(wrapper.connectionAbort(ex));

	    // REVISIT
	    //keepRunning = false;
	    // REVISIT - if this is called after purgeCalls then
	    // the state of the socket is ABORT so the writeLock
	    // in close throws an exception.  It is ignored but
	    // causes IBM (screen scraping) tests to fail.
	    //close();
	    throw wrapper.throwableInReadBits(ex);
	} 
    }

    public boolean shouldUseDirectByteBuffers()
    {
	return getSocketChannel() != null;
    }

    // NOTE: This method can throw a connection rebind SystemException.
    @Transport
    public ByteBuffer read(int size, int offset, int length )
	throws IOException {
	try {
	    if (shouldUseDirectByteBuffers()) {
	        ByteBuffer lbb = orb.getByteBufferPool().getByteBuffer(size);

	        if (orb.transportDebugFlag) {
		    int bbAddress = System.identityHashCode(lbb);
                    bbInfo( bbAddress ) ;
	        }
	    
	        lbb.position(offset);
	        lbb.limit(size);
	        readFully(lbb, length );
	        return lbb;
	    }

	    byte[] buf = new byte[size];
	    // getSocket().getInputStream() can throw an IOException
	    // if the socket is closed. Hence, we check the connection
	    // state CLOSE_RECVD if an IOException is thrown here 
	    // instead of in readFully()
	    readFully(getSocket().getInputStream(), buf,
		      offset, length );
	    ByteBuffer nbb = ByteBuffer.wrap(buf);
	    nbb.limit(size);
	    return nbb;
	} catch (IOException ioe) {
	    if (state == CLOSE_RECVD) {
		throw wrapper.connectionRebind(ioe);
	    } else {
		throw ioe;
	    }
	}
    }

    // NOTE: This method is used only when the ORB is configured with
    //       "useNIOSelectToWait=false", aka use blocking Sockets/SocketChannels.
    // NOTE: This method can throw a connection rebind SystemException.
    @Transport
    public ByteBuffer read(ByteBuffer byteBuffer, int offset, int length)
	throws IOException
    {
	try {
	    int size = offset + length;
	    if (shouldUseDirectByteBuffers()) {
	        if (size > byteBuffer.capacity()) {
		    if (orb.transportDebugFlag) {
		        // print address of ByteBuffer being released
		        int bbAddress = System.identityHashCode(byteBuffer);
                        bbInfo( bbAddress ) ;
		    }
		    orb.getByteBufferPool().releaseByteBuffer(byteBuffer);
		    byteBuffer = orb.getByteBufferPool().getByteBuffer(size);
	        }
	        byteBuffer.position(offset);
	        byteBuffer.limit(size);
	        readFully(byteBuffer, length);
	        byteBuffer.position(0);
	        byteBuffer.limit(size);
	        return byteBuffer;
	    }
	    if (byteBuffer.isDirect()) {
	        throw wrapper.unexpectedDirectByteBufferWithNonChannelSocket();
	    }
	    byte[] buf = new byte[size];
	    // getSocket().getInputStream() can throw an IOException
	    // if the socket is closed. Hence, we check the connection
	    // state CLOSE_RECVD if an IOException is thrown here 
	    // instead of in readFully()
	    readFully(getSocket().getInputStream(), buf, 
		      offset, length);
	    return ByteBuffer.wrap(buf);
	} catch (IOException ioe) {
	    if (state == CLOSE_RECVD) {
		throw wrapper.connectionRebind(ioe);
	    } else {
		throw ioe;
	    }
	} 
    }

    // REVISIT - Logic in this method that utilizes TCP timeouts can be removed
    //           removed since this method is used only when 
    //           'useNIOSelectToWait=false', aka blocking SocketChannels/Sockets.
    // NOTE: This method is used only when the ORB is configured with
    //       "useNIOSelectToWait=false", aka use blocking Sockets/SocketChannels
    @Transport
    private void readFully(ByteBuffer byteBuffer, int size) 
	throws IOException
    {
        int n = 0;
	int bytecount = 0;
	TcpTimeouts.Waiter waiter = tcpTimeouts.waiter() ;

	// The reading of data incorporates a strategy to detect a
	// rogue client.

	do {
	    bytecount = getSocketChannel().read(byteBuffer);
            readBytesFromChannel( bytecount ) ;

	    if (bytecount < 0) {
		throw new IOException("End-of-stream");
	    } else if (bytecount == 0) {
                TemporarySelector tmpSelector = null;
                SelectionKey sk = null;
                try {
                    tmpSelector = getTemporaryReadSelector();
                    sk = tmpSelector.registerChannel(getSocketChannel(),
                                                     SelectionKey.OP_READ);
                    do {
                        int nsel = tmpSelector.select(waiter.getTimeForSleep());
                        if (nsel > 0) {
                            tmpSelector.removeSelectedKey(sk);
                            bytecount = getSocketChannel().read(byteBuffer);
                            readBytesFromChannel(bytecount);

                            if (bytecount < 0) {
                                throw new IOException("End-of-stream");
                            } else {
                                n += bytecount;
                            }
                        }

                        if (n < size) {
                            // not all bytes read, increase select timeout
			    // REVISIT - Should we only increase wait timeout if
			    //		 an actual timeout occurred?
			    waiter.advance() ;
                        }
                    } while (n < size && !waiter.isExpired());
                } catch (IOException ioe) {
                    throw wrapper.exceptionWhenReadingWithTemporarySelector(ioe,
                                            n, size, waiter.timeWaiting(), 
                                            tcpTimeouts.get_max_time_to_wait());
                } finally {
                    if (tmpSelector != null) {
                        tmpSelector.cancelAndFlushSelector(sk);
                    }
                    doneWithTemporarySelector() ;
                }
	    } else {
		n += bytecount;
	    }
	} while (n < size && !waiter.isExpired());

	if (n < size && waiter.isExpired()) {
	    // failed to read entire message
	    throw wrapper.transportReadTimeoutExceeded(size, n, 
				      tcpTimeouts.get_max_time_to_wait(),
				      waiter.timeWaiting());
	}
    }

    // NOTE: This method is used only when the ORB is configured with
    //       "useNIOSelectToWait=false", aka use blocking java.net.Socket
    // REVISIT - Logic in this method that utilizes TCP timeouts can be removed
    //           removed since this method use is for blocking java.net.Sockets.
    // To support non-channel connections.
    @Transport
    public void readFully(java.io.InputStream is, byte[] buf,
			  int offset, int size ) 
	throws IOException
    {
        int n = 0;
	int bytecount = 0;
	TcpTimeouts.Waiter waiter = tcpTimeouts.waiter() ;

	// The reading of data incorporates a strategy to detect a
	// rogue client. The strategy is implemented as follows. As
	// long as data is being read, at least 1 byte or more, we
	// assume we have a well behaved client. If no data is read,
	// then we sleep for a time to wait, re-calculate a new time to
	// wait which is lengthier than the previous time spent waiting.
	// Then, if the total time spent waiting does not exceed a
	// maximum time we are willing to wait, we attempt another
	// read. If the maximum amount of time we are willing to
	// spend waiting for more data is exceeded, we throw an
	// IOException.

	// NOTE: Reading of GIOP headers are treated with a smaller
	//       maximum time to wait threshold. Based on extensive
	//       performance testing, all GIOP headers are being
	//       read in 1 read access.

	do {
	    bytecount = is.read(buf, offset + n, size - n);
            readBytesFromChannel(bytecount);

	    if (bytecount < 0) {
		throw new IOException("End-of-stream");
	    } else if (bytecount == 0) {
                // REVISIT - This entire if (bytecount == 0)
                //           block of code can be removed
                //           since is.read() is a blocking
                //           read and it is not possible
                //           to read 0 bytes without
                //           throwing an IOException.
                readFullySleeping( waiter.getTime() ) ;

		waiter.sleepTime() ;
		waiter.advance() ;
	    } else {
		n += bytecount;
	    }
	} while (n < size && !waiter.isExpired());

	if (n < size && waiter.isExpired()) {
	    // failed to read entire message
	    throw wrapper.transportReadTimeoutExceeded(
		size, n, tcpTimeouts.get_max_time_to_wait(), 
		waiter.timeWaiting());
	}
    }    

    // NOTE: This method can throw a connection rebind SystemException.
    @Transport
    public void write(ByteBuffer byteBuffer) throws IOException {
        try {
            if (shouldUseDirectByteBuffers()) {

	        /**
                 * NOTE: cannot perform this test.  If one ask for a
	         * ByteBuffer from the pool which is bigger than the size
	         * of ByteBuffers managed by the pool, then the pool will
	         * return a HeapByteBuffer.
	         * if (byteBuffer.hasArray()) {
		 *     throw wrapper.unexpectedNonDirectByteBufferWithChannelSocket();
	         * }
	         */

                // IMPORTANT: For non-blocking SocketChannels, there's no guarantee
                //            all bytes are written on first write attempt.

                int nbytes = getSocketChannel().write(byteBuffer);
                if (byteBuffer.hasRemaining()) {
                    // Can only occur on non-blocking connections.
                    // Using long for backoff_factor to avoid floating point
                    // calculations.
		    TcpTimeouts.Waiter waiter = tcpTimeouts.waiter() ;
                    SelectionKey sk = null;
                    TemporarySelector tmpSelector = null;
                    try {
                        tmpSelector = getTemporaryWriteSelector();
                        sk = tmpSelector.registerChannel(getSocketChannel(),
                                                        SelectionKey.OP_WRITE);
                        while (byteBuffer.hasRemaining() && !waiter.isExpired()) {
                            int nsel = tmpSelector.select(waiter.getTimeForSleep());
                            if (nsel > 0) {
                                tmpSelector.removeSelectedKey(sk);
                                do {
                                    // keep writing while bytes can be written
                                    nbytes = getSocketChannel().write(byteBuffer);
                                } while (nbytes > 0 && byteBuffer.hasRemaining());
                            }
                            // selector timed out or no bytes have been written
                            if (nsel == 0 || nbytes == 0) {
				waiter.advance() ;
                            }
                        }
                    } catch (IOException ioe) {
                        throw wrapper.exceptionWhenWritingWithTemporarySelector(ioe,
                            byteBuffer.position(), byteBuffer.limit(),
                            waiter.timeWaiting(), tcpTimeouts.get_max_time_to_wait());
                    } finally {
                        if (tmpSelector != null) {
                            tmpSelector.cancelAndFlushSelector(sk);
                        }
                        doneWithTemporarySelector();
                    }
                    // if message not fully written, throw exception
                    if (byteBuffer.hasRemaining() && waiter.isExpired()) {
                        // failed to write entire message
                        throw wrapper.transportWriteTimeoutExceeded( 
			    tcpTimeouts.get_max_time_to_wait(), waiter.timeWaiting());
                    }
                }
	    } else {
                if (! byteBuffer.hasArray()) {
		    throw wrapper.unexpectedDirectByteBufferWithNonChannelSocket();
	        }

	        byte[] tmpBuf = new byte[byteBuffer.limit()];
                System.arraycopy(byteBuffer.array(), byteBuffer.arrayOffset(),
                                 tmpBuf, 0, tmpBuf.length);
                // NOTE: Cannot simply use byteBuffer.array() since byteBuffer
                // could be a view buffer / sliced ByteBuffer. View buffers /
                // sliced ByteBuffers will return the entired backed array.
                // Not a byte array beginning at view buffer position 0.
	        getSocket().getOutputStream().write(tmpBuf, 0, tmpBuf.length);
	        getSocket().getOutputStream().flush();
	    }
	
	    // TimeStamp connection to indicate it has been used
	    // Note granularity of connection usage is assumed for
	    // now to be that of a IIOP packet.
	    getConnectionCache().stampTime(this);
	} catch (IOException ioe) {
	    if (state == CLOSE_RECVD) {
		throw wrapper.connectionRebind(ioe);
	    } else {
		throw ioe;
	    }
	} 
    }

    /**
     * Note:it is possible for this to be called more than once
     */
    @Transport
    public synchronized void close() {
        writeLock();

        // REVISIT It will be good to have a read lock on the reader thread
        // before we proceed further, to avoid the reader thread (server side)
        // from processing requests. This avoids the risk that a new request
        // will be accepted by ReaderThread while the ListenerThread is
        // attempting to close this connection.

        if (isBusy()) { // we are busy!
            writeUnlock();
            doNotCloseBusyConnection() ;
            return;
        }

        try {
            try {
                sendCloseConnection(GIOPVersion.V1_0);
            } catch (Throwable t) {
                wrapper.exceptionWhenSendingCloseConnection(t);
            }

            synchronized ( stateEvent ){
                state = CLOSE_SENT;
                stateEvent.notifyAll();
            }

            // stop the reader without causing it to do purgeCalls
            //Exception ex = new Exception();
            //reader.stop(ex); // REVISIT

            // NOTE: !!!!!!
            // This does writeUnlock().
            purgeCalls(wrapper.connectionRebind(), false, true);

        } catch (Exception ex) {
            // XXX log this
        }

        closeConnectionResources();
    }

    @Transport
    public void closeConnectionResources() {
        Selector selector = orb.getTransportManager().getSelector(0);
        selector.unregisterForEvent(this);
        closeSocketAndTemporarySelectors();
    }

    @Transport
    protected void closeSocketAndTemporarySelectors() {
        try {
            if (socketChannel != null) {
                closeTemporarySelectors();
                // NOTE: Until JDK bug 6215050 is fixed in Java 5, do not use
                //       socketChannel.close(). Instead shutdown input &
                //       output streams on the Socket and use socket.close().
                //       JDK bug 6215050 was fixed in JDK 1.5.0_07. Can use
                //       socketChannel.close() for 1.5.0_07 and later JDKs.
                if (!socketChannel.socket().isInputShutdown()) {
                    shuttingDownConnectionInputStream() ;
                    socketChannel.socket().shutdownInput();
                }
                if (!socketChannel.socket().isOutputShutdown()) {
                    shuttingDownConnectionOutputStream() ;
                    socketChannel.socket().shutdownOutput();
                }
                if (!socketChannel.socket().isClosed()) {
                    socketChannel.socket().close();
                }
            }
            
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            
        } catch (IOException e) {
            // XXX log this
        }
    }

    public CorbaAcceptor getAcceptor()
    {
	return acceptor;
    }

    public CorbaContactInfo getContactInfo()
    {
	return contactInfo;
    }

    public EventHandler getEventHandler()
    {
	return this;
    }

    public CDROutputObject createOutputObject(CorbaMessageMediator messageMediator)
    {
	// REVISIT - remove this method from Connection and all it subclasses.
	throw new RuntimeException("*****SocketOrChannelConnectionImpl.createOutputObject - should not be called.");
    }

    // This is used by the GIOPOutputObject in order to
    // throw the correct error when handling code sets.
    // Can we determine if we are on the server side by
    // other means?  XREVISIT
    public boolean isServer()
    {
        return isServer;
    }

    public boolean isClosed()
    {
	boolean result = true;
        if (socketChannel != null)
	{
	    result = ! socketChannel.isOpen();
	}
	else if (socket != null)
	{
	    result = socket.isClosed();
	}
	return result;
    }

    public boolean isBusy()
    {
        if (serverRequestCount > 0 ||
	    getResponseWaitingRoom().numberRegistered() > 0)
        {
            return true;
	} else {
            return false;
	}
    }

    public long getTimeStamp()
    {
	return timeStamp;
    }

    public void setTimeStamp(long time)
    {
	timeStamp = time;
    }

    public void setState(String stateString)
    {
	synchronized (stateEvent) {
	    if (stateString.equals("ESTABLISHED")) {
		state =  ESTABLISHED;
		stateEvent.notifyAll();
	    } else {
		// REVISIT: ASSERT
	    }
	}
    }

    /**
     * Sets the writeLock for this connection.
     * If the writeLock is already set by someone else, block till the
     * writeLock is released and can set by us.
     * IMPORTANT: this connection's lock must be acquired before
     * setting the writeLock and must be unlocked after setting the writeLock.
     */
    @Transport
    public void writeLock() {
        // Keep looping till we can set the writeLock.
        while ( true ) {
            int localState;
            synchronized (stateEvent) {
                localState = state;
            }

            localStateInfo( localState ) ;

            switch (localState) {
                 
            case OPENING:
                synchronized (stateEvent) {
                    if (state != OPENING) {
                        // somebody has changed 'state' so be careful
                        break;
                    }
                    try {
                        stateEvent.wait();
                    } catch (InterruptedException ie) {
                        // XXX log this
                    }
                }
                // Loop back
                break;
            
            case ESTABLISHED:
                synchronized (writeEvent) {
                    if (!writeLocked) {
                        writeLocked = true;
                        return;
                    }
                
                    try {
                        // do not stay here too long if state != ESTABLISHED
                        // Bug 4752117
                        while (state == ESTABLISHED && writeLocked) {
                            writeEvent.wait(100);
                        }
                    } catch (InterruptedException ie) {
                        // XXX log this
                    }
                }
                // Loop back
                break;
            
                //
                // XXX
                // Need to distinguish between client and server roles
                // here probably.
                //
            case ABORT:
                synchronized ( stateEvent ){
                    if (state != ABORT) {
                        break;
                    }
                    throw wrapper.writeErrorSend() ;
                }
                 
            case CLOSE_RECVD:
                // the connection has been closed or closing
                // ==> throw rebind exception
                synchronized ( stateEvent ){
                    if (state != CLOSE_RECVD) {
                        break;
                    }
                    throw wrapper.connectionRebind() ;
                }
            
            default:
                // REVISIT
                throw new RuntimeException(".writeLock: bad state");
            }
        }
    }

    @Transport
    public void writeUnlock()
    {
        synchronized (writeEvent) {
            writeLocked = false;
            writeEvent.notify(); // wake up one guy waiting to write
        }
    }

    // Assumes the caller handles writeLock and writeUnlock
    // NOTE: This method can throw a connection rebind SystemException.
    public void sendWithoutLock(CDROutputObject outputObject)
    {
        // Don't we need to check for CloseConnection
        // here?  REVISIT

        // XREVISIT - Shouldn't the MessageMediator 
        // be the one to handle writing the data here?

        try {
            // Write the fragment/message
	    CDROutputObject cdrOutputObject = (CDROutputObject) outputObject;
	    cdrOutputObject.writeTo(this);

	    // REVISIT - no flush?
            //socket.getOutputStream().flush();

        } catch (IOException exc) {
            // Since IIOPOutputStream's msgheader is set only once, and not
            // altered during sending multiple fragments, the original 
            // msgheader will always have the requestId.
	    // REVISIT This could be optimized to send a CancelRequest only
	    // if any fragments had been sent already.

            // IIOPOutputStream will cleanup the connection info when it
            // sees this exception.
	    final SystemException sysexc = (state == CLOSE_RECVD) ?
		wrapper.connectionRebind( CompletionStatus.COMPLETED_MAYBE, exc ) :
	        wrapper.writeErrorSend(CompletionStatus.COMPLETED_MAYBE, exc ) ;

	    purgeCalls( sysexc, false, true ) ;

	    throw sysexc ;
        }
    }

    public void registerWaiter(CorbaMessageMediator messageMediator)
    {
        responseWaitingRoom.registerWaiter(messageMediator);
    }

    public void unregisterWaiter(CorbaMessageMediator messageMediator)
    {
        responseWaitingRoom.unregisterWaiter(messageMediator);
    }

    public CDRInputObject waitForResponse(CorbaMessageMediator messageMediator)
    {
	return responseWaitingRoom.waitForResponse(messageMediator);
    }

    public void setConnectionCache(CorbaConnectionCache connectionCache)
    {
	this.connectionCache = connectionCache;
    }

    public CorbaConnectionCache getConnectionCache()
    {
	return connectionCache;	
    }

    ////////////////////////////////////////////////////
    //
    // EventHandler methods
    //

    @Override
    public void setUseSelectThreadToWait(boolean x)
    {
	useSelectThreadToWait = x;
    }

    public SelectableChannel getChannel()
    {
	return socketChannel;
    }

    public int getInterestOps()
    {
	return SelectionKey.OP_READ;
    }

    //    public Acceptor getAcceptor() - already defined above.

    public CorbaConnection getConnection()
    {
	return this;
    }

    ////////////////////////////////////////////////////
    //
    // Work methods.
    //

    public String getName()
    {
	return this.toString();
    }

    @Transport
    public void doWork() {
	try {
	    // IMPORTANT: Sanity checks on SelectionKeys such as
	    //            SelectorKey.isValid() should not be done
	    //            here.
	    //
	    
            if (!shouldUseSelectThreadToWait()) {
                read();
            } else {
                // use optimized read strategy
                doOptimizedReadStrategy();
            }
	} catch (Throwable t) {
            exceptionInfo(t);
	} 
    }

    public void setEnqueueTime(long timeInMillis)
    {
	enqueueTime = timeInMillis;
    }

    public long getEnqueueTime()
    {
	return enqueueTime;
    }

    ////////////////////////////////////////////////////
    //
    // spi.transport.CorbaConnection.
    //

    public CorbaResponseWaitingRoom getResponseWaitingRoom()
    {
	return responseWaitingRoom;
    }

    // REVISIT - inteface defines isServer but already defined in 
    // higher interface.

    public void serverRequestMapPut(int reqId, CorbaMessageMediator messageMediator)
    {
	serverRequestMap.put(reqId, messageMediator);
    }

    public CorbaMessageMediator serverRequestMapGet(int reqId)
    {
	return (CorbaMessageMediator)
	    serverRequestMap.get(reqId);
    }

    public void serverRequestMapRemove(int reqId)
    {
	serverRequestMap.remove(reqId);
    }

    public Queue<CorbaMessageMediator> getFragmentList(CorbaRequestId corbaRequestId) {
        return fragmentMap.get(corbaRequestId);
    }
    
    public void removeFragmentList(CorbaRequestId corbaRequestId) {
        fragmentMap.remove(corbaRequestId);
    }

    // REVISIT: this is also defined in:
    // com.sun.corba.se.spi.legacy.connection.Connection
    public java.net.Socket getSocket()
    {
	return socket;
    }

    /** It is possible for a Close Connection to have been
     ** sent here, but we will not check for this. A "lazy"
     ** Exception will be thrown in the Worker thread after the
     ** incoming request has been processed even though the connection
     ** is closed before the request is processed. This is o.k because
     ** it is a boundary condition. To prevent it we would have to add
     ** more locks which would reduce performance in the normal case.
     **/
    public synchronized void serverRequestProcessingBegins()
    {
        serverRequestCount++;
    }

    public synchronized void serverRequestProcessingEnds()
    {
        serverRequestCount--;
    }

    //
    //
    //

    public int getNextRequestId()
    {
        return requestId.getAndIncrement();
    }

    // Negotiated code sets for char and wchar data
    protected CodeSetComponentInfo.CodeSetContext codeSetContext = null;

    public ORB getBroker() 
    {
        return orb;
    }

    public synchronized CodeSetComponentInfo.CodeSetContext getCodeSetContext() {
        return codeSetContext;
    }

    public synchronized void setCodeSetContext(CodeSetComponentInfo.CodeSetContext csc) {
        if (codeSetContext == null) {
            
            if (OSFCodeSetRegistry.lookupEntry(csc.getCharCodeSet()) == null ||
                OSFCodeSetRegistry.lookupEntry(csc.getWCharCodeSet()) == null) {
                // If the client says it's negotiated a code set that
                // isn't a fallback and we never said we support, then
                // it has a bug.
		throw wrapper.badCodesetsFromClient() ;
            }

            codeSetContext = csc;
        }
    }

    //
    // from iiop.IIOPConnection.java
    //

    // Map request ID to an InputObject.
    // This is so the client thread can start unmarshaling
    // the reply and remove it from the out_calls map while the
    // ReaderThread can still obtain the input stream to give
    // new fragments.  Only the ReaderThread touches the clientReplyMap,
    // so it doesn't incur synchronization overhead.

    public CorbaMessageMediator clientRequestMapGet(int requestId)
    {
	return responseWaitingRoom.getMessageMediator(requestId);
    }

    protected CorbaMessageMediator clientReply_1_1;

    public void clientReply_1_1_Put(CorbaMessageMediator x)
    {
	clientReply_1_1 = x;
    }

    public CorbaMessageMediator clientReply_1_1_Get()
    {
	return 	clientReply_1_1;
    }

    public void clientReply_1_1_Remove()
    {
	clientReply_1_1 = null;
    }

    protected CorbaMessageMediator serverRequest_1_1;

    public void serverRequest_1_1_Put(CorbaMessageMediator x)
    {
	serverRequest_1_1 = x;
    }

    public CorbaMessageMediator serverRequest_1_1_Get()
    {
	return 	serverRequest_1_1;
    }

    public void serverRequest_1_1_Remove()
    {
	serverRequest_1_1 = null;
    }

    protected String getStateString( int state ) 
    {
        synchronized ( stateEvent ){
            switch (state) {
            case OPENING : return "OPENING" ;
            case ESTABLISHED : return "ESTABLISHED" ;
            case CLOSE_SENT : return "CLOSE_SENT" ;
            case CLOSE_RECVD : return "CLOSE_RECVD" ;
            case ABORT : return "ABORT" ;
            default : return "???" ;
            }
        }
    }
    
    public synchronized boolean isPostInitialContexts() {
        return postInitialContexts;
    }

    // Can never be unset...
    public synchronized void setPostInitialContexts(){
        postInitialContexts = true;
    }
    
    /**
     * Wake up the outstanding requests on the connection, and hand them
     * COMM_FAILURE exception with a given minor code.
     *
     * Also, delete connection from connection table and
     * stop the reader thread.

     * Note that this should only ever be called by the Reader thread for
     * this connection.
     * 
     * @param minor_code The minor code for the COMM_FAILURE major code.
     * @param die Kill the reader thread (this thread) before exiting.
     */
    @Transport
    public void purgeCalls(SystemException systemException, boolean die,
        boolean lockHeld) {

	int minor_code = systemException.minor;
        // If this invocation is a result of ThreadDeath caused
        // by a previous execution of this routine, just exit.
        synchronized ( stateEvent ){
            localStateInfo(state);
            if ((state == ABORT) || (state == CLOSE_RECVD)) {
                return;
            }
        }

        // Grab the writeLock (freeze the calls)
        try {
            if (!lockHeld) {
                writeLock();
            }
        } catch (SystemException ex) {
            exceptionInfo(ex);
        }

        // Mark the state of the connection
        // and determine the request status
        org.omg.CORBA.CompletionStatus completion_status;
        synchronized ( stateEvent ){
            if (minor_code == ORBUtilSystemException.CONNECTION_REBIND) {
                state = CLOSE_RECVD;
                systemException.completed = CompletionStatus.COMPLETED_NO;
            } else {
                state = ABORT;
                systemException.completed = CompletionStatus.COMPLETED_MAYBE;
            }
            stateEvent.notifyAll();
        }

        closeSocketAndTemporarySelectors();

        // Notify waiters (server-side processing only)

        if (serverRequest_1_1 != null) { // GIOP 1.1
            ((CorbaMessageMediator)serverRequest_1_1).cancelRequest();
        }

        if (serverRequestMap != null) { // GIOP 1.2
            for (CorbaMessageMediator mm : serverRequestMap.values()) {
                mm.cancelRequest() ;
            }
        }

        // Signal all threads with outstanding requests on this
        // connection and give them the SystemException;

        responseWaitingRoom.signalExceptionToAllWaiters(systemException);

        if (contactInfo != null) {
            ((CorbaOutboundConnectionCache)connectionCache).remove(contactInfo);
        } else if (acceptor != null) {
            ((CorbaInboundConnectionCache)connectionCache).remove(this) ;
        }

        //
        // REVISIT: Stop the reader thread
        //

        // Signal all the waiters of the writeLock.
        // There are 4 types of writeLock waiters:
        // 1. Send waiters:
        // 2. SendReply waiters:
        // 3. cleanUp waiters:
        // 4. purge_call waiters:
        //

        writeUnlock();
    }

    /*************************************************************************
    * The following methods are for dealing with Connection cleaning for
    * better scalability of servers in high network load conditions.
    **************************************************************************/

    public void sendCloseConnection(GIOPVersion giopVersion)
	throws IOException 
    {
        Message msg = MessageBase.createCloseConnection(giopVersion);
	sendHelper(giopVersion, msg);
    }

    public void sendMessageError(GIOPVersion giopVersion)
	throws IOException 
    {
        Message msg = MessageBase.createMessageError(giopVersion);
	sendHelper(giopVersion, msg);
    }

    /**
     * Send a CancelRequest message. This does not lock the connection, so the
     * caller needs to ensure this method is called appropriately.
     * @exception IOException - could be due to abortive connection closure.
     */
    public void sendCancelRequest(GIOPVersion giopVersion, int requestId)
	throws IOException 
    {

        Message msg = MessageBase.createCancelRequest(giopVersion, requestId);
	sendHelper(giopVersion, msg);
    }

    protected void sendHelper(GIOPVersion giopVersion, Message msg)
	throws IOException
    {
	// REVISIT: See comments in CDROutputObject constructor.
        CDROutputObject outputObject = 
	    new CDROutputObject((ORB)orb, null, giopVersion, this, msg,
				ORBConstants.STREAM_FORMAT_VERSION_1);
        msg.write(outputObject);

	outputObject.writeTo(this);
    }

    // NOTE: This method can throw a connection rebind SystemException.
    public void sendCancelRequestWithLock(GIOPVersion giopVersion,
					  int requestId)
	throws IOException 
    {
	writeLock();
	try {
	    sendCancelRequest(giopVersion, requestId);
	} catch (IOException ioe) {
	    if (state == CLOSE_RECVD) {
		throw wrapper.connectionRebind(ioe);
	    } else {
		throw ioe;
	    }
	}finally {
	    writeUnlock();
	}
    }

    // Begin Code Base methods ---------------------------------------
    //
    // Set this connection's code base IOR.  The IOR comes from the
    // SendingContext.  This is an optional service context, but all
    // JavaSoft ORBs send it.
    //
    // The set and get methods don't need to be synchronized since the
    // first possible get would occur during reading a valuetype, and
    // that would be after the set.

    // Sets this connection's code base IOR.  This is done after
    // getting the IOR out of the SendingContext service context.
    // Our ORBs always send this, but it's optional in CORBA.

    public final void setCodeBaseIOR(IOR ior) {
        codeBaseServerIOR = ior;
    }

    public final IOR getCodeBaseIOR() {
        return codeBaseServerIOR;
    }

    // Get a CodeBase stub to use in unmarshaling.  The CachedCodeBase
    // won't connect to the remote codebase unless it's necessary.
    public final CodeBase getCodeBase() {
        return cachedCodeBase;
    }

    // End Code Base methods -----------------------------------------

    // set transport read / write thresholds
    protected void setTcpTimeouts(TcpTimeouts tcpTimeouts) {
	this.tcpTimeouts = tcpTimeouts;
    }

    @Transport
    protected void doOptimizedReadStrategy() {
        MessageParser messageParser;
        try {
            // get a new ByteBuffer from ByteBufferPool ?
            if (byteBuffer == null || !byteBuffer.hasRemaining()) {
                byteBuffer = 
                        orb.getByteBufferPool().getByteBuffer(
                                    orb.getORBData().getReadByteBufferSize());
            }

            // Create a MessageParser. A MessageParser will exist until read 
            // event handling is re-enabled on main SelectorThread.
            messageParser = new MessageParserImpl(orb);
            
            // start of a message must begin at byteBuffer's current position
            messageParser.setNextMessageStartPosition(byteBuffer.position());

            int bytesRead = 0;
            do {
                startingMessagePosition( 
                    messageParser.getNextMessageStartPosition() );
                bytesRead = nonBlockingRead();
                if (bytesRead > 0) {
                    //byteBuffer.flip();
                    byteBuffer.limit(byteBuffer.position())
                              .position(messageParser.getNextMessageStartPosition());
                    parseBytesAndDispatchMessages(messageParser);
                    if (messageParser.isExpectingMoreData()) {
                        // End of data in byteBuffer ?
                        if (byteBuffer.position() == byteBuffer.capacity()) {
                            byteBuffer = getNewBufferAndCopyOld(messageParser);
                        }
                    }
                }
            } while (nonBlockingReadWhileLoopConditionIsTrue(messageParser,bytesRead));

            // if expecting more data or using 'always enter blocking read'
            // strategy (the default), then go to a blocking read using
            // a temporary selector.
            if (orb.getORBData().alwaysEnterBlockingRead() || 
                messageParser.isExpectingMoreData()) {
                blockingRead(messageParser);
            }
  
            // Always ensure subsequent calls to this method has
            // byteBuffer.position() set to the location where
            // the next message should begin
            byteBuffer.position(messageParser.getNextMessageStartPosition());

            readEventHandlingDone( byteBuffer) ;
            // Conection is no longer expecting more data.
            // Re-enable read event handling on main selector
            resumeSelectOnMainSelector();

        } catch (ThreadDeath td) {
	    try {
		purgeCalls(wrapper.connectionAbort(td), false, false);
	    } catch (Throwable t) {
                exceptionInfo(t);
	    }
	    throw td;
        } catch (Throwable ex) {
	    if (ex instanceof SystemException) {
		SystemException se = (SystemException)ex;
	        if (se.minor == ORBUtilSystemException.CONNECTION_REBIND) {
	            unregisterForEventAndPurgeCalls(se);
		    throw se;
		} else {
	            try {
		        if (se instanceof INTERNAL) {
		            sendMessageError(GIOPVersion.DEFAULT_VERSION);
		        }
	            } catch (IOException e) {
                        exceptionInfo(e);
	            }
		}
	    }
	    unregisterForEventAndPurgeCalls(wrapper.connectionAbort(ex));

	    // REVISIT
	    //keepRunning = false;
	    // REVISIT - if this is called after purgeCalls then
	    // the state of the socket is ABORT so the writeLock
	    // in close throws an exception.  It is ignored but
	    // causes IBM (screen scraping) tests to fail.
	    //close();
            throw wrapper.throwableInDoOptimizedReadStrategy(ex);
        }
    }
    @Transport
    protected void blockingRead(MessageParser messageParser) {
        // Precondition: byteBuffer's position must be pointing to where next 
        //               bit of data should be read and MessageParser's next 
        //               message start position must be set.
        
	TcpTimeouts.Waiter waiter = tcpTimeouts.waiter() ;
        TemporarySelector tmpSelector = null;
        SelectionKey sk = null;
        try {
            getConnectionCache().stampTime(this);
            tmpSelector = getTemporaryReadSelector();
            sk = tmpSelector.registerChannel(getSocketChannel(), 
                                             SelectionKey.OP_READ);
            do {
                int nsel = tmpSelector.select(waiter.getTimeForSleep());
                if (nsel > 0) {
                    tmpSelector.removeSelectedKey(sk);
                    int bytesRead = getSocketChannel().read(byteBuffer);
                    readBytesFromChannel(bytesRead);
                    if (bytesRead > 0) {
                        //byteBuffer.flip();
                        byteBuffer.limit(byteBuffer.position())
                                  .position(messageParser.getNextMessageStartPosition());
                        parseBytesAndDispatchMessages(messageParser);
                        if (messageParser.isExpectingMoreData()) {
                            // End of data in byteBuffer ?
                            if (byteBuffer.position() == byteBuffer.capacity()) {
                                byteBuffer = getNewBufferAndCopyOld(messageParser);
                            }
                        }
                        // reset waiter because we got some data
			waiter = tcpTimeouts.waiter() ;
                    } else if (bytesRead < 0) {
                        throw wrapper.blockingReadEndOfStream( 
			    new IOException("End-of-Stream"), this.toString());
                    } else { // bytesRead == 0, unlikely but possible
			waiter.advance() ;
                    }
                } else { // select operation timed out
		    waiter.advance() ;
                }
            } while (blockingReadWhileLoopConditionIsTrue(messageParser, waiter));

            // If MessageParser is not expecting more data, then we leave this
            // blocking read. Otherwise, we have timed out waiting for some
            // expected data to arrive.
            if (messageParser.isExpectingMoreData()) {
                // failed to read data when we were expecting more
                // and exceeded time willing to wait for additional data
                throw wrapper.blockingReadTimeout(
		    Long.valueOf(tcpTimeouts.get_max_time_to_wait()), 
		    Long.valueOf(waiter.timeWaiting()));
            }
        } catch (IOException ioe) {
            throw wrapper.exceptionBlockingReadWithTemporarySelector( ioe, this ) ;
        } finally {
            if (tmpSelector != null) {
                try {
                    tmpSelector.cancelAndFlushSelector(sk);
                } catch (IOException ex) {
                    wrapper.unexpectedExceptionCancelAndFlushTempSelector(ex);
                }
            }
        }
    }

    @Transport
    protected void parseBytesAndDispatchMessages(MessageParser messageParser) {
        do {
            Message message = messageParser.parseBytes(byteBuffer, this);
            if (message != null) {
                ByteBuffer msgBuffer = message.getByteBuffer();
                CorbaMessageMediatorImpl messageMediator =
                     new CorbaMessageMediatorImpl(orb, this,message, msgBuffer);

                // Special handling of messages which are fragmented
                boolean addToWorkerThreadQueue = true;
                if (MessageBase.messageSupportsFragments(message)) {
                    // Is this the first fragment ?
                    if (message.getType() != message.GIOPFragment) {
                        // NOTE: First message fragment will not be GIOPFragment
                        // type
                        if (message.moreFragmentsToFollow()) {
                            // Create an entry in fragmentMap so fragments
                            // will be processed in order.
                            CorbaRequestId corbaRequestId =
                                 MessageBase.getRequestIdFromMessageBytes(message);
                            fragmentMap.put(corbaRequestId, new LinkedList<
                                                   CorbaMessageMediator>());
                            addedEntryToFragmentMap( corbaRequestId ) ;
                        }
                    } else {
                        // Not the first fragment. Append to the request id's
                        // queue in the fragmentMap so fragments will be
                        // processed in order.
                        CorbaRequestId corbaRequestId =
                             MessageBase.getRequestIdFromMessageBytes(message);
                        Queue queue = fragmentMap.get(corbaRequestId);
                        if (queue != null) {
                            // REVISIT - In the future, the synchronized(queue),
                            // wait()/notify() construct should be replaced
                            // with something like a LinkedBlockingQueue
                            // from java.util.concurrent using its offer()
                            // and poll() methods.  But, at the time of the
                            // writing of this code, a LinkedBlockingQueue
                            // implementation is not performing as well as
                            // the synchronized(queue), wait(), notify()
                            // implementation.
                            synchronized (queue) {
                                queue.add(messageMediator);
                                queuedMessageFragment( corbaRequestId ) ;
                                // Notify anyone who might be waiting on a
                                // fragment for this request id.
                                queue.notifyAll();
                            }
                            // Only after the previous fragment is processed
                            // in CorbaMessageMediatorImpl.handleInput() will
                            // the fragment Message that's been queued to
                            // the fragmentMap for a given request id be
                            // put on a WorkerThreadQueue for processing.
                            addToWorkerThreadQueue = false;
                        } else {
                            // Very, very unlikely. But, be defensive.
                            wrapper.noFragmentQueueForRequestId(
                                                 corbaRequestId.toString());
                        }
                    }
                }

                // avoid memory leak,
                // see CorbaContactInfoBase.createMessageMediator()
                message.setByteBuffer(null);

                if (addToWorkerThreadQueue) {
                    addMessageMediatorToWorkQueue(messageMediator);
                }
            }
        } while (messageParser.hasMoreBytesToParse());
    }

    @Transport
    protected int nonBlockingRead() {
        int bytesRead = 0;
        SocketChannel sc = getSocketChannel();
        try {
            if (sc == null || sc.isBlocking()) {
                throw wrapper.nonBlockingReadOnBlockingSocketChannel(this);
            }
            bytesRead = sc.read(byteBuffer);
            if (bytesRead < 0) {
                throw new IOException("End-of-stream");
            }
            readBytesFromChannel(bytesRead);
            getConnectionCache().stampTime(this);
        } catch (IOException ioe) {
            if (state == CLOSE_RECVD) {
                throw wrapper.connectionRebind(ioe);
            } else {
                throw wrapper.ioexceptionWhenReadingConnection( ioe, this ) ;
            }
        }
        
        return bytesRead;
    }

    private boolean blockingReadWhileLoopConditionIsTrue(
        MessageParser messageParser, TcpTimeouts.Waiter waiter ) {
        // When orb.getORBData().blockingReadCheckMessageParser() is
        // true, we check both conditions, messageParser.isExpectingMoreData() 
        // and timeSpentWaiting < maxWaitTime. This is *NOT* the default.
        // If the messageParser.isExpectingMoreData() condition is not checked
        // the while loop will not exit until we have reached a timeout waiting
        // for more data. This is *NOW* the default. This means that control 
        // will be returned to the main Selector at a later time.  This has the
        // effect of being more patient in determining when a Connection should 
        // transition from being a "hot" Connnection to one that is not very 
        // "busy" or has gone cold/idle.
        final boolean checkMessageParser = 
	    orb.getORBData().blockingReadCheckMessageParser();

        if (checkMessageParser) {
            return messageParser.isExpectingMoreData() && !waiter.isExpired() ;
        } else {
	    return !waiter.isExpired() ;
        }
    }

    private boolean nonBlockingReadWhileLoopConditionIsTrue(
                                   MessageParser messageParser, int bytesRead) {
        // When orb.getORBData().nonBlockingReadCheckMessageParser() is
        // true, we check both conditions, messageParser.isExpectingMoreData() and
        // bytesRead > 0.  If bytesRead > 0 is the only condition checked,
        // i.e. orb.getORBData().nonBlockingReadCheckMessageParser() is false,
        // then an additional read() would be done before exiting the while
        // loop. The default is to check both conditions.
        final boolean checkBothConditions = 
                    orb.getORBData().nonBlockingReadCheckMessageParser();
        if (checkBothConditions) {
            return (bytesRead > 0 && messageParser.isExpectingMoreData());
        } else {
            return bytesRead > 0;
        }
    }

    @Transport
    private ByteBuffer getNewBufferAndCopyOld(MessageParser messageParser) {
        ByteBuffer newByteBuffer = null;
        // Set byteBuffer position to the start position of data to be
        // copied into the re-allocated ByteBuffer.
        byteBuffer.position(messageParser.getNextMessageStartPosition());
        newByteBuffer = orb.getByteBufferPool().reAllocate(byteBuffer,
                                         messageParser.getSizeNeeded());
        messageParser.setNextMessageStartPosition(0);
        return newByteBuffer;
    }

    @Transport
    private void addMessageMediatorToWorkQueue(
                               final CorbaMessageMediatorImpl messageMediator) {
        // Add messageMediator to work queue
        Throwable throwable = null;
        try {
            int poolToUse = messageMediator.getThreadPoolToUse();
            orb.getThreadPoolManager().getThreadPool(poolToUse)
               .getWorkQueue(0).addWork(messageMediator);
        } catch (NoSuchThreadPoolException e) {
            throwable = e;
        } catch (NoSuchWorkQueueException e) {
            throwable = e;
        }
        // REVISIT: need to close connection?
        if (throwable != null) {
            throw wrapper.noSuchThreadpoolOrQueue(throwable);
        }
    }

    @Transport
    private void resumeSelectOnMainSelector()
    {
	// NOTE: VERY IMPORTANT:
	// Re-enable read event handling on main Selector after getting to 
        // the point that proper serialization of fragments is ensured.
        // parseBytesAndDispatchMessages() and MessageParserImpl.parseBytes()
        // ensures this by tracking fragment messages for a given request id
        // for GIOP 1.2 and tracking GIOP 1.1 fragment messages.

	// IMPORTANT: To avoid bug (4953599), we force the Thread that does the 
        // NIO select to also do the enable/disable of interest ops using 
        // SelectionKey.interestOps(Ops of Interest). Otherwise, the 
        // SelectionKey.interestOps(Ops of Interest) may block indefinitely in
	// this thread.
        orb.getTransportManager().getSelector(0).registerInterestOps(this);
    }

    @Transport
    protected TemporarySelector getTemporaryReadSelector() throws IOException {
        // If one asks for a temporary read selector on a blocking connection,
        // it is an error.
        if (getSocketChannel() == null || getSocketChannel().isBlocking()) {
            throw wrapper.temporaryReadSelectorWithBlockingConnection(this);
        }
        synchronized (tmpReadSelectorLock) {
            if (tmpReadSelector == null) {
                tmpReadSelector = new TemporarySelector(this.orb, getSocketChannel());
            }
        }
        return tmpReadSelector;
    }

    @Transport
    protected TemporarySelector getTemporaryWriteSelector() throws IOException {
        // If one asks for a temporary write selector on a blocking connection,
        // it is an error.
        if (getSocketChannel() == null || getSocketChannel().isBlocking()) {
            throw wrapper.temporaryWriteSelectorWithBlockingConnection(this);
        }
        synchronized (tmpWriteSelectorLock) {
            if (tmpWriteSelector == null) {
                tmpWriteSelector = new TemporarySelector(this.orb, getSocketChannel());
            }
        }
        return tmpWriteSelector;
    }

    @Transport
    protected void closeTemporarySelectors() throws IOException {
        synchronized (tmpReadSelectorLock) {
            if (tmpReadSelector != null) {
                closingReadSelector( tmpReadSelector ) ;
                try {
                    tmpReadSelector.close();
                } catch (IOException ex) {
                    throw ex;
                }
            }
        }
        
        synchronized (tmpWriteSelectorLock) {
            if (tmpWriteSelector != null) {
                closingWriteSelector( tmpWriteSelector ) ;
                try {
                    tmpWriteSelector.close();
                } catch (IOException ex) {
                    throw ex;
                }
            }
        }
    }

    @Override
    public String toString()
    {
        synchronized ( stateEvent ){
            return 
		"SocketOrChannelConnectionImpl[" + " "
		+ (socketChannel == null ?
		   socket.toString() : socketChannel.toString()) + " "
		+ getStateString( state ) + " "
		+ shouldUseSelectThreadToWait() + " "
		+ shouldUseWorkerThreadForEvent()
		+ "]" ;
        }
    }

    @InfoMethod
    private void exceptionInfo(Throwable t) { }

    @InfoMethod
    private void exceptionInfo(String string, Throwable t) { }

    @InfoMethod
    private void bbInfo(int bbAddress) { }

    @InfoMethod
    private void readBytesFromChannel(int bytecount) { }

    @InfoMethod
    private void doneWithTemporarySelector() { }

    @InfoMethod
    private void readFullySleeping(int time) { }

    @InfoMethod
    private void doNotCloseBusyConnection() { }

    @InfoMethod
    private void shuttingDownConnectionInputStream() { }

    @InfoMethod
    private void shuttingDownConnectionOutputStream() { }

    @InfoMethod
    private void localStateInfo(int localState) { }

    @InfoMethod
    private void startingMessagePosition(int nextMessageStartPosition) { }

    @InfoMethod
    private void readEventHandlingDone(ByteBuffer byteBuffer) { }

    @InfoMethod
    private void addedEntryToFragmentMap(CorbaRequestId corbaRequestId) { }

    @InfoMethod
    private void queuedMessageFragment(CorbaRequestId corbaRequestId) { }

    @InfoMethod
    private void closingReadSelector(TemporarySelector tmpReadSelector) { }

    @InfoMethod
    private void closingWriteSelector(TemporarySelector tmpWriteSelector) { }
}

// End of file.
