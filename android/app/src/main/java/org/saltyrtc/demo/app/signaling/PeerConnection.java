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

import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.client.signaling.CloseCode;
import org.saltyrtc.demo.app.BuildConfig;
import org.saltyrtc.demo.app.Config;
import org.saltyrtc.demo.app.webrtc.UnboundedFlowControlledDataChannel;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.saltyrtc.tasks.webrtc.events.MessageHandler;
import org.saltyrtc.tasks.webrtc.exceptions.UntiedException;
import org.saltyrtc.tasks.webrtc.messages.Answer;
import org.saltyrtc.tasks.webrtc.messages.Candidate;
import org.saltyrtc.tasks.webrtc.messages.Offer;
import org.saltyrtc.tasks.webrtc.transport.SignalingTransportHandler;
import org.saltyrtc.tasks.webrtc.transport.SignalingTransportLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Creates a WebRTC peer connection.
 *
 * Wires the peer connection instance to a SaltyRTC WebRTC task instance. Hands
 * the signalling channel over to a dedicated data channel once the peer
 * connection has been established.
 */
@AnyThread
class PeerConnection {
    @NonNull private static final Logger log = LoggerFactory.getLogger("SaltyRTC.Demo.PC");

    @NonNull private final WebRTCTask task;
    @NonNull private final org.webrtc.PeerConnection.Observer observer;
    @NonNull private final MediaConstraints constraints;
    @Nullable private PeerConnectionFactory factory;
    @Nullable private org.webrtc.PeerConnection pc;
    private boolean dcOpened = false;

    PeerConnection(
        @NonNull final WebRTCTask task,
        @NonNull final org.webrtc.PeerConnection.Observer observer,
        @NonNull final Activity activity
    ) {
        this.task = task;
        this.observer = observer;

        // Initialize factory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(activity)
                .setEnableInternalTracer(BuildConfig.DEBUG)
                .createInitializationOptions());
        this.factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory();

        // Set media constraints
        this.constraints = new MediaConstraints();

        // Set ICE servers
        final List<org.webrtc.PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(
            org.webrtc.PeerConnection.IceServer.builder("stun:" + Config.STUN_SERVER)
                .createIceServer());
        if (Config.TURN_SERVER != null) {
            iceServers.add(
                org.webrtc.PeerConnection.IceServer.builder("turn:" + Config.TURN_SERVER)
                    .setUsername(Config.TURN_USER)
                    .setPassword(Config.TURN_PASS)
                    .createIceServer());
        }

        // Bind task events
        task.setMessageHandler(new TaskMessageHandler());

        // Create peer connection & bind events
        this.pc = Objects.requireNonNull(this.factory).createPeerConnection(
            iceServers, new PeerConnectionObserver());

        // Get transport link
        final SignalingTransportLink link = this.task.getTransportLink();

        // Create data channel
        final DataChannel.Init parameters = new DataChannel.Init();
        parameters.id = link.getId();
        parameters.negotiated = true;
        parameters.ordered = true;
        parameters.protocol = link.getProtocol();
        final DataChannel dc = Objects.requireNonNull(this.pc).createDataChannel(
            link.getLabel(), parameters);

        // Wrap as unbounded, flow-controlled data channel
        final UnboundedFlowControlledDataChannel ufcdc = new UnboundedFlowControlledDataChannel(dc);

        // Create transport handler
        final SignalingTransportHandler handler = new SignalingTransportHandler() {
            @Override
            public long getMaxMessageSize() {
                // Sigh... still not supported by webrtc.org, so fallback to a
                // well-known (and, frankly, terribly small) value.
                return 64 * 1024;
            }

            @Override
            public void close() {
                log.debug("Data channel " + dc.label() + " close request");
                dc.close();
            }

            @Override
            public void send(@NonNull final ByteBuffer message) {
                log.debug("Data channel " + dc.label() + " outgoing signaling message of length " +
                    message.remaining());
                ufcdc.write(new DataChannel.Buffer(message, true));
            }
        };

        // Bind events
        dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(final long bufferedAmount) {
                ufcdc.bufferedAmountChange();
            }

            @Override
            public void onStateChange() {
                switch (dc.state()) {
                    case CONNECTING:
                        log.debug("Data channel " + dc.label() + " connecting");
                        break;
                    case OPEN:
                        if (PeerConnection.this.dcOpened) {
                            log.error("Data channel " + dc.label() + " re-opened");
                        } else {
                            PeerConnection.this.dcOpened = true;
                            log.info("Data channel " + dc.label() + " open");
                            task.handover(handler);
                        }
                        break;
                    case CLOSING:
                        if (!PeerConnection.this.dcOpened) {
                            log.error("Data channel " + dc.label() + " closing");
                        } else {
                            log.debug("Data channel " + dc.label() + " closing");
                            try {
                                link.closing();
                            } catch (UntiedException error) {
                                log.warn("Could not move into closing state", error);
                            }
                        }
                        break;
                    case CLOSED:
                        if (!PeerConnection.this.dcOpened) {
                            log.error("Data channel " + dc.label() + " closed");
                        } else {
                            log.info("Data channel " + dc.label() + " closed");
                            try {
                                link.closed();
                            } catch (UntiedException error) {
                                // Note: We can safely ignore this because, in
                                //       our case, the signalling instance may
                                //       be closed before the channel has been
                                //       through the closing sequence.
                            }
                        }
                        dc.dispose();
                        break;
                }
            }

            @Override
            public void onMessage(@NonNull final DataChannel.Buffer buffer) {
                if (!buffer.binary) {
                    task.close(CloseCode.PROTOCOL_ERROR);
                } else {
                    try {
                        link.receive(buffer.data);
                    } catch (UntiedException error) {
                        log.warn("Could not feed incoming data to the transport link", error);
                    }
                }
            }
        });
    }

    /**
     * Get the underlying peer connection instance.
     */
    @Nullable org.webrtc.PeerConnection getPeerConnection() {
        return this.pc;
    }

    /**
     * Handler for incoming task messages.
     */
    @AnyThread
    private class TaskMessageHandler implements MessageHandler {
        @Override
        public void onOffer(@NonNull final Offer offer) {
            log.debug("Received offer: " + offer.getSdp());
            PeerConnection.this.onOfferReceived(offer);
        }

        @Override
        public void onAnswer(@NonNull final Answer answer) {
            log.error("Unexpected answer received");
        }

        @Override
        public void onCandidates(@NonNull final Candidate[] candidates) {
            PeerConnection.this.onIceCandidatesReceived(candidates);
        }
    }

    @AnyThread
    private class PeerConnectionObserver implements org.webrtc.PeerConnection.Observer {
        @Override
        public void onSignalingChange(
            @NonNull final org.webrtc.PeerConnection.SignalingState signalingState
        ) {
            log.debug("SignalingConnection state change: " + signalingState.name());
            PeerConnection.this.observer.onSignalingChange(signalingState);
        }

        @Override
        public void onIceConnectionChange(
            @NonNull final org.webrtc.PeerConnection.IceConnectionState iceConnectionState
        ) {
            log.debug("ICE connection change to " + iceConnectionState.name());
            PeerConnection.this.observer.onIceConnectionChange(iceConnectionState);
        }

        @Override
        public void onIceConnectionReceivingChange(final boolean receiving) {
            log.debug("ICE connection receiving change: " + receiving);
            PeerConnection.this.observer.onIceConnectionReceivingChange(receiving);
        }

        @Override
        public void onIceGatheringChange(
            @NonNull final org.webrtc.PeerConnection.IceGatheringState iceGatheringState
        ) {
            log.debug("ICE gathering change: " + iceGatheringState.name());
            PeerConnection.this.observer.onIceGatheringChange(iceGatheringState);
        }

        @Override
        public void onIceCandidate(@NonNull final IceCandidate iceCandidate) {
            log.debug("ICE candidate gathered: " + iceCandidate.sdp);

            // Send candidate to the remote peer
            final Candidate candidate = new Candidate(
                iceCandidate.sdp, iceCandidate.sdpMid, iceCandidate.sdpMLineIndex);
            try {
                PeerConnection.this.task.sendCandidates(new Candidate[] { candidate });
            } catch (final ConnectionException error) {
                log.error("Could not send ICE candidate", error);
            }

            // Dispatch event
            PeerConnection.this.observer.onIceCandidate(iceCandidate);
        }

        @Override
        public void onIceCandidatesRemoved(@NonNull final IceCandidate[] iceCandidates) {
            log.debug("ICE candidates removed");
            PeerConnection.this.observer.onIceCandidatesRemoved(iceCandidates);
        }

        @Override
        public void onDataChannel(@NonNull final DataChannel dc) {
            log.debug("New data channel was created: " + dc.label());
            PeerConnection.this.observer.onDataChannel(dc);

        }

        @Override
        public void onRenegotiationNeeded() {
            log.debug("Negotiation needed");
            PeerConnection.this.observer.onRenegotiationNeeded();
        }

        @Override
        public void onAddStream(@NonNull final MediaStream mediaStream) {
            log.warn("Stream added");
            PeerConnection.this.observer.onAddStream(mediaStream);
        }

        @Override
        public void onRemoveStream(@NonNull final MediaStream mediaStream) {
            log.warn("Stream removed");
            PeerConnection.this.observer.onRemoveStream(mediaStream);
        }

        @Override
        public void onAddTrack(
            @NonNull final RtpReceiver rtpReceiver,
            @NonNull final MediaStream[] mediaStreams
        ) {
            log.warn("Add track");
            PeerConnection.this.observer.onAddTrack(rtpReceiver, mediaStreams);
        }
    }

    /**
     * An offer was received. Set the remote description.
     */
    private void onOfferReceived(@NonNull final Offer offer) {
        final SessionDescription offerDescription = new SessionDescription(
            SessionDescription.Type.OFFER, offer.getSdp());

        // Set remote description
        Objects.requireNonNull(this.pc).setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(@NonNull final SessionDescription description) {}

            @Override
            public void onCreateFailure(@NonNull final String error) {}

            @Override
            public void onSetSuccess() {
                log.debug("Remote description set");
                PeerConnection.this.onRemoteDescriptionSet();
            }

            @Override
            public void onSetFailure(@NonNull final String s) {
                log.error("Could not set remote description: " + s);
            }
        }, offerDescription);
    }

    /**
     * The remote description was set. Create and send an answer.
     */
    private void onRemoteDescriptionSet() {
        Objects.requireNonNull(this.pc).createAnswer(new SdpObserver() {
            @Nullable private SessionDescription answerDescription;

            @Override
            public void onCreateSuccess(@NonNull final SessionDescription description) {
                log.debug("Created answer");
                this.answerDescription = description;
                PeerConnection.this.pc.setLocalDescription(this, description);
            }

            @Override
            public void onCreateFailure(@NonNull final String error) {
                log.error("Could not create answer: " + error);
            }

            @Override
            public void onSetSuccess() {
                log.debug("Local description set");
                final Answer answer = new Answer(
                    Objects.requireNonNull(this.answerDescription).description);
                try {
                    PeerConnection.this.task.sendAnswer(answer);
                    log.debug("Sent answer: " + answer.getSdp());
                } catch (final ConnectionException error) {
                    log.error("Could not send answer: " + error.getMessage());
                }
            }

            @Override
            public void onSetFailure(@NonNull final String error) {
                log.error("Could not set local description: " + error);
            }
        }, this.constraints);
    }

    /**
     * One or more ICE candidates were received. Store them.
     */
    private void onIceCandidatesReceived(@NonNull final Candidate[] candidates) {
        for (@Nullable final Candidate candidate : candidates) {
            if (candidate == null) {
                // Note: Unsure how to signal end-of-candidates to webrtc.org
                continue;
            }
            final IceCandidate iceCandidate = new IceCandidate(
                candidate.getSdpMid(), candidate.getSdpMLineIndex(), candidate.getSdp());
            log.debug("New remote candidate: " + candidate.getSdp());
            Objects.requireNonNull(this.pc).addIceCandidate(iceCandidate);
        }
    }

    /**
     * Close and dispose this connection.
     *
     * Note: This instance cannot be used after calling this!
     */
    void close() {
        if (this.pc != null) {
            this.pc.dispose();
            this.pc = null;
        }
        if (this.factory != null) {
            this.factory.dispose();
            this.factory = null;
        }
    }
}
