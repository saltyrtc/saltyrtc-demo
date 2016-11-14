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
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.client.signaling.state.SignalingState;
import org.saltyrtc.client.tasks.Task;
import org.saltyrtc.tasks.webrtc.SecureDataChannel;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.webrtc.DataChannel;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
	private LinearLayout messagesLayout;
	private ScrollView messagesScrollView;
	private EditText textInput;
	private Button sendButton;

	@SuppressLint("SetTextI18n")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Get button views
		this.startButton = (Button) findViewById(R.id.button_start);
		this.stopButton = (Button) findViewById(R.id.button_stop);

		// Get state views
		this.saltySignalingStateView = (TextView) findViewById(R.id.salty_signaling_state);
		this.rtcSignalingStateView = (TextView) findViewById(R.id.rtc_signaling_state);
		this.rtcIceConnectionStateView = (TextView) findViewById(R.id.rtc_ice_connection_state);
		this.rtcIceGatheringStateView = (TextView) findViewById(R.id.rtc_ice_gathering_state);

		// Get other views
		this.messagesLayout = (LinearLayout) findViewById(R.id.messages);
		this.messagesScrollView = (ScrollView) findViewById(R.id.messagesScroll);
		this.textInput = (EditText) findViewById(R.id.chat_input);
		this.sendButton = (Button) findViewById(R.id.send_button);

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

		final KeyStore permanentKey = new KeyStore(Config.PUBLIC_KEY, Config.PRIVATE_KEY);
		this.task = new WebRTCTask();
		this.client = new SaltyRTCBuilder()
				.connectTo(Config.HOST, Config.PORT, this.getSslContext())
				.withKeyStore(permanentKey)
				.withTrustedPeerKey(Config.TRUSTED_KEY)
				.usingTasks(new Task[]{this.task})
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
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						MainActivity.this.textInput.setVisibility(View.VISIBLE);
						MainActivity.this.sendButton.setVisibility(View.VISIBLE);
					}
				});
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
	void onNewSdc(final SecureDataChannel sdc) {
		sdc.registerObserver(new DataChannel.Observer() {
			@Override
			public void onBufferedAmountChange(long l) {
				Log.d(LOG_TAG, "Buffered amount changed: " + l);
			}

			@Override
			public void onStateChange() {
				Log.d(LOG_TAG, "State changed: " + sdc.state());
			}

			/**
			 * Handle incoming messages.
			 *
			 * SaltyRTC only supports binary data, so we encode data as UTF8 on
			 * the browser side and decode the string from UTF8 Here.
			 */
			@Override
			public void onMessage(DataChannel.Buffer buffer) {
				final byte[] bytes = buffer.data.array();
				Log.d(LOG_TAG, "New incoming message: " + bytes.length + " bytes");
				String message;
				try {
					message = new String(bytes, "UTF-8");
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
					return;
				}
				Log.d(LOG_TAG, "Message is: " + message);
				MainActivity.this.onMessage(message);
			}
		});
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
			this.messagesLayout.removeAllViewsInLayout();
		} catch (NoSuchAlgorithmException | InvalidKeyException | ConnectionException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Stop SaltyRTC client.
	 *
	 * Must be run on UI thread.
	 */
	public synchronized void stop(View view) {
		if (this.sdc != null) {
			Log.d(LOG_TAG, "Closing secure data channel...");
			this.sdc.close();
			this.sdc.dispose();
		}

		Log.d(LOG_TAG, "Stopping SaltyRTC client...");
		this.client.disconnect();
		this.client.events.signalingStateChanged.clear();
		this.client.events.handover.clear();
		this.client.events.signalingConnectionLost.clear();
		this.client.events.close.clear();
		this.client = null;
		this.webrtc = null;

		this.startButton.setEnabled(true);
		this.stopButton.setEnabled(false);
		this.textInput.setVisibility(View.INVISIBLE);
		this.sendButton.setVisibility(View.INVISIBLE);
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

	private TextView getMessageTextView(int colorResource, String text) {
		// Create text view
		final TextView view = new TextView(this);
		view.setText(text);
		view.setBackgroundColor(getResources().getColor(colorResource));

		// Set layout parameters
		final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		final int spacing = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, this.getResources().getDisplayMetrics());
		params.setMargins(spacing, spacing, spacing, 0);
		view.setPadding(spacing, spacing, spacing, spacing);
		view.setLayoutParams(params);

		return view;
	}

	/**
	 * Show message and scroll to bottom.
	 *
	 * Must be run on UI thread.
	 */
	private void showMessage(final View view) {
		MainActivity.this.messagesLayout.addView(view);
		MainActivity.this.messagesScrollView.post(new Runnable() {
			@Override
			public void run() {
				MainActivity.this.messagesScrollView.fullScroll(ScrollView.FOCUS_DOWN);
			}
		});
	}

	/**
	 * Handle incoming message.
	 *
	 * This method may be called from a background thread.
	 */
	public void onMessage(String message) {
		final View view = this.getMessageTextView(R.color.colorMessageIn, message);
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				MainActivity.this.showMessage(view);
			}
		});
	}

	/**
	 * Send message via DC.
	 *
	 * Must be run on UI thread.
	 */
	public void sendDc(View view) {
		Log.d(LOG_TAG, "Sending message...");
		final String text = this.textInput.getText().toString();
		final ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
		this.sdc.send(new DataChannel.Buffer(bytes, true));
		final View msgView = this.getMessageTextView(R.color.colorMessageOut, text);
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				MainActivity.this.showMessage(msgView);
			}
		});
		this.textInput.setText("");
	}


	/**
	 * Show key info.
	 */
	public void showKeyInfo(View view) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setCancelable(true);
		builder.setTitle("Key Info");
		StringBuilder msgBuilder = new StringBuilder();
		msgBuilder.append("Public key: ");
		msgBuilder.append(Config.PUBLIC_KEY);
		msgBuilder.append("\n\n");
		msgBuilder.append("Private key: ");
		msgBuilder.append(Config.PRIVATE_KEY);
		msgBuilder.append("\n\n");
		msgBuilder.append("Trusted key: ");
		msgBuilder.append(Config.TRUSTED_KEY);
		msgBuilder.append("\n\n");
		builder.setMessage(msgBuilder.toString());
		builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialogInterface, int i) {
				dialogInterface.dismiss();
			}
		});
		builder.create().show();
	}

}
