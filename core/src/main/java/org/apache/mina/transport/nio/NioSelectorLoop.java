/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.mina.transport.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.mina.api.RuntimeIoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class NioSelectorLoop implements SelectorLoop {

    private final Logger logger;

    /** the selector managed by this class */
    private Selector selector;

    /** the worker thread in charge of polling the selector */
    private final SelectorWorker worker;

    /** Read buffer for all the incoming bytes (default to 64Kb) */
    private final ByteBuffer readBuffer = ByteBuffer.allocate(64 * 1024);

    private final Queue<Registration> registrationQueue = new ConcurrentLinkedQueue<Registration>();

    public NioSelectorLoop(final String prefix, final int index) {
        logger = LoggerFactory.getLogger(NioSelectorLoop.class.getName() + ":" + prefix + "-" + index);
        worker = new SelectorWorker(prefix, index);

        try {
            logger.debug("open a selector");
            selector = Selector.open();
        } catch (final IOException ioe) {
            logger.error("Impossible to open a new NIO selector, O/S is out of file descriptor ?");
            throw new RuntimeIoException(ioe);
        }
        logger.debug("starting worker thread");
        worker.start();

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(final boolean accept, final boolean read, final boolean write,
            final SelectorListener listener, final SelectableChannel channel) {
        logger.debug("registering : {} for accept : {}, read : {}, write : {}", new Object[] { listener, accept, read,
                                write });
        int ops = 0;
        if (accept) {
            ops |= SelectionKey.OP_ACCEPT;
        }
        if (read) {
            ops |= SelectionKey.OP_READ;
        }
        if (write) {
            ops |= SelectionKey.OP_WRITE;
        }

        // TODO : if it's the same selector/worker, we don't need to do that we could directly enqueue
        registrationQueue.add(new Registration(ops, channel, listener));
        selector.wakeup();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modifyRegistration(final boolean accept, final boolean read, final boolean write,
            final SelectorListener listener, final SelectableChannel channel) {
        logger.debug("modifying registration : {} for accept : {}, read : {}, write : {}", new Object[] { listener,
                                accept, read, write });

        final SelectionKey key = channel.keyFor(selector);
        if (key == null) {
            logger.error("Trying to modify the registration of a not registered channel");
            return;
        }

        int ops = 0;
        if (accept) {
            ops |= SelectionKey.OP_ACCEPT;
        }
        if (read) {
            ops |= SelectionKey.OP_READ;
        }
        if (write) {
            ops |= SelectionKey.OP_WRITE;
        }
        key.interestOps(ops);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregister(final SelectorListener listener, final SelectableChannel channel) {
        logger.debug("unregistering : {}", listener);
        final SelectionKey key = channel.keyFor(selector);
        if (key == null) {
            logger.error("Trying to modify the registration of a not registered channel");
            return;
        }
        key.cancel();
        key.attach(null);
        logger.debug("unregistering : {} done !", listener);

    }

    /**
     * The worker processing incoming session creation, session destruction requests, session write and reads. It will
     * also bind new servers.
     */
    private class SelectorWorker extends Thread {

        public SelectorWorker(final String prefix, final int index) {
            super("SelectorWorker " + prefix + "-" + index);
            setDaemon(true);
        }

        @Override
        public void run() {

            for (;;) {
                try {
                    logger.debug("selecting...");
                    final int readyCount = selector.select();
                    logger.debug("... done selecting : {} events", readyCount);
                    final Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        final SelectionKey key = it.next();
                        final SelectorListener listener = (SelectorListener) key.attachment();
                        listener.ready(key.isAcceptable(), key.isReadable(), key.isReadable() ? readBuffer : null,
                                key.isWritable());
                        // if you don't remove the event of the set, the selector will present you this event again and
                        // again
                        it.remove();
                    }

                    // new registration
                    while (!registrationQueue.isEmpty()) {
                        final Registration reg = registrationQueue.poll();

                        try {
                            reg.channel.register(selector, reg.ops, reg.listener);
                        } catch (final ClosedChannelException ex) {
                            // dead session..
                            logger.error("socket is already dead", ex);
                        }
                    }
                } catch (final Exception e) {
                    logger.error("Unexpected exception : ", e);
                }
            }
        }
    }

    private class Registration {

        public Registration(final int ops, final SelectableChannel channel, final SelectorListener listener) {
            this.ops = ops;
            this.channel = channel;
            this.listener = listener;
        }

        private final int ops;
        private final SelectableChannel channel;
        private final SelectorListener listener;
    }
}