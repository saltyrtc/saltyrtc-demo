/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.signaling;

import android.app.Activity;
import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.saltyrtc.client.SaltyRTC;
import org.saltyrtc.client.SaltyRTCBuilder;
import org.saltyrtc.client.crypto.CryptoException;
import org.saltyrtc.client.crypto.CryptoProvider;
import org.saltyrtc.client.events.SignalingStateChangedEvent;
import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.client.exceptions.InvalidKeyException;
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.client.signaling.CloseCode;
import org.saltyrtc.client.signaling.state.SignalingState;
import org.saltyrtc.client.tasks.Task;
import org.saltyrtc.demo.app.Config;
import org.saltyrtc.demo.app.utils.LazysodiumCryptoProvider;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.saltyrtc.tasks.webrtc.WebRTCTaskBuilder;
import org.saltyrtc.tasks.webrtc.WebRTCTaskVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import javax.net.ssl.SSLContext;

/**
 * Handles the SaltyRTC signalling connection.
 *
 * Automatically creates a WebRTC peer connection once the WebRTC task has been
 * negotiated. It then hands over to a data channel as soon as possible.
 */
@AnyThread
public class SignalingConnection {
    @NonNull private static final Logger log =
        LoggerFactory.getLogger("SaltyRTC.Demo.SignalingConnection");
    @NonNull public static final CryptoProvider cryptoProvider = new LazysodiumCryptoProvider();

    @NonNull private final Activity activity;
    @NonNull private final org.webrtc.PeerConnection.Observer observer;
    @Nullable private SaltyRTC client;
    @Nullable private WebRTCTask task;
    @Nullable private PeerConnection pc;

    public SignalingConnection(
        @NonNull final Activity activity,
        @NonNull final org.webrtc.PeerConnection.Observer observer
    ) throws NoSuchAlgorithmException, CryptoException, InvalidKeyException {
        this.activity = activity;
        this.observer = observer;

        // Create SaltyRTC tasks
        final Task[] tasks = new Task[] {
            new WebRTCTaskBuilder()
                .withVersion(WebRTCTaskVersion.V1)
                .build(),
            new WebRTCTaskBuilder()
                .withVersion(WebRTCTaskVersion.V0)
                .build()
        };

        // Create SaltyRTC client
        this.client = new SaltyRTCBuilder(cryptoProvider)
            .connectTo(Config.HOST, Config.PORT, SSLContext.getDefault())
            .withServerKey(Config.SERVER_KEY)
            .withKeyStore(new KeyStore(cryptoProvider, Config.PRIVATE_KEY))
            .withTrustedPeerKey(Config.TRUSTED_KEY)
            .withPingInterval(30)
            .withWebsocketConnectTimeout(15000)
            .usingTasks(tasks)
            .asResponder();

        // Bind events
        this.client.events.signalingStateChanged.register(this::onSignalingStateChanged);
    }

    /**
     * Get the underlying SaltyRTC client instance.
     *
     * Note: This will be null until the task has been negotiated.
     */
    public @Nullable SaltyRTC getClient() {
        return this.client;
    }

    /**
     * Get the underlying SaltyRTC WebRTC task instance.
     */
    public @Nullable WebRTCTask getTask() {
        return this.task;
    }

    /**
     * Get the underlying peer connection instance.
     *
     * Note: This will be null until the handover process has been initiated.
     */
    public @Nullable org.webrtc.PeerConnection getPeerConnection() {
        if (this.pc == null) {
            return null;
        }
        return this.pc.getPeerConnection();
    }

    /**
     * On signaling state change.
     */
    private boolean onSignalingStateChanged(@NonNull final SignalingStateChangedEvent event) {
        if (SignalingState.TASK == event.getState()) {
            // Store chosen task
            final Task task = Objects.requireNonNull(this.client).getTask();
            if (!(task instanceof WebRTCTask)) {
                throw new RuntimeException("Unexpected task instance!");
            }
            this.task = (WebRTCTask) this.client.getTask();

            // Create peer connection via WebRTC
            this.pc = new PeerConnection(this.task, this.observer, this.activity);
        }

        // Keep listener registered
        return false;
    }

    /**
     * Connect to the signalling server.
     */
    public void connect() throws ConnectionException {
        log.debug("Connecting SaltyRTC client");
        Objects.requireNonNull(this.client).connect();
    }

    /**
     * Close all connections and unbind all events.
     *
     * Note: This instance cannot be used after calling this!
     */
    public void close() {
        if (this.task != null) {
            log.debug("Stopping WebRTC task");
            this.task.close(CloseCode.CLOSING_NORMAL);
        }

        if (this.client != null) {
            log.debug("Closing SaltyRTC client");
            this.client.disconnect();
            this.client.events.clearAll();
            this.client = null;
        }

        if (this.pc != null) {
            log.debug("Stopping WebRTC connection");
            this.pc.close();
            this.pc = null;
        }
    }
}
