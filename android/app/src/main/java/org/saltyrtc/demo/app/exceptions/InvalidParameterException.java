/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app.exceptions;

import android.support.annotation.NonNull;

/**
 * General exception for any kind of invalid parameters.
 */
public class InvalidParameterException extends Exception {
    public InvalidParameterException(@NonNull final String description) {
        super(description);
    }

    public InvalidParameterException(@NonNull final Throwable throwable) {
        super(throwable);
    }
}
