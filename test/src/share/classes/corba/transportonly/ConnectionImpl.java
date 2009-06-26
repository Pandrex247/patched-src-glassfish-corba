/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
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
package corba.transportonly;

import com.sun.corba.se.impl.encoding.CodeSetComponentInfo.CodeSetContext;
import com.sun.corba.se.spi.ior.IOR;
import com.sun.corba.se.spi.ior.iiop.GIOPVersion;
import com.sun.corba.se.spi.protocol.CorbaRequestId;
import com.sun.corba.se.spi.transport.CorbaResponseWaitingRoom;
import com.sun.org.omg.SendingContext.CodeBase;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import com.sun.corba.se.impl.encoding.CDRInputObject;
import com.sun.corba.se.impl.encoding.CDROutputObject;
import com.sun.corba.se.spi.protocol.CorbaMessageMediator;
import com.sun.corba.se.spi.transport.CorbaAcceptor;
import com.sun.corba.se.spi.transport.CorbaConnection;
import com.sun.corba.se.spi.transport.CorbaConnectionCache;
import com.sun.corba.se.spi.transport.CorbaContactInfo;
import com.sun.corba.se.spi.transport.EventHandler;

import com.sun.corba.se.spi.orb.ORB;
import com.sun.corba.se.spi.orbutil.threadpool.Work;

import com.sun.corba.se.impl.transport.EventHandlerBase;
import java.util.Queue;
import org.omg.CORBA.SystemException;

public class ConnectionImpl
    extends
	EventHandlerBase
    implements
	CorbaConnection,
	Work
{
    SocketChannel socketChannel;
    ByteBuffer buffer = ByteBuffer.allocateDirect(1024);

    public ConnectionImpl(ORB orb,
			  SocketChannel socketChannel,
			  boolean useSelectThreadToWait,
			  boolean isBlocking, 
			  boolean useWorkerThread)
	throws
	    IOException
    {
	this.orb = orb;
	setWork(this);
	this.socketChannel = socketChannel;
	socketChannel.configureBlocking(isBlocking);
	setUseSelectThreadToWait(useSelectThreadToWait);
	setUseWorkerThreadForEvent(useWorkerThread);
	buffer = ByteBuffer.allocateDirect(1024);
    }

    ////////////////////////////////////////////////////
    //
    // Connection methods.
    //

    public boolean read()
    {
	try {
	    int count;
	    buffer.clear();
	    if ((count = socketChannel.read(buffer)) > 0) {
		buffer.flip();
		while (buffer.hasRemaining()) {
		    socketChannel.write(buffer);
		}
		buffer.clear();
	    }

	    return (count < 0 ? true : false);

	} catch (IOException e) {
	    throw new RuntimeException(e.toString());
	}
    }

    public EventHandler getEventHandler()
    {
	return this;
    }

    ////////////////////////////////////////////////////
    //
    // EventHandler methods
    //

    public SelectableChannel getChannel()
    {
	return socketChannel;
    }

    public int getInterestOps()
    {
	return SelectionKey.OP_READ;
    }

    public CorbaAcceptor getAcceptor()
    {
	return null;
    }

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

    public void doWork()
    {
	System.out.println("----- Connection: BEGIN doEvent -----");
	if (! selectionKey.isReadable()) {
	    throw new RuntimeException("Expecting isReadable.");
	}
	if (read()) {
	    // REVISIT - right place for this?
	    try {
		socketChannel.close(); // invalidates key
	    } catch (IOException e) {
		throw new RuntimeException(e.toString());
	    }
	    System.out.println("----- Connection: closed -----");
	} else {
	    selectionKey.interestOps(selectionKey.interestOps() |
				     getInterestOps());
	}
	System.out.println("----- Connection: END doWork -----");
    }

    protected long enqueueTime;

    public void setEnqueueTime(long timeInMillis)
    {
	enqueueTime = timeInMillis;
    }

    public long getEnqueueTime()
    {
	return enqueueTime;
    }

    //////////////////////////////////////////////////
    //
    //
    //

    //
    // REVISIT - temp merge stuff
    //

    public boolean handleReadEvent()
	throws
	    IOException
    {
	throw new RuntimeException("NOT READY YET");
    }

    public CorbaContactInfo getContactInfo()
    {
	throw new RuntimeException("NOT READY YET");
    }
    
    public CDROutputObject createOutputObject(CorbaMessageMediator messageMediator)
    {
	throw new RuntimeException("NOT READY YET");
    }

    public boolean isBusy()
    {
	throw new RuntimeException("NOT READY YET");
    }

    public long getTimeStamp()
    {
	throw new RuntimeException("NOT READY YET");
    }

    public void setTimeStamp(long time)
    {
	throw new RuntimeException("NOT READY YET");
    }

    public void close()
    {
	throw new RuntimeException("NOT READY YET");
    }

    public boolean isClosed()
    {
	boolean result = true;
        if (socketChannel != null)
	{
	    result = ! socketChannel.isOpen();
	}
	return result;
    }

    public boolean isServer()
    {
	throw new RuntimeException("NOT READY YET");
    }

    public boolean hasInput(boolean waitForInput)
	throws 
	    IOException
    {
	throw new RuntimeException("NOT READY YET");
    }

    public boolean shouldRegisterReadEvent()
    {
	throw new RuntimeException("NOT READY YET");
    }
    public boolean shouldRegisterServerReadEvent()
    {
	throw new RuntimeException("NOT READY YET");
    }

    public void setState(String state)
    {
	throw new RuntimeException("NOT READY YET");
    }

    public void writeLock()
    {
	throw new RuntimeException("NOT READY YET");
    }
    public void writeUnlock()
    {
	throw new RuntimeException("NOT READY YET");
    }
    public void sendWithoutLock(CDROutputObject outputObject)
    {
	throw new RuntimeException("NOT READY YET");
    }

    public void registerWaiter(CorbaMessageMediator messageMediator)
    {
	throw new RuntimeException("NOT READY YET");
    }

    public void unregisterWaiter(CorbaMessageMediator messageMediator)
    {
	throw new RuntimeException("NOT READY YET");
    }

    public CDRInputObject waitForResponse(CorbaMessageMediator messageMediator)
    {
	throw new RuntimeException("NOT READY YET");
    }

    public void setConnectionCache(CorbaConnectionCache connectionCache)
    {
	throw new RuntimeException();
    }

    public CorbaConnectionCache getConnectionCache()
    {
	throw new RuntimeException();
    }

    public boolean shouldUseDirectByteBuffers() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public ByteBuffer read(int size, int offset, int length) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public ByteBuffer read(ByteBuffer byteBuffer, int offset, int length) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void write(ByteBuffer byteBuffer) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void dprint(String msg) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public int getNextRequestId() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public ORB getBroker() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CodeSetContext getCodeSetContext() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void setCodeSetContext(CodeSetContext csc) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CorbaMessageMediator clientRequestMapGet(int requestId) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void clientReply_1_1_Put(CorbaMessageMediator x) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CorbaMessageMediator clientReply_1_1_Get() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void clientReply_1_1_Remove() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void serverRequest_1_1_Put(CorbaMessageMediator x) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CorbaMessageMediator serverRequest_1_1_Get() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void serverRequest_1_1_Remove() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public boolean isPostInitialContexts() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void setPostInitialContexts() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void purgeCalls(SystemException systemException, boolean die, boolean lockHeld) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void setCodeBaseIOR(IOR ior) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public IOR getCodeBaseIOR() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CodeBase getCodeBase() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void sendCloseConnection(GIOPVersion giopVersion) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void sendMessageError(GIOPVersion giopVersion) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void sendCancelRequest(GIOPVersion giopVersion, int requestId) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void sendCancelRequestWithLock(GIOPVersion giopVersion, int requestId) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CorbaResponseWaitingRoom getResponseWaitingRoom() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void serverRequestMapPut(int requestId, CorbaMessageMediator messageMediator) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CorbaMessageMediator serverRequestMapGet(int requestId) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void serverRequestMapRemove(int requestId) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public Queue<CorbaMessageMediator> getFragmentList(CorbaRequestId corbaRequestId) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void removeFragmentList(CorbaRequestId corbaRequestId) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public SocketChannel getSocketChannel() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void serverRequestProcessingBegins() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void serverRequestProcessingEnds() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void closeConnectionResources() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public Socket getSocket() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}

// End of file.
