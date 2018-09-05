/*
 * Copyright (c) 2016-2018 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.AnyThread;
import android.support.annotation.ColorRes;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.util.Log;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.saltyrtc.client.exceptions.CryptoFailedException;
import org.saltyrtc.client.exceptions.InvalidKeyException;
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.vendor.com.neilalexander.jnacl.NaCl;

import java.util.Random;

public class BenchmarkActivity extends Activity {

	private static final String LOG_TAG = BenchmarkActivity.class.getSimpleName();

	private Random random;
	private LinearLayout messagesLayout;
	private ScrollView messagesScrollView;
	private Button buttonRunBenchmarks;

	@Nullable
	private AsyncTask<Void, Void, Void> benchmarkTask;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(R.string.benchmarks);
		final ActionBar actionBar = getActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
		setContentView(R.layout.activity_benchmarks);

		// Get views
		this.messagesLayout = findViewById(R.id.messages);
		this.messagesScrollView = findViewById(R.id.messages_scroll);
		this.buttonRunBenchmarks = findViewById(R.id.button_run_benchmarks);

		this.random = new Random();
	}

	@Override
	protected void onDestroy() {
		Log.d(LOG_TAG, "Cancelling benchmark task");
		this.benchmarkTask.cancel(true);
		super.onDestroy();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				this.finish();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	/**
	 * Show message and scroll to bottom.
	 */
	@UiThread
	private void showMessage(String text, @ColorRes int colorResource) {
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

		BenchmarkActivity.this.messagesLayout.addView(view);
		BenchmarkActivity.this.messagesScrollView.post(
			() -> BenchmarkActivity.this.messagesScrollView.fullScroll(ScrollView.FOCUS_DOWN)
		);
	}

	@UiThread
	@SuppressLint("StaticFieldLeak")
	public void runBenchmarks(View view) {
		Log.d(LOG_TAG, "Running benchmarks in background thread...");
		if (this.benchmarkTask != null) {
			Log.e(LOG_TAG, "Benchmark task is already defined!");
			return;
		}
		this.benchmarkTask = new AsyncTask<Void, Void, Void>() {
			@Override
			protected void onPreExecute() {
				BenchmarkActivity.this.buttonRunBenchmarks.setEnabled(false);
				super.onPreExecute();
			}

			@Override
			protected Void doInBackground(Void... voids) {
				try {
					runOnUiThread(() -> showMessage("Starting benchmark 1...", R.color.colorMessageIn));
					if (!isCancelled()) { benchmark1EncryptKeyStore(1, 64); }
					if (!isCancelled()) { benchmark1EncryptKeyStore(1, 1024 * 10); }
					if (!isCancelled()) { benchmark1EncryptKeyStore(10, 1024); }
				} catch (CryptoFailedException | InvalidKeyException e) {
					Log.e(LOG_TAG, "Crypto error", e);
					e.printStackTrace();
				}
				return null;
			}

			@Override
			protected void onPostExecute(Void aVoid) {
				BenchmarkActivity.this.buttonRunBenchmarks.setEnabled(true);
				BenchmarkActivity.this.benchmarkTask = null;
				showMessage("Benchmarks done!", R.color.colorMessageIn);
				super.onPostExecute(aVoid);
			}

			@Override
			protected void onCancelled() {
				Log.d(LOG_TAG, "Async task cancelled");
				BenchmarkActivity.this.benchmarkTask = null;
				super.onCancelled();
			}
		};
		this.messagesLayout.removeAllViews();
		this.benchmarkTask.execute();
	}

	/**
	 * Encrypt the specified number of megabytes of random data using the classic `KeyStore`.
	 */
	@AnyThread
	private void benchmark1EncryptKeyStore(int count, int kilobytes) throws CryptoFailedException, InvalidKeyException {
		Log.d(LOG_TAG, "Benchmark 1: Start: Encrypt " + count + "x" + kilobytes + " KiB");

		// Gerate random plaintext
		final byte[] plaintext = new byte[1024 * kilobytes];
		final byte[] nonce = new byte[NaCl.NONCEBYTES];
		final byte[] otherKey = new byte[NaCl.PUBLICKEYBYTES];
		this.random.nextBytes(plaintext);
		this.random.nextBytes(nonce);
		this.random.nextBytes(otherKey);

		// Create keystore
		final KeyStore keyStore = new KeyStore();

		// Encrypt
		final long t1 = System.nanoTime();
		for (int i = 0; i < count; i++) {
			keyStore.encrypt(plaintext, nonce, otherKey);
		}
		final long t2 = System.nanoTime();

		Log.d(LOG_TAG, "Benchmark 1: End: Encrypted " + count + "x" + kilobytes + " KiB");

		runOnUiThread(
			() -> showMessage(
				"--> Encrypting " + count + "x" + kilobytes + " KiB took " + ((t2 - t1) / 1000 / 1000) + " ms",
				R.color.colorMessageOut
			)
		);
	}
}
