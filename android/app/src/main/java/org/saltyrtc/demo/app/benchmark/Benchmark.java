/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.benchmark;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.saltyrtc.demo.app.exceptions.InvalidParameterException;
import org.saltyrtc.demo.app.transport.ChunkMode;
import org.saltyrtc.demo.app.transport.CryptoMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.DataChannel;
import org.webrtc.PeerConnection;

/**
 * Starts a throughput benchmark with a
 *
 * - specific crypto mode (chunk-then-encrypt, encrypt-then-chunk, none),
 * - specific chunk mode (reliable/ordered, unreliable/unordered), and
 * - a length specifying the amount of bytes to be sent.
 */
@AnyThread
public class Benchmark {
    @NonNull private static final Logger log = LoggerFactory.getLogger("SaltyRTC.Demo.Benchmark");

    @NonNull public static Benchmark fromChannel(
        @NonNull final DataChannel dc
    ) throws InvalidParameterException {
        try {
            // Parse options
            final JSONObject options = new JSONObject(dc.label());
            final CryptoMode cryptoMode = CryptoMode.fromString(options.getString("crypto"));
            if (cryptoMode == null) {
                throw new InvalidParameterException("'crypto' contains an unknown crypto mode");
            }
            final ChunkMode chunkMode = ChunkMode.fromString(options.getString("chunk"));
            if (chunkMode == null) {
                throw new InvalidParameterException("'chunk' contains an unknown chunk mode");
            }
            final int length = options.getInt("length");
            if (length <= 0) {
                throw new InvalidParameterException("'length' must be an unsigned integer");
            }

            // Create instance
            return new Benchmark(dc, cryptoMode, chunkMode, length);
        } catch (JSONException error) {
            throw new InvalidParameterException(error);
        }
    }

    @NonNull public static Benchmark create(
        @NonNull final PeerConnection pc,
        @NonNull final CryptoMode cryptoMode,
        @NonNull final ChunkMode chunkMode,
        final int length
    ) throws JSONException {
        // Prepare protocol string
        JSONObject options = new JSONObject();
        options.put("crypto", cryptoMode.toString());
        options.put("chunk", chunkMode.toString());
        options.put("length", length);

        // Create a new data channel
        // Note: Would love to use the 'protocol' string instead of the 'label'
        //       but, once again, webrtc.org strikes with its crude
        //       implementation that lacks an accessor.
        final DataChannel.Init parameters = new DataChannel.Init();
        final DataChannel dc = pc.createDataChannel(options.toString(), parameters);

        // Create instance
        return new Benchmark(dc, cryptoMode, chunkMode, length);
    }

    private Benchmark(
        @NonNull final DataChannel dc,
        @NonNull final CryptoMode cryptoMode,
        @NonNull final ChunkMode chunkMode,
        final int length
    ) {
        // TODO: Continue here!
        log.warn("TODO: RUN BENCHMARK");
    }
}
