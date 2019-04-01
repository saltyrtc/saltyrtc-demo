/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.webrtc;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;

import org.saltyrtc.chunkedDc.Chunker;
import org.saltyrtc.chunkedDc.Unchunker;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.saltyrtc.tasks.webrtc.crypto.DataChannelCryptoContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.DataChannel;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

@AnyThread
public class SecureDataChannelContext {
    @NonNull private final Logger log;
    @NonNull public final DataChannel dc;
    @NonNull public final FlowControlledDataChannel fcdc;
    @NonNull public final DataChannelCryptoContext crypto;
    @NonNull private final Unchunker unchunker;
    @NonNull private CompletableFuture<?> queue;
    private int chunkLength;
    private long messageId = 0;

    public SecureDataChannelContext(
        @NonNull final DataChannel dc, @NonNull final WebRTCTask task,
        @NonNull final Unchunker.MessageListener messageListener) {
        this.log = LoggerFactory.getLogger("SaltyRTC.Demo.SDCC" + dc.id());
        this.dc = dc;

        // Wrap as unbounded, flow-controlled data channel
        this.fcdc = new FlowControlledDataChannel(dc);

        // Create crypto context
        // Note: We need to apply encrypt-then-chunk for backwards
        //       compatibility reasons.
        this.crypto = task.createCryptoContext(dc.id());

        // Create unchunker
        this.unchunker = new Unchunker();
        this.unchunker.onMessage(messageListener);

        // Determine chunk length
        // Note: Hard-coded because webrtc.org...
        // Important: We need to do this here because the "open" state may not
        //            in case we're receiving a data channel.
        SecureDataChannelContext.this.chunkLength = 64 * 1024;

        // Bind state events
        dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(final long bufferedAmount) {}

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
                        break;
                }
            }

            @Override
            public void onMessage(@NonNull final DataChannel.Buffer buffer) {}
        });

        // Initialise queue
        this.queue = this.fcdc.ready();
    }

    /**
     * Enqueue an operation to be run in order on this channel's write queue.
     */
    public CompletableFuture<?> enqueue(@NonNull final Runnable operation) {
        this.queue = this.queue.thenRunAsync(operation);
        this.queue.exceptionally(error -> {
            log.error("Exception in write queue", error);
            return null;
        });
        return this.queue;
    }

    /**
     * Create a chunker for message fragmentation.
     *
     * @param buffer The message to be fragmented.
     */
    @NonNull public Chunker chunk(@NonNull final ByteBuffer buffer) {
        return new Chunker(this.messageId++, buffer, this.chunkLength);
    }

    /**
     * Hand in a chunk for reassembly.
     *
     * @param buffer The chunk to be added to the reassembly buffer.
     */
    public void unchunk(@NonNull final ByteBuffer buffer) {
        this.unchunker.add(buffer);
    }
}
