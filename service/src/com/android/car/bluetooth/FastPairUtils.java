/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.bluetooth;

import android.car.builtin.util.Slogf;
import android.util.Log;

class FastPairUtils {
    static final String TAG = FastPairUtils.class.getSimpleName();
    static final boolean DBG = Slogf.isLoggable("FastPair", Log.DEBUG);
    static final String THREAD_NAME = "FastPairProvider";

    private static final int BD_ADDR_LEN = 6;
    private static final int BD_UUID_LEN = 16;

    static byte[] getBytesFromAddress(String address) {
        int i, j = 0;
        byte[] output = new byte[BD_ADDR_LEN];

        for (i = 0; i < address.length(); i++) {
            if (address.charAt(i) != ':') {
                output[j] = (byte) Integer.parseInt(address.substring(i, i + 2), BD_UUID_LEN);
                j++;
                i++;
            }
        }
        return output;
    }
}
