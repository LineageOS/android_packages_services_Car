/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.car.occupantconnection;

import android.annotation.NonNull;
import android.text.TextUtils;

import java.util.Objects;

/** A class used to identify a receiver endpoint. */
final class ReceiverEndpointToken {

    /** Indicates which client this endpoint is in. */
    public final ClientToken clientToken;
    /** The ID of this endpoint. The ID is specified by this endpoint. */
    public final String receiverEndpointId;

    ReceiverEndpointToken(@NonNull ClientToken clientToken, @NonNull String receiverEndpointId) {
        this.clientToken = Objects.requireNonNull(clientToken, "clientToKen cannot be null");
        this.receiverEndpointId =
                Objects.requireNonNull(receiverEndpointId, "receiverEndpointId cannot be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof ReceiverEndpointToken) {
            ReceiverEndpointToken other = (ReceiverEndpointToken) o;
            return clientToken.equals(other.clientToken)
                    && TextUtils.equals(receiverEndpointId, other.receiverEndpointId);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientToken, receiverEndpointId);
    }

    @Override
    public String toString() {
        return "ReceiverEndpointToken[clientToken=" + clientToken
                + ", receiverEndpointId=" + receiverEndpointId + "]";
    }
}
