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
import org.saltyrtc.client.tasks.Task;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.w3c.dom.Text;

import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {

	private static String LOG_TAG = MainActivity.class.getName();

	private SaltyRTC client;
	private WebRTCTask task;

	private Button startButton;
	private Button stopButton;
	private TextView signalingState;

	@SuppressLint("SetTextI18n")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		((TextView)findViewById(R.id.public_key)).setText("Public key: " + Config.PUBLIC_KEY);
		((TextView)findViewById(R.id.private_key)).setText("Private key: " + Config.PRIVATE_KEY);
		((TextView)findViewById(R.id.trusted_key)).setText("Trusted key: " + Config.TRUSTED_KEY);

		this.startButton = (Button) findViewById(R.id.button_start);
		this.stopButton = (Button) findViewById(R.id.button_stop);
		this.signalingState = (TextView) findViewById(R.id.state);
	}

	private SSLContext getSslContext() throws NoSuchAlgorithmException {
		return SSLContext.getDefault();
	}

	private void init() throws NoSuchAlgorithmException, InvalidKeyException {
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
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					MainActivity.this.signalingState.setText(event.getState().name());
				}
			});
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
	 * Start SaltyRTC client.
	 *
	 * Must be run on UI thread.
	 */
	public void start(View view) {
		Log.d(LOG_TAG, "Starting SaltyRTC client...");
		try {
			this.init();
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
		this.startButton.setEnabled(true);
		this.stopButton.setEnabled(false);
	}
}
