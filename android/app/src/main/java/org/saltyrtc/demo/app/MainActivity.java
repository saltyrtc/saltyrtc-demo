/*
 * Copyright (c) 2016 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.saltyrtc.client.SaltyRTC;
import org.saltyrtc.client.SaltyRTCBuilder;
import org.saltyrtc.client.events.CloseEvent;
import org.saltyrtc.client.events.EventHandler;
import org.saltyrtc.client.events.HandoverEvent;
import org.saltyrtc.client.events.SignalingConnectionLostEvent;
import org.saltyrtc.client.events.SignalingStateChangedEvent;
import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.client.exceptions.InvalidKeyException;
import org.saltyrtc.client.helpers.HexHelper;
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.client.signaling.state.SignalingState;
import org.saltyrtc.client.tasks.Task;
import org.saltyrtc.tasks.webrtc.SecureDataChannel;
import org.saltyrtc.tasks.webrtc.WebRTCTask;

import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {

	private static String LOG_TAG = MainActivity.class.getName();

	private SaltyRTC client;
	private WebRTCTask task;
	private WebRTC webrtc;
	private SecureDataChannel sdc;

	private Button startButton;
	private Button stopButton;
	private TextView saltySignalingStateView;
	private TextView rtcSignalingStateView;
	private TextView rtcIceConnectionStateView;
	private TextView rtcIceGatheringStateView;

	@SuppressLint("SetTextI18n")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Set key infos
		((TextView)findViewById(R.id.public_key)).setText("Public key: " + Config.PUBLIC_KEY);
		((TextView)findViewById(R.id.private_key)).setText("Private key: " + Config.PRIVATE_KEY);
		((TextView)findViewById(R.id.trusted_key)).setText("Trusted key: " + Config.TRUSTED_KEY);

		// Get button views
		this.startButton = (Button) findViewById(R.id.button_start);
		this.stopButton = (Button) findViewById(R.id.button_stop);

		// Get state views
		this.saltySignalingStateView = (TextView) findViewById(R.id.salty_signaling_state);
		this.rtcSignalingStateView = (TextView) findViewById(R.id.rtc_signaling_state);
		this.rtcIceConnectionStateView = (TextView) findViewById(R.id.rtc_ice_connection_state);
		this.rtcIceGatheringStateView = (TextView) findViewById(R.id.rtc_ice_gathering_state);

		// Initialize states
		this.resetStates();
	}

	/**
	 * Reset all states to "Unknown".
	 */
	private void resetStates() {
		this.setState(StateType.SALTY_SIGNALING, "Unknown");
		this.setState(StateType.RTC_SIGNALING, "Unknown");
		this.setState(StateType.RTC_ICE_CONNECTION, "Unknown");
		this.setState(StateType.RTC_ICE_GATHERING, "Unknown");
	}

	private SSLContext getSslContext() throws NoSuchAlgorithmException {
		return SSLContext.getDefault();
	}

	private void init() throws NoSuchAlgorithmException, InvalidKeyException {
		this.resetStates();

		final byte[] pubKey = HexHelper.hexStringToByteArray(Config.PUBLIC_KEY);
		final byte[] privKey = HexHelper.hexStringToByteArray(Config.PRIVATE_KEY);
		final byte[] trustedKey = HexHelper.hexStringToByteArray(Config.TRUSTED_KEY);
		final KeyStore permanentKey = new KeyStore(pubKey, privKey);
		this.task = new WebRTCTask();
		this.client = new SaltyRTCBuilder()
				.connectTo(Config.HOST, Config.PORT, this.getSslContext())
				.withKeyStore(permanentKey)
				.withTrustedPeerKey(trustedKey)
				.usingTasks(new Task[] { this.task })
				.asResponder();

		// On signaling
		this.client.events.signalingStateChanged.register(this.onSignalingStateChanged);
		this.client.events.handover.register(this.onHandover);
		this.client.events.close.register(this.onClose);
		this.client.events.signalingConnectionLost.register(this.onSignalingConnectionLost);
	}

	/**
	 * On signaling state change.
	 */
	private EventHandler<SignalingStateChangedEvent> onSignalingStateChanged = new EventHandler<SignalingStateChangedEvent>() {
		@Override
		public boolean handle(final SignalingStateChangedEvent event) {
			MainActivity.this.setState(StateType.SALTY_SIGNALING, event.getState().name());
			if (SignalingState.TASK == event.getState()) {
				MainActivity.this.webrtc.handover();
			}
			return false;
		}
	};

	/**
	 * On handover.
	 */
	private EventHandler<HandoverEvent> onHandover = new EventHandler<HandoverEvent>() {
		@Override
		public boolean handle(final HandoverEvent event) {
			return false;
		}
	};

	/**
	 * On close.
	 */
	private EventHandler<CloseEvent> onClose = new EventHandler<CloseEvent>() {
		@Override
		public boolean handle(final CloseEvent event) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					MainActivity.this.stop(null);
				}
			});
			return false;
		}
	};

	/**
	 * On signaling connection lost.
	 */
	private EventHandler<SignalingConnectionLostEvent> onSignalingConnectionLost = new EventHandler<SignalingConnectionLostEvent>() {
		@Override
		public boolean handle(final SignalingConnectionLostEvent event) {
			return false;
		}
	};

	/**
	 * A new secure data channel was created.
	 */
	void onNewSdc(SecureDataChannel sdc) {
		this.sdc = sdc;
	}

	/**
	 * Start SaltyRTC client.
	 *
	 * Must be run on UI thread.
	 */
	public void start(View view) {
		Log.d(LOG_TAG, "Starting SaltyRTC client...");
		try {
			this.init();
			this.webrtc = new WebRTC(this.task, this);
			this.client.connect();
			this.startButton.setEnabled(false);
			this.stopButton.setEnabled(true);
		} catch (NoSuchAlgorithmException | InvalidKeyException | ConnectionException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Stop SaltyRTC client.
	 *
	 * Must be run on UI thread.
	 */
	public void stop(View view) {
		Log.d(LOG_TAG, "Stoppping SaltyRTC client...");
		this.client.disconnect();
		this.client.events.signalingStateChanged.clear();
		this.client.events.handover.clear();
		this.client.events.signalingConnectionLost.clear();
		this.client.events.close.clear();
		this.client = null;
		this.webrtc = null;
		this.startButton.setEnabled(true);
		this.stopButton.setEnabled(false);
	}

	/**
	 * Set a state field.
	 *
	 * This method may be called from a background thread.
	 */
	public void setState(final StateType type, final String state) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				switch (type) {
					case SALTY_SIGNALING:
						MainActivity.this.saltySignalingStateView.setText(state);
						break;
					case RTC_SIGNALING:
						MainActivity.this.rtcSignalingStateView.setText(state);
						break;
					case RTC_ICE_CONNECTION:
						MainActivity.this.rtcIceConnectionStateView.setText(state);
						break;
					case RTC_ICE_GATHERING:
						MainActivity.this.rtcIceGatheringStateView.setText(state);
						break;
				}
			}
		});

	}
}
