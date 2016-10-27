/**
 * Copyright (c) 2016 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */

function hexToU8a(hexstring) {
    let array, i, j, k, ref;
    j = 0;
    if (hexstring.length % 2 == 1) {
        hexstring = '0' + hexstring;
    }
    array = new Uint8Array(hexstring.length / 2);
    for (i = k = 0, ref = hexstring.length; k <= ref; i = k += 2) {
        array[j++] = parseInt(hexstring.substr(i, 2), 16);
    }
    return array;
}

function ready(fn) {
    if (document.readyState != 'loading'){
        fn();
    } else {
        document.addEventListener('DOMContentLoaded', fn);
    }
}
