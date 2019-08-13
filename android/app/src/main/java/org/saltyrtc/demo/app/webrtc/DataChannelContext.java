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
import android.support.annotation.Nullable;

import org.saltyrtc.chunkedDc.Chunker;
import org.saltyrtc.chunkedDc.Unchunker;
import org.saltyrtc.client.crypto.CryptoException;
import org.saltyrtc.client.exceptions.OverflowException;
import org.saltyrtc.client.exceptions.ProtocolException;
import org.saltyrtc.client.exceptions.ValidationError;
import org.saltyrtc.client.keystore.Box;
import org.saltyrtc.demo.app.transport.ChunkMode;
import org.saltyrtc.demo.app.transport.CryptoMode;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.saltyrtc.tasks.webrtc.crypto.DataChannelCryptoContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.DataChannel;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Wraps a flow-controlled (sender-side) data channel, applies additional
 * encryption and fragmentation/reassembly when sending/receiving depending
 * on the parameters provided.
 */
@AnyThread
public class DataChannelContext {
    @NonNull private final Logger log;
    @NonNull private final CryptoMode cryptoMode;
    @NonNull private final DataChannel dc;
    @NonNull public final FlowControlledDataChannel fcdc;
    @Nullable private final DataChannelCryptoContext crypto;
    @NonNull private final Unchunker unchunker;
    @NonNull private CompletableFuture<?> queue;
    private int chunkLength;
    private long messageId = 0;

    public DataChannelContext(
        @NonNull final CryptoMode cryptoMode,
        @NonNull final ChunkMode chunkMode,
        @NonNull final DataChannel dc,
        @NonNull final WebRTCTask task,
        @NonNull final Unchunker.MessageListener messageListener
    ) {
        this.log = LoggerFactory.getLogger("SaltyRTC.Demo.DCC." + dc.id());
        this.cryptoMode = cryptoMode;
        this.dc = dc;

        // Wrap as flow-controlled data channel
        this.fcdc = new FlowControlledDataChannel(dc);

        // Create crypto context (if needed)
        switch (cryptoMode) {
            case CHUNK_THEN_ENCRYPT:
            case ENCRYPT_THEN_CHUNK:
                this.crypto = task.createCryptoContext(dc.id());
                break;
            default:
                this.crypto = null;
                break;
        }

        // Create unchunker
        // TODO: Add support for reliable/ordered
        if (chunkMode != ChunkMode.UNRELIABLE_UNORDERED) {
            throw new RuntimeException("Unsupported mode: " + chunkMode);
        }
        this.unchunker = new Unchunker();
        this.unchunker.onMessage(buffer -> {
            // Decrypt message (if needed)
            if (this.cryptoMode == CryptoMode.ENCRYPT_THEN_CHUNK) {
                final Box box = new Box(buffer, DataChannelCryptoContext.NONCE_LENGTH);
                try {
                    buffer = ByteBuffer.wrap(Objects.requireNonNull(this.crypto).decrypt(box));
                } catch (ValidationError | ProtocolException error) {
                    log.error("Invalid packet received", error);
                    return;
                } catch (CryptoException error) {
                    log.error("Unable to encrypt", error);
                    return;
                }
            }

            // Hand out message
            log.debug("Data channel " + dc.label() + " incoming message of length "
                + buffer.remaining());
            messageListener.onMessage(buffer);
        });

        // Determine chunk length
        // Note: Hard-coded because webrtc.org...
        // Important: We need to do this here because the "open" state may not
        //            be fired in case we're receiving a data channel.
        this.chunkLength = 64 * 1024;

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
     * Send a message asynchronously via this channel's write queue. The
     * message will be fragmented into chunks.
     */
    @NonNull public CompletableFuture<?> sendAsync(@NonNull final ByteBuffer buffer) {
        return this.enqueue(() -> {
            try {
                this.send(buffer);
            } catch (OverflowException error) {
                log.error("CSN overflow", error);
            } catch (CryptoException error) {
                log.error("Unable to encrypt", error);
            }
        });
    }

    /**
     * Send a message synchronously, fragmented into chunks.
     */
    public void send(@NonNull ByteBuffer buffer) throws OverflowException, CryptoException {
        log.debug("Data channel " + this.dc.label() + " outgoing message of length " +
            buffer.remaining());

        // Encrypt message (if needed)
        if (this.cryptoMode == CryptoMode.ENCRYPT_THEN_CHUNK) {
            final Box box = Objects.requireNonNull(this.crypto).encrypt(bufferToBytes(buffer));
            buffer = ByteBuffer.wrap(box.toBytes());
        }

        // Write chunks
        // TODO: Add support for reliable/ordered
        final Chunker chunker = new Chunker(this.messageId++, buffer, this.chunkLength);
        while (chunker.hasNext()) {
            // Wait until we can send
            // Note: This will block!
            try {
                this.fcdc.ready().get();
            } catch (InterruptedException | ExecutionException error) {
                error.printStackTrace();
                return;
            }
            buffer = chunker.next();

            // Encrypt chunk (if needed)
            if (this.cryptoMode == CryptoMode.CHUNK_THEN_ENCRYPT) {
                final Box box = Objects.requireNonNull(this.crypto).encrypt(bufferToBytes(buffer));
                buffer = ByteBuffer.wrap(box.toBytes());
            }

            // Write chunk
            final DataChannel.Buffer chunk = new DataChannel.Buffer(buffer, true);
            log.debug("Data channel " + this.dc.label() + " outgoing chunk of length " +
                chunk.data.remaining());
            this.fcdc.write(chunk);
        }
    }

    /**
     * Hand in a chunk for reassembly.
     *
     * @param buffer The chunk to be added to the reassembly buffer.
     */
    public void receive(@NonNull ByteBuffer buffer) {
        log.debug("Data channel " + dc.label() + " incoming chunk of length " +
            buffer.remaining());

        // Decrypt chunk (if needed)
        if (this.cryptoMode == CryptoMode.CHUNK_THEN_ENCRYPT) {
            final Box box = new Box(buffer, DataChannelCryptoContext.NONCE_LENGTH);
            try {
                buffer = ByteBuffer.wrap(Objects.requireNonNull(this.crypto).decrypt(box));
            } catch (ValidationError | ProtocolException error) {
                log.error("Invalid packet received", error);
                return;
            } catch (CryptoException error) {
                log.error("Unable to encrypt", error);
                return;
            }
        }

        // Reassemble
        this.unchunker.add(buffer);
    }

    /**
     * Convert a ByteBuffer to a byte array.
     */
    @NonNull static private byte[] bufferToBytes(@NonNull final ByteBuffer buffer) {
        // Strip the buffer's array from unnecessary bytes
        // TODO: Fix the crypto API to use ByteBuffer - this is terrible.
        byte[] bytes = buffer.array();
        if (buffer.position() != 0 || buffer.remaining() != bytes.length) {
            bytes = Arrays.copyOf(buffer.array(), buffer.remaining());
        }
        return bytes;
    }

    /**
     * Close the underlying data channel.
     */
    public void close() {
        this.dc.close();
    }
}
