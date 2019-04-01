/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

public final class Config {
    @NonNull public static String HOST = "server.saltyrtc.org";
    public static int PORT = 443;

    @NonNull public static String STUN_SERVER = "stun.l.google.com:19302";
    @Nullable public static String TURN_SERVER = null;
    @Nullable public static String TURN_USER = null;
    @Nullable public static String TURN_PASS = null;

    @NonNull public static String PRIVATE_KEY = "c41df741435bb144edcd429d1d8e86c5e0e24ccceff87ec5e6647525c2d52077";
    @NonNull public static String TRUSTED_KEY = "424280166304526b4a2874a2270d091071fcc5c98959f7d4718715626df26204";
    @NonNull public static String SERVER_KEY = "f77fe623b6977d470ac8c7bf7011c4ad08a1d126896795db9d2b4b7a49ae1045";
}
