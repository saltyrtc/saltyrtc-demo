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
 * Represents the fragmentation/reassembly mode used for a data transport:
 *
 * - reliable/ordered only adds a single byte header to each chunk, and can be
 *   reassembled more efficiently.
 * - unreliable/unordered adds a 9 byte header, and reassembling is usually
 *   more expensive.
 *
 * Obviously, the chosen mode should depend on the data transport's
 * characteristics (i.e. whether it's reliable/ordered or not).
 */
public enum ChunkMode {
    RELIABLE_ORDERED,
    UNRELIABLE_UNORDERED;

    @Nullable public static ChunkMode fromString(String string) {
        switch (string) {
            case "reliable-ordered":
                return RELIABLE_ORDERED;
            case "unreliable-unordered":
                return UNRELIABLE_UNORDERED;
            default:
                return null;
        }
    }

    @Override
    @NonNull public String toString() {
        switch (this) {
            case RELIABLE_ORDERED:
                return "reliable-ordered";
            case UNRELIABLE_UNORDERED:
                return "unreliable-unordered";
            default:
                throw new RuntimeException("Invalid mode");
        }
    }
}
