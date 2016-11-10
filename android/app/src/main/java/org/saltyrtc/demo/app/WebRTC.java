/*
 * Copyright (c) 2016 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app;

import android.util.Log;

import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.tasks.webrtc.SecureDataChannel;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.List;

class WebRTC {

	private static final String LOG_TAG = WebRTC.class.getName();
	private static final String DC_LABEL = "much-secure";

	private final PeerConnection pc;
	private final WebRTCTask task;
	private final MediaConstraints constraints;
	private final MainActivity activity;

	WebRTC(WebRTCTask task, MainActivity activity) {
		this.task = task;
		this.activity = activity;

		// Initialize Android globals
		// See https://bugs.chromium.org/p/webrtc/issues/detail?id=3416
		final boolean ok = PeerConnectionFactory.initializeAndroidGlobals(activity, true, true, false);
		if (!ok) {
			throw new RuntimeException("initializeAndroidGlobals failed");
		}

		// Set ICE servers
		List<PeerConnection.IceServer> iceServers = new ArrayList<>();
		iceServers.add(new org.webrtc.PeerConnection.IceServer("stun:" + Config.STUN_SERVER));
		if (Config.TURN_SERVER != null) {
			iceServers.add(new org.webrtc.PeerConnection.IceServer("turn:" + Config.TURN_SERVER));
		}

		// Create peer connection
		final PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
		final PeerConnectionFactory factory = new PeerConnectionFactory(options);
		constraints = new MediaConstraints();
		this.pc = factory.createPeerConnection(iceServers, constraints, new PeerConnectionObserver());

		// Add task message event handler
		this.task.setMessageHandler(new TaskMessageHandler());
	}

	/**
	 * Handler for incoming task messages.
	 */
	private class TaskMessageHandler implements org.saltyrtc.tasks.webrtc.events.MessageHandler {
		@Override
		public void onOffer(SessionDescription sd) {
			WebRTC.this.onOfferReceived(sd);
		}

		@Override
		public void onAnswer(SessionDescription sd) {
			Log.d(LOG_TAG, "Received answer. Ignoring.");
		}

		@Override
		public void onCandidates(List<IceCandidate> candidates) {
			WebRTC.this.onIceCandidatesReceived(candidates);
		}
	}

	/**
	 * A WebRTC offer was received. Set the remote description.
	 */
	private void onOfferReceived(SessionDescription offer) {
		// Set remote description
		this.pc.setRemoteDescription(new SdpObserver() {
			@Override
			public void onCreateSuccess(SessionDescription sd) { }

			@Override
			public void onCreateFailure(String s) { }

			@Override
			public void onSetSuccess() {
				Log.d(LOG_TAG, "Remote description set");
				WebRTC.this.onRemoteDescriptionSet();
			}

			@Override
			public void onSetFailure(String s) {
				Log.e(LOG_TAG, "Could not set remote description: " + s);
			}
		}, offer);
	}

	/**
	 * The remote description was set. Create and send an answer.
	 */
	private void onRemoteDescriptionSet() {
		this.pc.createAnswer(new SdpObserver() {
			private SessionDescription sd;

			@Override
			public void onCreateSuccess(SessionDescription sd) {
				Log.d(LOG_TAG, "Created answer");
				this.sd = sd;
				WebRTC.this.pc.setLocalDescription(this, sd);
			}

			@Override
			public void onCreateFailure(String s) {
				Log.e(LOG_TAG, "Could not create answer: " + s);
			}

			@Override
			public void onSetSuccess() {
				Log.d(LOG_TAG, "Local description set");
				try {
					WebRTC.this.task.sendAnswer(this.sd);
					Log.d(LOG_TAG, "Sent answer");
				} catch (ConnectionException e) {
					Log.e(LOG_TAG, "Could not send answer: " + e.getMessage());
				}
			}

			@Override
			public void onSetFailure(String s) {
				Log.e(LOG_TAG, "Could not set local description: " + s);
			}
		}, this.constraints);
	}

	/**
	 * One or more ICE candidates were received. Store them.
	 */
	private void onIceCandidatesReceived(List<IceCandidate> candidates) {
		for (IceCandidate candidate : candidates) {
			this.pc.addIceCandidate(candidate);
		}
		Log.d(LOG_TAG, "Added " + candidates.size() + " ICE candidate(s)");
	}

	private class PeerConnectionObserver implements org.webrtc.PeerConnection.Observer {
		@Override
		public void onSignalingChange(org.webrtc.PeerConnection.SignalingState signalingState) {
			Log.d(LOG_TAG, "Signaling state change: " + signalingState.name());
			WebRTC.this.activity.setState(StateType.RTC_SIGNALING, signalingState.name());
		}

		@Override
		public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
			Log.d(LOG_TAG, "ICE connection change to " + iceConnectionState.name());
			WebRTC.this.activity.setState(StateType.RTC_ICE_CONNECTION, iceConnectionState.name());
		}

		@Override
		public void onIceConnectionReceivingChange(boolean b) {
			Log.d(LOG_TAG, "ICE connection receiving change: " + b);
		}

		@Override
		public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
			Log.d(LOG_TAG, "ICE gathering change: " + iceGatheringState.name());
			WebRTC.this.activity.setState(StateType.RTC_ICE_GATHERING, iceGatheringState.name());
		}

		/**
		 * A new ICE candidate was generated. Send it to the peer.
		 */
		@Override
		public void onIceCandidate(IceCandidate iceCandidate) {
			Log.d(LOG_TAG, "New ICE candidate");
			try {
				WebRTC.this.task.sendCandidates(iceCandidate);
			} catch (ConnectionException e) {
				Log.e(LOG_TAG, "Could not send ICE candidate: " + e.getMessage());
			}
		}

		@Override
		public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
			Log.d(LOG_TAG, "ICE candidate removed");
		}

		@Override
		public void onAddStream(MediaStream mediaStream) {
			Log.d(LOG_TAG, "Stream added");
		}

		@Override
		public void onRemoveStream(MediaStream mediaStream) {
			Log.d(LOG_TAG, "Stream removed");
		}

		@Override
		public void onDataChannel(DataChannel dc) {
			Log.d(LOG_TAG, "New data channel: " + dc.label());

			if (!DC_LABEL.equals(dc.label())) {
				return;
			}

			// If the newly created data channel is the one we want, wrap it.
			final SecureDataChannel secureDataChannel = WebRTC.this.task.wrapDataChannel(dc);

			// Notify main class about this new data channel.
			WebRTC.this.activity.onNewSdc(secureDataChannel);
		}

		@Override
		public void onRenegotiationNeeded() {
			Log.d(LOG_TAG, "Renegotiation needed");
		}
	}

	/**
	 * Initiate handover.
	 */
	void handover() {
		this.task.handover(this.pc);
	}

}
