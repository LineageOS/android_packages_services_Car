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

package com.android.car.telemetry.publisher;

import android.annotation.NonNull;

import com.android.internal.util.Preconditions;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for computing hash code.
 *
 * <p>Most of the methods are copied from {@code external/guava/}.
 */
public class HashUtils {

    /**
     * Returns the hash code of the given string using SHA-256 algorithm. Returns only the first
     * 8 bytes if the hash code, as SHA-256 is uniformly distributed.
     */
    static long sha256(@NonNull String data) {
        try {
            return asLong(MessageDigest.getInstance("SHA-256").digest(data.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            // unreachable
            throw new RuntimeException("SHA-256 algorithm not found.", e);
        }
    }

    /**
     * Returns the first eight bytes of {@code hashCode}, converted to a {@code long} value in
     * little-endian order.
     *
     * <p>Copied from Guava's {@code HashCode#asLong()}.
     *
     * @throws IllegalStateException if {@code hashCode bytes < 8}
     */
    private static long asLong(byte[] hashCode) {
        Preconditions.checkState(hashCode.length >= 8, "requires >= 8 bytes (it only has %s bytes)",
                hashCode.length);
        long retVal = (hashCode[0] & 0xFF);
        for (int i = 1; i < Math.min(hashCode.length, 8); i++) {
            retVal |= (hashCode[i] & 0xFFL) << (i * 8);
        }
        return retVal;
    }
}
