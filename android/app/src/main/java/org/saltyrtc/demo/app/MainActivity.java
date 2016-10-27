package org.saltyrtc.demo.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.saltyrtc.client.SaltyRTC;
import org.saltyrtc.client.SaltyRTCBuilder;
import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.client.exceptions.InvalidKeyException;
import org.saltyrtc.client.helpers.HexHelper;
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.client.tasks.Task;
import org.saltyrtc.tasks.webrtc.WebRTCTask;

import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {

	private static String LOG_TAG = MainActivity.class.getName();

	private SaltyRTC client;
	private WebRTCTask task;

	@SuppressLint("SetTextI18n")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		((TextView)findViewById(R.id.public_key)).setText("Public key: " + Config.PUBLIC_KEY);
		((TextView)findViewById(R.id.private_key)).setText("Private key: " + Config.PRIVATE_KEY);
		((TextView)findViewById(R.id.trusted_key)).setText("Trusted key: " + Config.TRUSTED_KEY);
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
	}

	/**
	 * Start SaltyRTC client.
	 */
	public void start(View view) {
		Log.d(LOG_TAG, "Starting SaltyRTC client...");
		try {
			this.init();
			this.client.connect();
		} catch (NoSuchAlgorithmException | InvalidKeyException | ConnectionException e) {
			e.printStackTrace();
		}
	}
}
