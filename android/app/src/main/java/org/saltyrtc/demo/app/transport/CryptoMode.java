/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.transport;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

/**
 * Represents the crypto mode used for a data transport:
 *
 * - `none` disables (additional) encryption/decryption.
 * - `chunk-then-encrypt` will lead to better performance and less memory
 *   pressure, since encrypting/decrypting small chunks is faster and uses less
 *   memory, if done on demand.
 * - `encrypt-then-chunk` results in high memory pressure and worse throughput,
 *   since all chunks need to be received and reassembled before they can be
 *   decrypted.
 */
public enum CryptoMode {
    NONE,
    CHUNK_THEN_ENCRYPT,
    ENCRYPT_THEN_CHUNK;

    @Nullable
    public static CryptoMode fromString(String string) {
        switch (string) {
            case "none":
                return NONE;
            case "chunk-then-encrypt":
                return CHUNK_THEN_ENCRYPT;
            case "encrypt-then-chunk":
                return ENCRYPT_THEN_CHUNK;
            default:
                return null;
        }
    }

    @Override
    @NonNull public String toString() {
        switch (this) {
            case NONE:
                return "none";
            case CHUNK_THEN_ENCRYPT:
                return "chunk-then-encrypt";
            case ENCRYPT_THEN_CHUNK:
                return "encrypt-then-chunk";
            default:
                throw new RuntimeException("Invalid mode");
        }
    }
}
