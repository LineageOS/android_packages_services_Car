/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.car.encryptionrunner;

import android.annotation.Nullable;

/**
 * During an {@link EncryptionRunner} handshake process, these are the messages returned as part
 * of each step.
 */
public class HandshakeMessage {

    private final boolean mHandShakeComplete;
    private final Key mKey;
    private final byte[] mNextMessage;

    /**
     * @return Returns a builder for {@link HandshakeMessage}.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Use the builder;
     */
    private HandshakeMessage(
            boolean handShakeComplete,
            @Nullable Key key,
            @Nullable byte[] nextMessage) {
        mHandShakeComplete = handShakeComplete;
        mKey = key;
        mNextMessage = nextMessage;
    }

    /**
     * Returns the next message to send in a handshake.
     */
    @Nullable
    public byte[] getNextMessage() {
        return mNextMessage == null ? null : mNextMessage.clone();
    }

    /**
     * Returns true if the handshake is complete.
     */
    public boolean isHandShakeComplete() {
        return mHandShakeComplete;
    }

    /**
     * Returns the encryption key that can be used to encrypt data.
     */
    @Nullable
    public Key getKey() {
        return mKey;
    }

    static class Builder {
        boolean mHandshakeComplete;
        Key mKey;
        byte[] mNextMessage;

        Builder setHandshakeComplete(boolean handshakeComplete) {
            mHandshakeComplete = handshakeComplete;
            return this;
        }

        Builder setKey(Key key) {
            mKey = key;
            return this;
        }

        Builder setNextMessage(byte[] nextMessage) {
            mNextMessage = nextMessage == null ? null : nextMessage.clone();
            return this;
        }

        HandshakeMessage build() {
            return new HandshakeMessage(mHandshakeComplete, mKey, mNextMessage);
        }
    }
}
