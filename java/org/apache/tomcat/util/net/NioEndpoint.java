/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 * <p>
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */

/**
 * socket 接收器和发送器 --> 用来处理TCP协议
 *
 *
 * NIO量身定制的线程池，并提供以下服务：
 * - Socket接收线程
 * - Socket轮询线程
 * - 工作线程池
 * 当切换到Java 5，有使用虚拟机的线程池的机会。
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel, SocketChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private volatile ServerSocketChannel serverSock = null;

    /**
     * Stop latch used to wait for poller stop
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        } catch (Exception e) {
            log.error(sm.getString("endpoint.setAttributeError", name, value), e);
            return false;
        }
    }


    /**
     * Use System.inheritableChannel to obtain channel from stdin/stdout.
     */
    private boolean useInheritedChannel = false;

    public void setUseInheritedChannel(boolean useInheritedChannel) {
        this.useInheritedChannel = useInheritedChannel;
    }

    public boolean getUseInheritedChannel() {
        return useInheritedChannel;
    }

    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;

    public void setPollerThreadPriority(int pollerThreadPriority) {
        this.pollerThreadPriority = pollerThreadPriority;
    }

    public int getPollerThreadPriority() {
        return pollerThreadPriority;
    }


    /**
     * NO-OP.
     *
     * @param pollerThreadCount Unused
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public void setPollerThreadCount(int pollerThreadCount) {
    }

    /**
     * Always returns 1.
     *
     * @return Always 1.
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public int getPollerThreadCount() {
        return 1;
    }

    private long selectorTimeout = 10000;

    public void setSelectorTimeout(long timeout) {
        this.selectorTimeout = timeout;
    }

    public long getSelectorTimeout() {
        return this.selectorTimeout;
    }

    /**
     * The socket poller.
     */
    private Poller poller = null;


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     *         for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (poller == null) {
            return 0;
        } else {
            return poller.getKeyCount();
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     */
    @Override
    public void bind() throws Exception {
        initServerSocket();

        setStopLatch(new CountDownLatch(1));

        // Initialize SSL if needed
        initialiseSsl();

        selectorPool.open(getName());
    }

    // Separated out to make it easier for folks that extend NioEndpoint to
    // implement custom [server]sockets
    protected void initServerSocket() throws Exception {
        if (!getUseInheritedChannel()) {
            //底层本质是申请一个fd
            serverSock = ServerSocketChannel.open();
            //serverSock.socket() --> 本质是创建一个对象,内部包含ServerSocketChannel
            //为其配置属性
            socketProperties.setProperties(serverSock.socket());
            //地址、端口
            InetSocketAddress addr = new InetSocketAddress(getAddress(), getPortWithOffset());
            //fd绑定地址、端口.
            serverSock.socket().bind(addr, getAcceptCount());
        } else {
            // Retrieve the channel provided by the OS
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        }
        serverSock.configureBlocking(true); //mimic APR behavior
    }


    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     */
    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;

            if (socketProperties.getProcessorCache() != 0) {
                processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                        socketProperties.getProcessorCache());
            }
            if (socketProperties.getEventCache() != 0) {
                eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                        socketProperties.getEventCache());
            }
            if (socketProperties.getBufferPool() != 0) {
                nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                        socketProperties.getBufferPool());
            }

            // Create worker collection
            if (getExecutor() == null) {
                createExecutor();
            }

            initializeConnectionLatch();

            // Start poller thread
            poller = new Poller();
            Thread pollerThread = new Thread(poller, getName() + "-ClientPoller");
            pollerThread.setPriority(threadPriority);
            pollerThread.setDaemon(true);
            pollerThread.start();

            startAcceptorThread();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            if (poller != null) {
                poller.destroy();
                poller = null;
            }
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor();
            if (eventCache != null) {
                eventCache.clear();
                eventCache = null;
            }
            if (nioChannels != null) {
                nioChannels.clear();
                nioChannels = null;
            }
            if (processorCache != null) {
                processorCache.clear();
                processorCache = null;
            }
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for " +
                    new InetSocketAddress(getAddress(), getPortWithOffset()));
        }
        if (running) {
            stop();
        }
        try {
            doCloseServerSocket();
        } catch (IOException ioe) {
            getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
        }
        destroySsl();
        super.unbind();
        if (getHandler() != null) {
            getHandler().recycle();
        }
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for " +
                    new InetSocketAddress(getAddress(), getPortWithOffset()));
        }
    }


    @Override
    protected void doCloseServerSocket() throws IOException {
        if (!getUseInheritedChannel() && serverSock != null) {
            // Close server socket
            serverSock.close();
        }
        serverSock = null;
    }

    // ------------------------------------------------------ Protected Methods

    protected NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    protected SynchronizedStack<NioChannel> getNioChannels() {
        return nioChannels;
    }


    protected Poller getPoller() {
        return poller;
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     * Process the specified connection.
     * @param socket The socket channel  -->和服务端建立连接的客户端channel对象
     * @return <code>true</code> if the socket was correctly configured
     *  and processing may continue, <code>false</code> if the socket needs to be
     *  close immediately
     */
    @Override
    protected boolean setSocketOptions(SocketChannel socket) {
        NioSocketWrapper socketWrapper = null;
        try {
            // Allocate channel and wrapper
            //从NioChannel栈中出栈一个，若能重用（即不为null）则重用对象，否则新建一个NioChannel对象
            NioChannel channel = null;
            if (nioChannels != null) {
                // 先尝试复用以前close掉的channel
                channel = nioChannels.pop();
            }
            if (channel == null) {
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),  // 默认 8192
                        socketProperties.getAppWriteBufSize(),  // 默认 8192
                        socketProperties.getDirectBuffer());  // 默认 false
                if (isSSLEnabled()) {  // 开启了 SSL
                    channel = new SecureNioChannel(bufhandler, selectorPool, this);
                } else {
                    channel = new NioChannel(bufhandler);
                }
            }
            // 将底层的socket，包装成上层可以用的SocketWrapper -->socket相关信息放入了NioChannel中
            NioSocketWrapper newWrapper = new NioSocketWrapper(channel, this);
            // 重置该NioChannel的底层socket、包装的SocketWrapper
            channel.reset(socket, newWrapper);
            // 记录持有的连接，以及底层socket和socketWrapper的映射关系
            connections.put(socket, newWrapper);
            socketWrapper = newWrapper;

            // Set socket properties
            // Disable blocking, polling will be used
            socket.configureBlocking(false);
            // 配置SocketChannel对应的Socket对象
            socketProperties.setProperties(socket.socket());

            socketWrapper.setReadTimeout(getConnectionTimeout());
            socketWrapper.setWriteTimeout(getConnectionTimeout());
            socketWrapper.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            socketWrapper.setSecure(isSSLEnabled());
            // 将channel注册到poller
            poller.register(channel, socketWrapper);
            System.out.println("注册======"+channel);
            return true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error(sm.getString("endpoint.socketOptionsError"), t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            if (socketWrapper == null) {
                destroySocket(socket);
            }
        }
        // Tell to close the socket if needed
        return false;
    }


    @Override
    protected void destroySocket(SocketChannel socket) {
        countDownConnection();
        try {
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.err.close"), ioe);
            }
        }
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    @Override
    protected SocketChannel serverSocketAccept() throws Exception {
        // JDK的方法了
        return serverSock.accept();
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent {

        private NioChannel socket;
        private int interestOps;

        public PollerEvent(NioChannel ch, int intOps) {
            reset(ch, intOps);
        }

        public void reset(NioChannel ch, int intOps) {
            socket = ch;
            interestOps = intOps;
        }

        public NioChannel getSocket() {
            return socket;
        }

        public int getInterestOps() {
            return interestOps;
        }

        public void reset() {
            reset(null, 0);
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socket.getSocketWrapper() +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller class.
     */
    // Acceptor的小弟，处理来自Acceptor的（任务事件or连接？）
    public class Poller implements Runnable {

        private Selector selector;
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();

        // Poller有没有被销毁
        private volatile boolean close = false;
        // Optimize expiration handling
        private long nextExpiration = 0;

        private AtomicLong wakeupCounter = new AtomicLong(0);

        private volatile int keyCount = 0;

        public Poller() throws IOException {
            this.selector = Selector.open();
        }

        public int getKeyCount() {
            return keyCount;
        }

        public Selector getSelector() {
            return selector;
        }

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        private void addEvent(PollerEvent event) {
            //入队列
            events.offer(event);
            //wakeupCounter++
            if (wakeupCounter.incrementAndGet() == 0) {
                // wakeupCounter 原值为-1
                selector.wakeup();
            }
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socketWrapper to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(NioSocketWrapper socketWrapper, int interestOps) {
            PollerEvent r = null;
            if (eventCache != null) {
                r = eventCache.pop();
            }
            if (r == null) {
                r = new PollerEvent(socketWrapper.getSocket(), interestOps);
            } else {
                r.reset(socketWrapper.getSocket(), interestOps);
            }
            addEvent(r);
            if (close) {
                processSocket(socketWrapper, SocketEvent.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        /**
         * 处理轮询器的事件队列中的事件。
         *
         * @return 如果有事件被处理了，返回false。如果队列是空的，返回false
         */
        public boolean events() {
            boolean result = false;

            PollerEvent pe = null;
            //队列中取event
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++) {
                result = true;
                NioChannel channel = pe.getSocket();
                NioSocketWrapper socketWrapper = channel.getSocketWrapper();
                int interestOps = pe.getInterestOps();
                //注册事件
                if (interestOps == OP_REGISTER) {
                    try {
                        //该客户端channel连接到服务端,注册其感兴趣的事件OP_READ(有可读数据,及客户端发送了数据,则触发该事件)
                        channel.getIOChannel().register(getSelector(), SelectionKey.OP_READ, socketWrapper);
                    } catch (Exception x) {
                        log.error(sm.getString("endpoint.nio.registerFail"), x);
                    }
                } else {
                    //搜索隶属于当前通道的所有"选择键"，查看当前通道是否在指定的selector上已经注册过
                    final SelectionKey key = channel.getIOChannel().keyFor(getSelector());
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        socketWrapper.close();
                    } else {
                        final NioSocketWrapper attachment = (NioSocketWrapper) key.attachment();
                        if (attachment != null) {
                            // We are registering the key to start with, reset the fairness counter.
                            try {
                                int ops = key.interestOps() | interestOps;
                                attachment.interestOps(ops);
                                key.interestOps(ops);
                            } catch (CancelledKeyException ckx) {
                                cancelledKey(key, socketWrapper);
                            }
                        } else {
                            cancelledKey(key, attachment);
                        }
                    }
                }
                if (running && !paused && eventCache != null) {
                    //重置为空
                    pe.reset();
                    //放入栈
                    eventCache.push(pe);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket    The newly created socket
         * @param socketWrapper The socket wrapper
         */
        public void register(final NioChannel socket, final NioSocketWrapper socketWrapper) {
            // 设置该客户端 socket 的感兴趣事件为 OP_READ
            socketWrapper.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            PollerEvent event = null;
            if (eventCache != null) {
                // 重试复用之前失效的 PollerEvent
                event = eventCache.pop();
            }
            if (event == null) {
                // 没有则 new 一个
                event = new PollerEvent(socket, OP_REGISTER);
            } else {
                // 有则通过重置指定参数达到复用的目的
                event.reset(socket, OP_REGISTER);
            }
            addEvent(event);
        }

        public void cancelledKey(SelectionKey sk, SocketWrapperBase<NioChannel> socketWrapper) {
            try {
                // If is important to cancel the key first, otherwise a deadlock may occur between the
                // poller select and the socket channel close which would cancel the key
                if (sk != null) {
                    sk.attach(null);
                    if (sk.isValid()) {
                        sk.cancel();
                    }
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
                }
            } finally {
                if (socketWrapper != null) {
                    socketWrapper.close();
                }
            }
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         */
        // 后台线程，增加了 sockets 的轮询，检查轮询的触发事件和手相关的插座断为事件发生时一个合适的处理器。
        @Override
        public void run() {
            // Loop until destroy() is called  （轮询直到destroy()方法被调用）
            while (true) {

                boolean hasEvents = false;

                try {
                    if (!close) {
                        //如果有事件,会注册到selector上
                        hasEvents = events();
                        //原子方式设置为给定值，并返回以前的值
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            // If we are here, means we have other stuff to do  （如果我们在这里，意味着我们还有其他事情要做）
                            // Do a non blocking select                         （做一个非阻塞的select）
                            keyCount = selector.selectNow();
                        } else {
                            //selector上注册的SelectKey,触发对应的事件后,会立刻被唤醒,否则阻塞selectorTimeout后向后执行
                            keyCount = selector.select(selectorTimeout);
                        }
                        wakeupCounter.set(0);
                    }
                    if (close) {
                        events();
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error(sm.getString("endpoint.nio.selectorLoopError"), x);
                    continue;
                }
                // Either we timed out or we woke up, process events first  （我们要么超时，要么醒来，首先处理事件）
                if (keyCount == 0) {
                    hasEvents = (hasEvents | events());
                }

                //遍历就绪的SelectKey
                Iterator<SelectionKey> iterator =
                        keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch any active event.  （遍历ready keys的集合并分发任何active事件）
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    System.out.println("====channel====="+sk.channel()+"===attchment==="+sk.attachment()+"====opt====="+sk.interestOps());
                    NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
                    // Attachment may be null if another thread has called cancelledKey()
                    if (socketWrapper == null) {
                        iterator.remove();
                    } else {
                        //移除该SelectKey
                        iterator.remove();
                        processKey(sk, socketWrapper);
                    }
                }

                // Process timeouts
                timeout(keyCount, hasEvents);
            }

            getStopLatch().countDown();
        }

        protected void processKey(SelectionKey sk, NioSocketWrapper socketWrapper) {
            try {
                if (close) {
                    cancelledKey(sk, socketWrapper);
                } else if (sk.isValid() && socketWrapper != null) {
                    if (sk.isReadable() || sk.isWritable()) {  // 必须是可读或可写的
                        if (socketWrapper.getSendfileData() != null) {
                            processSendfile(sk, socketWrapper, false);
                        } else {
                            unreg(sk, socketWrapper, sk.readyOps());
                            boolean closeSocket = false;
                            // Read goes before write  （先读再写）
                            if (sk.isReadable()) {  // 读
                                if (socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        closeSocket = true;
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {  // 进这里
                                    closeSocket = true;
                                }
                            }
                            if (!closeSocket && sk.isWritable()) {  // 写
                                if (socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        closeSocket = true;
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (closeSocket) {
                                cancelledKey(sk, socketWrapper);
                            }
                        }
                    }
                } else {
                    // Invalid key
                    cancelledKey(sk, socketWrapper);
                }
            } catch (CancelledKeyException ckx) {
                cancelledKey(sk, socketWrapper);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("endpoint.nio.keyProcessingError"), t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                                             boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                            FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining() > 0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException(sm.getString("endpoint.sendfile.tooMuchData"));
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining() <= 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: " + sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                            case NONE: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Send file connection is being closed");
                                }
                                poller.cancelledKey(sk, socketWrapper);
                                break;
                            }
                            case PIPELINED: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Connection is keep alive, processing pipe-lined data");
                                }
                                if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                    poller.cancelledKey(sk, socketWrapper);
                                }
                                break;
                            }
                            case OPEN: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Connection is keep alive, registering back for OP_READ");
                                }
                                reg(sk, socketWrapper, SelectionKey.OP_READ);
                                break;
                            }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper, SelectionKey.OP_WRITE);
                    } else {
                        reg(sk, socketWrapper, SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Unable to complete sendfile request:", e);
                }
                if (!calledByProcessor && sc != null) {
                    poller.cancelledKey(sk, socketWrapper);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.sendfile.error"), t);
                if (!calledByProcessor && sc != null) {
                    poller.cancelledKey(sk, socketWrapper);
                }
                return SendfileState.ERROR;
            }
        }

        protected void unreg(SelectionKey sk, NioSocketWrapper socketWrapper, int readyOps) {
            // This is a must, so that we don't have multiple threads messing with the socket
            reg(sk, socketWrapper, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper socketWrapper, int intops) {
            sk.interestOps(intops);
            socketWrapper.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            int keycount = 0;
            try {
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper == null) {
                            // We don't support any keys without attachments
                            cancelledKey(key, null);
                        } else if (close) {
                            key.interestOps(0);
                            // Avoid duplicate stop calls
                            socketWrapper.interestOps(0);
                            cancelledKey(key, socketWrapper);
                        } else if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean readTimeout = false;
                            boolean writeTimeout = false;
                            // Check for read timeout
                            if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - socketWrapper.getLastRead();
                                long timeout = socketWrapper.getReadTimeout();
                                if (timeout > 0 && delta > timeout) {
                                    readTimeout = true;
                                }
                            }
                            // Check for write timeout
                            if (!readTimeout && (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - socketWrapper.getLastWrite();
                                long timeout = socketWrapper.getWriteTimeout();
                                if (timeout > 0 && delta > timeout) {
                                    writeTimeout = true;
                                }
                            }
                            if (readTimeout || writeTimeout) {
                                key.interestOps(0);
                                // Avoid duplicate timeout calls
                                socketWrapper.interestOps(0);
                                socketWrapper.setError(new SocketTimeoutException());
                                if (readTimeout && socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        cancelledKey(key, socketWrapper);
                                    }
                                } else if (writeTimeout && socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        cancelledKey(key, socketWrapper);
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.ERROR, true)) {
                                    cancelledKey(key, socketWrapper);
                                }
                            }
                        }
                    } catch (CancelledKeyException ckx) {
                        cancelledKey(key, (NioSocketWrapper) key.attachment());
                    }
                }
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            // For logging purposes only
            long prevExp = nextExpiration;
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));
            }

        }
    }

    // --------------------------------------------------- Socket Wrapper Class

    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;
        private final SynchronizedStack<NioChannel> nioChannels;
        private final Poller poller;

        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            pool = endpoint.getSelectorPool();
            nioChannels = endpoint.getNioChannels();
            poller = endpoint.getPoller();
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() {
            return poller;
        }

        public int interestOps() {
            return interestOps;
        }

        public int interestOps(int ops) {
            this.interestOps = ops;
            return ops;
        }

        public CountDownLatch getReadLatch() {
            return readLatch;
        }

        public CountDownLatch getWriteLatch() {
            return writeLatch;
        }

        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if (latch == null || latch.getCount() == 0) {
                return null;
            } else {
                throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
            }
        }

        public void resetReadLatch() {
            readLatch = resetLatch(readLatch);
        }

        public void resetWriteLatch() {
            writeLatch = resetLatch(writeLatch);
        }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if (latch == null || latch.getCount() == 0) {
                return new CountDownLatch(cnt);
            } else {
                throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
            }
        }

        public void startReadLatch(int cnt) {
            readLatch = startLatch(readLatch, cnt);
        }

        public void startWriteLatch(int cnt) {
            writeLatch = startLatch(writeLatch, cnt);
        }

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if (latch == null) {
                throw new IllegalStateException(sm.getString("endpoint.nio.nullLatch"));
            }
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout, unit);
        }

        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(readLatch, timeout, unit);
        }

        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(writeLatch, timeout, unit);
        }

        public void setSendfileData(SendfileData sf) {
            this.sendfileData = sf;
        }

        public SendfileData getSendfileData() {
            return this.sendfileData;
        }

        public void updateLastWrite() {
            lastWrite = System.currentTimeMillis();
        }

        public long getLastWrite() {
            return lastWrite;
        }

        public void updateLastRead() {
            lastRead = System.currentTimeMillis();
        }

        public long getLastRead() {
            return lastRead;
        }

        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.
            nRead = fillReadBuffer(block);
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }


        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            // 将socket中的buffer，填充到to中。
            // nRead表示成功填充的字节数
            int nRead = populateReadBuffer(to);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
                /**
                 * 由于自从上次填充缓冲区以来可能已经到达了更多的字节，因此此时可以选择执行非阻塞读取。
                 * 但是，如果读取返回流的末尾，则正确处理这种情况会增加复杂性。因此，目前，偏向于简化。
                 */
            }

            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            if (to.remaining() >= limit) {
                to.limit(to.position() + limit);
                nRead = fillReadBuffer(block, to);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
                }
                updateLastRead();
            } else {
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
                }
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        protected void doClose() {
            if (log.isDebugEnabled()) {
                log.debug("Calling [" + getEndpoint() + "].closeSocket([" + this + "])");
            }
            try {
                getEndpoint().connections.remove(getSocket().getIOChannel());
                if (getSocket().isOpen()) {
                    getSocket().close(true);
                }
                if (getEndpoint().running && !getEndpoint().paused) {
                    if (nioChannels == null || !nioChannels.push(getSocket())) {
                        getSocket().free();
                    }
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
                }
            } finally {
                socketBufferHandler = SocketBufferHandler.EMPTY;
                nonBlockingWriteBuffer.clear();
                reset(NioChannel.CLOSED_NIO_CHANNEL);
            }
            try {
                SendfileData data = getSendfileData();
                if (data != null && data.fchannel != null && data.fchannel.isOpen()) {
                    data.fchannel.close();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.sendfile.closeError"), e);
                }
            }
        }

        private int fillReadBuffer(boolean block) throws IOException {
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }


        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            NioChannel socket = getSocket();
            if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
                throw new ClosedChannelException();
            }
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    nRead = pool.read(to, socket, selector, getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                nRead = socket.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }


        @Override
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            NioChannel socket = getSocket();
            if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
                throw new ClosedChannelException();
            }
            if (block) {
                long writeTimeout = getWriteTimeout();
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    pool.write(from, socket, selector, writeTimeout);
                    // Make sure we are flushed
                    do {
                    } while (!socket.flush(true, selector, writeTimeout));
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
                // If there is data left in the buffer the socket will be registered for
                // write further up the stack. This is to ensure the socket is only
                // registered for write once as both container and user code can trigger
                // write registration.
            } else {
                int n = 0;
                do {
                    n = socket.write(from);
                    if (n == -1) {
                        throw new EOFException();
                    }
                } while (n > 0 && from.hasRemaining());
            }
            updateLastWrite();
        }


        @Override
        public void registerReadInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerRead", this));
            }
            // 注册一个读兴趣事件
            getPoller().add(this, SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerWrite", this));
            }
            getPoller().add(this, SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(getPoller().getSelector());
            // Might as well do the first write on this thread
            return getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemoteHost() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteHost = inetAddr.getHostName();
                    if (remoteAddr == null) {
                        remoteAddr = inetAddr.getHostAddress();
                    }
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                remotePort = sc.socket().getPort();
            }
        }


        @Override
        protected void populateLocalName() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localName = inetAddr.getHostName();
                }
            }
        }


        @Override
        protected void populateLocalAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateLocalPort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                localPort = sc.socket().getLocalPort();
            }
        }


        /**
         * {@inheritDoc}
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLEngine sslEngine = ch.getSslEngine();
                if (sslEngine != null) {
                    SSLSession session = sslEngine.getSession();
                    return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
                }
            }
            return null;
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }


        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }

        @Override
        protected <A> OperationState<A> newOperationState(boolean read,
                                                          ByteBuffer[] buffers, int offset, int length,
                                                          BlockingMode block, long timeout, TimeUnit unit, A attachment,
                                                          CompletionCheck check, CompletionHandler<Long, ? super A> handler,
                                                          Semaphore semaphore, VectoredIOCompletionHandler<A> completion) {
            return new NioOperationState<>(read, buffers, offset, length, block,
                    timeout, unit, attachment, check, handler, semaphore, completion);
        }

        private class NioOperationState<A> extends OperationState<A> {
            private volatile boolean inline = true;

            private NioOperationState(boolean read, ByteBuffer[] buffers, int offset, int length,
                                      BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
                                      CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
                                      VectoredIOCompletionHandler<A> completion) {
                super(read, buffers, offset, length, block,
                        timeout, unit, attachment, check, handler, semaphore, completion);
            }

            @Override
            protected boolean isInline() {
                return inline;
            }

            @Override
            public void run() {
                // Perform the IO operation
                // Called from the poller to continue the IO operation
                long nBytes = 0;
                if (getError() == null) {
                    try {
                        synchronized (this) {
                            if (!completionDone) {
                                // This filters out same notification until processing
                                // of the current one is done
                                if (log.isDebugEnabled()) {
                                    log.debug("Skip concurrent " + (read ? "read" : "write") + " notification");
                                }
                                return;
                            }
                            if (read) {
                                // Read from main buffer first
                                if (!socketBufferHandler.isReadBufferEmpty()) {
                                    // There is still data inside the main read buffer, it needs to be read first
                                    socketBufferHandler.configureReadBufferForRead();
                                    for (int i = 0; i < length && !socketBufferHandler.isReadBufferEmpty(); i++) {
                                        nBytes += transfer(socketBufferHandler.getReadBuffer(), buffers[offset + i]);
                                    }
                                }
                                if (nBytes == 0) {
                                    nBytes = getSocket().read(buffers, offset, length);
                                    updateLastRead();
                                }
                            } else {
                                boolean doWrite = true;
                                // Write from main buffer first
                                if (!socketBufferHandler.isWriteBufferEmpty()) {
                                    // There is still data inside the main write buffer, it needs to be written first
                                    socketBufferHandler.configureWriteBufferForRead();
                                    do {
                                        nBytes = getSocket().write(socketBufferHandler.getWriteBuffer());
                                    } while (!socketBufferHandler.isWriteBufferEmpty() && nBytes > 0);
                                    if (!socketBufferHandler.isWriteBufferEmpty()) {
                                        doWrite = false;
                                    }
                                    // Preserve a negative value since it is an error
                                    if (nBytes > 0) {
                                        nBytes = 0;
                                    }
                                }
                                if (doWrite) {
                                    long n = 0;
                                    do {
                                        n = getSocket().write(buffers, offset, length);
                                        if (n == -1) {
                                            nBytes = n;
                                        } else {
                                            nBytes += n;
                                        }
                                    } while (n > 0);
                                    updateLastWrite();
                                }
                            }
                            if (nBytes != 0 || !buffersArrayHasRemaining(buffers, offset, length)) {
                                completionDone = false;
                            }
                        }
                    } catch (IOException e) {
                        setError(e);
                    }
                }
                if (nBytes > 0 || (nBytes == 0 && !buffersArrayHasRemaining(buffers, offset, length))) {
                    // The bytes processed are only updated in the completion handler
                    completion.completed(Long.valueOf(nBytes), this);
                } else if (nBytes < 0 || getError() != null) {
                    IOException error = getError();
                    if (error == null) {
                        error = new EOFException();
                    }
                    completion.failed(error, this);
                } else {
                    // As soon as the operation uses the poller, it is no longer inline
                    inline = false;
                    if (read) {
                        registerReadInterest();
                    } else {
                        registerWriteInterest();
                    }
                }
            }

        }

    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    // 就是一个处理Socket的Task
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {

        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        @Override
        protected void doRun() {
            NioChannel socket = socketWrapper.getSocket();
            Poller poller = NioEndpoint.this.poller;
            if (poller == null) {
                socketWrapper.close();
                return;
            }

            try {
                // 0表示完成 SSL 握手了
                int handshake = -1;
                try {
                    if (socket.isHandshakeComplete()) {
                        // No TLS handshaking required. Let the handler process this socket / event combination.
                        handshake = 0;
                    } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                            event == SocketEvent.ERROR) {
                        // Unable to complete the TLS handshake. Treat it as
                        // if the handshake failed.
                        handshake = -1;
                    } else {
                        // 如果是非SSL的，进入 NioEndpoint，什么都没做
                        // 如果是 SSL 的，进入 SecureNioChannel，进行 SSL 握手
                        handshake = socket.handshake(event == SocketEvent.OPEN_READ, event == SocketEvent.OPEN_WRITE);
                        // The handshake process reads/writes from/to the
                        // socket. status may therefore be OPEN_WRITE once
                        // the handshake completes. However, the handshake
                        // happens when the socket is opened so the status
                        // must always be OPEN_READ after it completes. It
                        // is OK to always set this as it is only used if
                        // the handshake completes.
                        event = SocketEvent.OPEN_READ;
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake", x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                if (handshake == 0) {
                    // 表示握手完成了
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket  （处理来自此socket的请求）
                    /**
                     * 处理请求
                     */
                    if (event == null) {
                        // 事件为：数据可读了
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        state = getHandler().process(socketWrapper, event);
                    }
                    System.out.println("執行的線程:"+Thread.currentThread().getName()+";socket對象是:"+socketWrapper+";執行的事件:"+event+";執行的結果+"+state);
                    if (state == SocketState.CLOSED) {
                        poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
                    }
                } else if (handshake == -1) {
                    getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
                    poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
                } else if (handshake == SelectionKey.OP_READ) {
                    // 握手还没有完成，需要读更多的数据。
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE) {
                    // 握手还没有完成，需要写数据。
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.processing.fail"), t);
                poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
            } finally {
                socketWrapper = null;
                event = null;
                //return to cache
                if (running && !paused && processorCache != null) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class

    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
