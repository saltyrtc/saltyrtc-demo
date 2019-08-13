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

import org.webrtc.DataChannel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A flow-controlled (sender side) data channel that allows to queue an
 * infinite amount of messages.
 *
 * While this cancels the effect of the flow control, it prevents the data
 * channel's underlying buffer from becoming saturated by queueing all messages
 * in application space.
 */
@AnyThread
public class UnboundedFlowControlledDataChannel extends FlowControlledDataChannel {
    @NonNull private CompletableFuture<?> queue = this.ready();

    /**
     * Create a flow-controlled (sender side) data channel with an infinite
     * buffer.
     *
     * @param dc The data channel to be flow-controlled
     */
    public UnboundedFlowControlledDataChannel(@NonNull final DataChannel dc) {
        super(dc);
    }

    /**
     * Create a flow-controlled (sender side) data channel with an infinite
     * buffer.
     *
     * @param dc The data channel to be flow-controlled
     * @param lowWaterMark The low water mark unpauses the data channel once
     *   the buffered amount of bytes becomes less or equal to it.
     * @param highWaterMark The high water mark pauses the data channel once
     *   the buffered amount of bytes becomes greater or equal to it.
     */
    public UnboundedFlowControlledDataChannel(
        @NonNull final DataChannel dc, final long lowWaterMark, final long highWaterMark) {
        super(dc, lowWaterMark, highWaterMark);
    }

    /**
     * Write a message to the data channel's internal or application buffer for
     * delivery to the remote side.
     *
     * Warning: This method is not thread-safe.
     *
     * @param message The message to be sent.
     */
    public void write(@NonNull final DataChannel.Buffer message) {
        // Note: This very simple technique allows for ordered message
        //       queueing by using future chaining.
        this.queue = this.queue.thenRunAsync(() -> {
            // Wait until ready
            try {
                this.ready().get();
            } catch (ExecutionException error) {
                // Should not happen
                log.error("Woops!", error);
                return;
            } catch (InterruptedException error) {
                // Can happen when the channel has been closed abruptly
                log.error("Unable to send pending chunk! Channel closed abruptly?", error);
                return;
            }

            // Write message
            super.write(message);
        });
        this.queue.exceptionally(error -> {
            log.error("Exception in write queue", error);
            return null;
        });
    }
}
