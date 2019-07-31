/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.utils;

import com.goterl.lazycode.lazysodium.LazySodiumAndroid;
import com.goterl.lazycode.lazysodium.SodiumAndroid;
import com.goterl.lazycode.lazysodium.interfaces.Box;
import com.goterl.lazycode.lazysodium.interfaces.SecretBox;
import org.saltyrtc.client.annotations.NonNull;
import org.saltyrtc.client.crypto.CryptoException;
import org.saltyrtc.client.crypto.CryptoInstance;
import org.saltyrtc.client.crypto.CryptoProvider;

/**
 * An implementation of the CryptoProvider interface for lazysodium-java
 * (https://github.com/terl/lazysodium-java).
 *
 * Note that this includes precompiled binaries by the lazysodium-developers.
 * In production, you should probably use your own implementation of the
 * `CryptoProvider` interface using the library of your choice.
 */
public class LazysodiumCryptoProvider implements CryptoProvider {
    final private static SodiumAndroid sodium = new SodiumAndroid();

    @Override
    public void generateKeypair(
        @NonNull byte[] publickey,
        @NonNull byte[] privatekey
    ) throws CryptoException {
        // Verify key lengths
        if (publickey.length != CryptoProvider.PUBLICKEYBYTES) {
            throw new CryptoException("Invalid public key buffer length");
        }
        if (privatekey.length != CryptoProvider.PRIVATEKEYBYTES) {
            throw new CryptoException("Invalid private key buffer length");
        }

        // Generate keypair
        final Box.Native lazySodium = new LazySodiumAndroid(sodium);
        final boolean success = lazySodium.cryptoBoxKeypair(publickey, privatekey);
        if (!success) {
            throw new CryptoException("Could not generate keypair");
        }
    }

    @NonNull
    @Override
    public byte[] derivePublicKey(@NonNull byte[] privateKey) throws CryptoException {
        // Verify key lengths
        if (privateKey.length != CryptoProvider.PRIVATEKEYBYTES) {
            throw new CryptoException("Invalid private key length");
        }

        // Derive public key from private key
        final LazySodiumAndroid lazySodium = new LazySodiumAndroid(sodium);
        byte[] publicKey = new byte[CryptoProvider.PUBLICKEYBYTES];
        final boolean success = lazySodium.cryptoScalarMultBase(publicKey, privateKey);
        if (!success) {
            throw new CryptoException("Could not derive public key");
        }

        return publicKey;
    }

    @NonNull
    @Override
    public byte[] symmetricEncrypt(
        @NonNull byte[] input,
        @NonNull byte[] key,
        @NonNull byte[] nonce
    ) throws CryptoException {
        // Verify key lengths
        if (key.length != CryptoProvider.SYMMKEYBYTES) {
            throw new CryptoException("Invalid key length");
        }
        if (nonce.length != CryptoProvider.NONCEBYTES) {
            throw new CryptoException("Invalid nonce length");
        }

        // Encrypt
        final SecretBox.Native lazySodium = new LazySodiumAndroid(sodium);
        final byte[] output = new byte[input.length + CryptoProvider.BOXOVERHEAD];
        final boolean success = lazySodium.cryptoSecretBoxEasy(
            output, input, input.length, nonce, key);
        if (!success) {
            throw new CryptoException("Could not encrypt data");
        }

        return output;
    }

    @NonNull
    @Override
    public byte[] symmetricDecrypt(
        @NonNull byte[] input,
        @NonNull byte[] key,
        @NonNull byte[] nonce
    ) throws CryptoException {
        // Verify key lengths
        if (key.length != CryptoProvider.SYMMKEYBYTES) {
            throw new CryptoException("Invalid key length");
        }
        if (nonce.length != CryptoProvider.NONCEBYTES) {
            throw new CryptoException("Invalid nonce length");
        }

        // Decrypt
        final SecretBox.Native lazySodium = new LazySodiumAndroid(sodium);
        final byte[] decrypted = new byte[input.length - CryptoProvider.BOXOVERHEAD];
        final boolean success = lazySodium.cryptoSecretBoxOpenEasy(
            decrypted, input, input.length, nonce, key);
        if (!success) {
            throw new CryptoException("Could not decrypt data");
        }

        return decrypted;
    }

    @NonNull
    @Override
    public CryptoInstance getInstance(
        @NonNull byte[] ownPrivateKey,
        @NonNull byte[] otherPublicKey
    ) throws CryptoException {
        return new LazysodiumCryptoInstance(sodium, ownPrivateKey, otherPublicKey);
    }
}
