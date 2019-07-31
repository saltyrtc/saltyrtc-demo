/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.chat;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;

import org.saltyrtc.chunkedDc.Unchunker;
import org.saltyrtc.demo.app.transport.ChunkMode;
import org.saltyrtc.demo.app.transport.CryptoMode;
import org.saltyrtc.demo.app.webrtc.DataChannelContext;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.DataChannel;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatches chat messages back and forth using a data channel underneath.
 */
@AnyThread
public class Chat {
    @NonNull private static final Logger log = LoggerFactory.getLogger("SaltyRTC.Demo.Chat");

    @NonNull private final DataChannelContext dcc;

    /**
     * Chat events.
     */
    @AnyThread
    public interface ChatEvents {
        /**
         * On (fully reassembled) message.
         */
        void onMessage(@NonNull ByteBuffer buffer);

        /**
         * On underlying transport's buffer status update.
         */
        void onBufferStatusUpdate(long lowWaterMark, long highWaterMark, long bufferedAmount);
    }

    public Chat(
        @NonNull final DataChannel dc,
        @NonNull final WebRTCTask task,
        @NonNull final ChatEvents events
    ) {
        // Handle incoming message
        final Unchunker.MessageListener messageListener = events::onMessage;

        // Note: We need to apply encrypt-then-chunk with unreliable/unordered
        //       chunking mode for backwards compatibility reasons.
        final DataChannelContext dcc = new DataChannelContext(
            CryptoMode.ENCRYPT_THEN_CHUNK, ChunkMode.UNRELIABLE_UNORDERED,
            dc, task, messageListener);
        this.dcc = dcc;

        // Bind events
        dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(final long bufferedAmount) {
                // Forward buffered amount to flow control
                dcc.fcdc.bufferedAmountChange();

                // Fire event
                events.onBufferStatusUpdate(
                    dcc.fcdc.getLowWaterMark(), dcc.fcdc.getHighWaterMark(), bufferedAmount);
            }

            @Override
            public void onStateChange() {
                switch (dc.state()) {
                    case CONNECTING:
                        log.debug("Data channel " + dc.label() + " connecting");
                        break;
                    case OPEN:
                        log.info("Data channel " + dc.label() + " open");
                        break;
                    case CLOSING:
                        log.debug("Data channel " + dc.label() + " closing");
                        break;
                    case CLOSED:
                        log.info("Data channel " + dc.label() + " closed");
                        dc.dispose();
                        break;
                }
            }

            @Override
            public void onMessage(@NonNull final DataChannel.Buffer buffer) {
                // Reassemble chunks to message
                dcc.receive(buffer.data);
            }
        });
    }

    /**
     * Send a byte sequence via the underlying data channel.
     *
     * Note: This uses the old encrypt-then-chunk method which results in high
     *       memory pressure and low throughput.
     */
    @AnyThread
    @NonNull public CompletableFuture<?> send(@NonNull final ByteBuffer buffer) {
        return this.dcc.sendAsync(buffer);
    }

    /**
     * Close the underlying data channel.
     */
    public void close() {
        log.debug("Closing chat");
        this.dcc.close();
    }
}
