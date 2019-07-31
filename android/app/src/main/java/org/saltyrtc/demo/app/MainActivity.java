/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.saltyrtc.client.SaltyRTC;
import org.saltyrtc.client.crypto.CryptoException;
import org.saltyrtc.client.events.ApplicationDataEvent;
import org.saltyrtc.client.events.CloseEvent;
import org.saltyrtc.client.events.HandoverEvent;
import org.saltyrtc.client.events.SignalingStateChangedEvent;
import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.client.exceptions.InvalidKeyException;
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.client.signaling.state.SignalingState;
import org.saltyrtc.demo.app.chat.Chat;
import org.saltyrtc.demo.app.signaling.SignalingConnection;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.HandroidLoggerAdapter;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public class MainActivity extends Activity {
    static {
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG;
        HandroidLoggerAdapter.APP_NAME = "Demo";
    }
    @NonNull private static final Logger log =
        LoggerFactory.getLogger("SaltyRTC.Demo.MainActivity");

    @Nullable private SignalingConnection sc;
    @Nullable private Chat chat;

    private Button startButton;
    private Button stopButton;
    private TextView saltySignalingStateView;
    private TextView rtcSignalingStateView;
    private TextView rtcIceConnectionStateView;
    private TextView rtcIceGatheringStateView;
    private TextView saltyHandoverStateView;
    private LinearLayout messagesLayout;
    private ScrollView messagesScrollView;
    private LinearLayout bufferLayout;
    private ProgressBar bufferStatus;
    private LinearLayout textLayout;
    private EditText textInput;
    private Button sendTextButton;
    private LinearLayout binaryLayout;
    private EditText binaryInput;
    private Button sendBinaryButton;

    @SuppressLint("SetTextI18n")
    @Override
    @MainThread
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get button views
        this.startButton = findViewById(R.id.button_start);
        this.stopButton = findViewById(R.id.button_stop);

        // Get state views
        this.saltySignalingStateView = findViewById(R.id.salty_signaling_state);
        this.rtcSignalingStateView = findViewById(R.id.rtc_signaling_state);
        this.rtcIceConnectionStateView = findViewById(R.id.rtc_ice_connection_state);
        this.rtcIceGatheringStateView = findViewById(R.id.rtc_ice_gathering_state);
        this.saltyHandoverStateView = findViewById(R.id.salty_handover_state);

        // Get other views
        this.messagesLayout = findViewById(R.id.messages);
        this.messagesScrollView = findViewById(R.id.messages_scroll);
        this.bufferLayout = findViewById(R.id.buffer_layout);
        this.bufferStatus = findViewById(R.id.buffer_status);
        this.textLayout = findViewById(R.id.text_layout);
        this.textInput = findViewById(R.id.text_input);
        this.sendTextButton = findViewById(R.id.send_text_button);
        this.binaryLayout = findViewById(R.id.binary_layout);
        this.binaryInput = findViewById(R.id.binary_input);
        this.sendBinaryButton = findViewById(R.id.send_binary_button);

        // Initialize states
        this.runOnUiThread(this::resetStates);
    }

    /**
     * Show/hide send elements
     */
    @UiThread
    private void setSendElementsVisible(final boolean on) {
        final int visibility = on ? View.VISIBLE : View.INVISIBLE;
        this.bufferLayout.setVisibility(visibility);
        this.textLayout.setVisibility(visibility);
        this.binaryLayout.setVisibility(visibility);
    }

    /**
     * Enable/disable send elements.
     */
    @UiThread
    private void setSendElementsEnabled(final boolean on) {
        this.textInput.setEnabled(on);
        this.sendTextButton.setEnabled(on);
        this.binaryInput.setEnabled(on);
        this.sendBinaryButton.setEnabled(on);
    }

    /**
     * Reset all states to "Unknown".
     */
    @UiThread
    private void resetStates() {
        this.setState(StateType.SALTY_SIGNALING, "Unknown");
        this.setState(StateType.RTC_SIGNALING, "Unknown");
        this.setState(StateType.RTC_ICE_CONNECTION, "Unknown");
        this.setState(StateType.RTC_ICE_GATHERING, "Unknown");
        this.setState(StateType.SALTY_HANDOVER, "Unknown");
    }

    /**
     * Handler for signalling events.
     */
    @AnyThread
    private class SignalingEvents {
        boolean onSignalingStateChanged(@NonNull final SignalingStateChangedEvent event) {
            // Update state
            MainActivity.this.setState(StateType.SALTY_SIGNALING, event.getState().name());

            // Show send elements (once in task state)
            if (SignalingState.TASK == event.getState()) {
                MainActivity.this.runOnUiThread(() ->
                    MainActivity.this.setSendElementsVisible(true));
            }

            // Keep listener registered
            return false;
        }

        boolean onClose(@SuppressWarnings("unused") @NonNull final CloseEvent event) {
            // Stop
            MainActivity.this.runOnUiThread(() -> MainActivity.this.stop(null));

            // Unregister listener
            return true;
        }

        boolean onHandover(@SuppressWarnings("unused") @NonNull final HandoverEvent event) {
            // Enable UI elements
            MainActivity.this.runOnUiThread(() ->
                MainActivity.this.setState(StateType.SALTY_HANDOVER, "YES"));

            // Unregister listener
            return true;
        }

        boolean onApplicationData(@NonNull final ApplicationDataEvent event) {
            // Display message
            final ByteBuffer buffer = ByteBuffer.wrap((byte[]) event.getData());
            log.debug("Incoming application message: " + buffer.remaining() + " bytes");
            final String message = StandardCharsets.UTF_8.decode(buffer).toString();
            MainActivity.this.showMessage(R.color.colorMessageIn, message);

            // Keep listener registered
            return false;
        }
    }

    /**
     * Handler for chat events.
     */
    @AnyThread
    private class ChatEvents implements Chat.ChatEvents {
        @Override
        public void onMessage(@NonNull final ByteBuffer buffer) {
            // Convert to string
            // TODO: This is ugly... we should use a separate channel instead
            final String message;
            if (buffer.remaining() < 255) {
                message = StandardCharsets.UTF_8.decode(buffer).toString();
            } else {
                message = "[" + buffer.remaining() / 1024 + " KiB binary data]";
            }

            // Display
            MainActivity.this.showMessage(R.color.colorMessageIn, message);
        }

        @Override
        public void onBufferStatusUpdate(
            final long lowWaterMark,
            final long highWaterMark,
            final long bufferedAmount
        ) {
            final int progress = (int) (((float) bufferedAmount / (float) highWaterMark) * 100);
            MainActivity.this.runOnUiThread(() ->
                MainActivity.this.bufferStatus.setProgress(progress));
        }
    }

    /**
     * Handler for peer-to-peer connection events.
     */
    @AnyThread
    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(
            @NonNull final PeerConnection.SignalingState signalingState
        ) {
            MainActivity.this.setState(StateType.RTC_SIGNALING, signalingState.name());
        }

        @Override
        public void onIceConnectionChange(
            @NonNull final PeerConnection.IceConnectionState iceConnectionState
        ) {
            MainActivity.this.setState(StateType.RTC_ICE_CONNECTION, iceConnectionState.name());
        }

        @Override
        public void onIceConnectionReceivingChange(final boolean receiving) {}

        @Override
        public void onIceGatheringChange(
            @NonNull final PeerConnection.IceGatheringState iceGatheringState
        ) {
            MainActivity.this.setState(StateType.RTC_ICE_GATHERING, iceGatheringState.name());
        }

        @Override
        public void onIceCandidate(@NonNull final IceCandidate iceCandidate) {}

        @Override
        public void onIceCandidatesRemoved(@NonNull final IceCandidate[] iceCandidates) {}

        @Override
        public void onDataChannel(@NonNull final DataChannel dc) {
            // Ensure the WebRTC task instance is available
            final SignalingConnection sc = Objects.requireNonNull(MainActivity.this.sc);
            final WebRTCTask task = Objects.requireNonNull(sc.getTask());

            // Create a chat instance (if not already created)
            if (MainActivity.this.chat == null) {
                MainActivity.this.chat = new Chat(dc, task, new ChatEvents());

                // Enable send elements
                MainActivity.this.runOnUiThread(() ->
                    MainActivity.this.setSendElementsEnabled(true));
                return;
            }

            // Close unhandled
            log.error("Closing unexpected data channel: " + dc.label());
            dc.close();
        }

        @Override
        public void onRenegotiationNeeded() {}

        @Override
        public void onAddStream(@NonNull final MediaStream mediaStream) {}

        @Override
        public void onRemoveStream(@NonNull final MediaStream mediaStream) {}

        @Override
        public void onAddTrack(
            @NonNull final RtpReceiver rtpReceiver,
            @NonNull final MediaStream[] mediaStreams
        ) {}
    }

    @UiThread
    private void init() {
        this.resetStates();
    }

    /**
     * Start SaltyRTC client.
     */
    @UiThread
    public void start(@NonNull final View view) {
        log.debug("Starting SaltyRTC client...");
        try {
            this.init();

            // Create signalling connection
            this.sc = new SignalingConnection(this, new PeerConnectionObserver());

            // Bind signalling events
            final SaltyRTC client = Objects.requireNonNull(this.sc.getClient());
            final SignalingEvents events = new SignalingEvents();
            client.events.signalingStateChanged.register(events::onSignalingStateChanged);
            client.events.close.register(events::onClose);
            client.events.handover.register(events::onHandover);
            client.events.applicationData.register(events::onApplicationData);

            // Initiate connecting to signalling server
            this.sc.connect();

            // Swap start/stop button
            this.startButton.setEnabled(false);
            this.stopButton.setEnabled(true);

            // Reset text input
            this.textInput.setText("");

            // Purge messages logged
            this.messagesLayout.removeAllViewsInLayout();
            this.setState(StateType.SALTY_HANDOVER, "NO");
        } catch (NoSuchAlgorithmException | InvalidKeyException | ConnectionException |
                 CryptoException e) {
            e.printStackTrace();
        }
    }

    /**
     * Stop SaltyRTC client.
     */
    @UiThread
    public void stop(@Nullable final View view) {
        // Close chat
        if (this.chat != null) {
            this.chat.close();
            this.chat = null;
        }

        // Close signalling connection
        if (this.sc != null) {
            this.sc.close();
            this.sc = null;
        }

        // Reset start/stop button
        this.startButton.setEnabled(true);
        this.stopButton.setEnabled(false);

        // Reset buffer fill status
        this.bufferStatus.setProgress(0);

        // Reset text input
        this.textInput.setText("");

        // Disable send elements
        this.setSendElementsEnabled(false);

        // Hide send elements
        this.setSendElementsVisible(false);
    }

    /**
     * Set a state field.
     *
     * This method may be called from a background thread.
     */
    @AnyThread
    public void setState(@NonNull final StateType type, @NonNull final String state) {
        this.runOnUiThread(() -> {
            switch (type) {
                case SALTY_SIGNALING:
                    this.saltySignalingStateView.setText(state);
                    break;
                case RTC_SIGNALING:
                    this.rtcSignalingStateView.setText(state);
                    break;
                case RTC_ICE_CONNECTION:
                    this.rtcIceConnectionStateView.setText(state);
                    break;
                case RTC_ICE_GATHERING:
                    this.rtcIceGatheringStateView.setText(state);
                    break;
                case SALTY_HANDOVER:
                    this.saltyHandoverStateView.setText(state);
                    break;
            }
        });

    }

    @AnyThread
    @NonNull private TextView getMessageTextView(
        final int colorResource, @NonNull final String text) {
        // Create text view
        final TextView view = new TextView(this);
        view.setText(text);
        view.setBackgroundColor(getResources().getColor(colorResource, null));

        // Set layout parameters
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        final int spacing = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
            this.getResources().getDisplayMetrics());
        params.setMargins(spacing, spacing, spacing, 0);
        view.setPadding(spacing, spacing, spacing, spacing);
        view.setLayoutParams(params);

        return view;
    }

    /**
     * Add text message to view and scroll to bottom.
     */
    @AnyThread
    private void showMessage(final int colorResource, @NonNull final String message) {
        final View view = this.getMessageTextView(colorResource, message);
        this.runOnUiThread(() -> {
            this.messagesLayout.addView(view);
            this.messagesScrollView.post(() ->
                this.messagesScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
    /**
     * Send text message via the secure data channel.
     */
    @UiThread
    public void sendTextMessage(@NonNull final View view) {
        // Fetch from input and encode
        final String text = this.textInput.getText().toString();
        final ByteBuffer buffer = StandardCharsets.UTF_8.encode(text);

        // Disable send elements until sent
        this.setSendElementsEnabled(false);

        // Strip the buffer's array from unnecessary bytes
        final byte[] bytes = Arrays.copyOf(buffer.array(), buffer.remaining());

        // Send message
        Objects.requireNonNull(this.chat)
            .send(ByteBuffer.wrap(bytes))
            .thenRun(() -> this.runOnUiThread(() -> {
                // Reset text
                this.textInput.setText("");

                // Re-enable send elements
                this.setSendElementsEnabled(true);

                // Show sent message
                this.showMessage(R.color.colorMessageOut, text);
            }));
    }

    /**
     * Send binary message via the secure data channel.
     */
    @UiThread
    public void sendBinaryMessage(@NonNull final View view) {
        // Fetch length from input
        final Integer length = Integer.parseInt(this.binaryInput.getText().toString(), 10);

        // Disable send elements until sent
        this.setSendElementsEnabled(false);

        // Generate binary data
        final byte[] bytes = new byte[length * 1024];

        // Send message
        Objects.requireNonNull(this.chat)
            .send(ByteBuffer.wrap(bytes))
            .thenRun(() -> this.runOnUiThread(() -> {
                // Re-enable send elements
                this.setSendElementsEnabled(true);

                // Show sent message
                final String message = "[" + length + " KiB binary data]";
                this.showMessage(R.color.colorMessageOut, message);
            }));
    }

    /**
     * Show key info.
     */
    @UiThread
    public void showKeyInfo(@NonNull final View view) throws CryptoException {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setTitle("Key Info");
        final KeyStore keyStore = new KeyStore(
            SignalingConnection.cryptoProvider, Config.PRIVATE_KEY);
        final String msg = "Public key: " +
                keyStore.getPublicKeyHex() +
                "\n\n" +
                "Private key: " +
                Config.PRIVATE_KEY +
                "\n\n" +
                "Trusted key: " +
                Config.TRUSTED_KEY +
                "\n\n" +
                "Server public key: " +
                Config.SERVER_KEY +
                "\n\n";
        builder.setMessage(msg);
        builder.setPositiveButton("OK", (dialogInterface, i) -> dialogInterface.dismiss());
        builder.create().show();
    }
}
