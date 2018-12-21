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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An ecnryption runnner that doesn't actually do encryption. Useful for debugging. Do not use in
 * production environments.
 */
class DummyEncryptionRunner implements EncryptionRunner {

    private static final String KEY = "key";
    private static final String INIT = "init";
    private static final String INIT_RESPONSE = "initResponse";
    private static final String CLIENT_RESPONSE = "clientResponse";
    public static final String PIN = "1234";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Mode.UNKNOWN, Mode.CLIENT, Mode.SERVER})
    private @interface Mode {

        int UNKNOWN = 0;
        int CLIENT = 1;
        int SERVER = 2;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({State.UNKNOWN, State.WAITING_FOR_RESPONSE, State.FINISHED})
    private @interface State {

        int UNKNOWN = 0;
        int WAITING_FOR_RESPONSE = 1;
        int FINISHED = 2;
    }

    @Mode
    private int mMode;
    @State
    private int mState;

    @Override
    public HandshakeMessage initHandshake() {
        mMode = Mode.CLIENT;
        mState = State.WAITING_FOR_RESPONSE;
        return HandshakeMessage.newBuilder()
                .setNextMessage(INIT.getBytes())
                .build();
    }

    @Override
    public HandshakeMessage respondToInitRequest(byte[] initializationRequest)
            throws HandshakeException {
        mMode = Mode.SERVER;
        if (!new String(initializationRequest).equals(INIT)) {
            throw new HandshakeException("Unexpected initialization request");
        }
        mState = State.WAITING_FOR_RESPONSE;
        return HandshakeMessage.newBuilder()
                .setNextMessage(INIT_RESPONSE.getBytes())
                .build();
    }

    @Override
    public HandshakeMessage continueHandshake(byte[] response) throws HandshakeException {
        if (mState != State.WAITING_FOR_RESPONSE) {
            throw new HandshakeException("not waiting for response but got one");
        }
        switch(mMode) {
            case Mode.SERVER:
                if (!CLIENT_RESPONSE.equals(new String(response))) {
                    throw new HandshakeException("unexpected response: " + new String(response));
                }
                mState = State.FINISHED;
                return HandshakeMessage.newBuilder()
                        .setHandshakeComplete(true)
                        .setKey(new DummyKey())
                        .build();
            case Mode.CLIENT:
                if (!INIT_RESPONSE.equals(new String(response))) {
                    throw new HandshakeException("unexpected response: " + new String(response));
                }
                mState = State.FINISHED;
                return HandshakeMessage.newBuilder()
                        .setHandshakeComplete(true)
                        .setKey(new DummyKey())
                        .setNextMessage(CLIENT_RESPONSE.getBytes())
                        .build();
            default:
                throw new IllegalStateException();
        }
    }

    @Override
    public Key keyOf(byte[] serialized) {
        return new DummyKey();
    }

    @Override
    public String getPin() {
        return PIN;
    }

    @Override
    public byte[] encryptData(Key key, byte[] data) {
        return data;
    }

    @Override
    public byte[] decryptData(Key key, byte[] encryptedData) {
        return encryptedData;
    }

    private class DummyKey implements Key {

        @Override
        public byte[] asBytes() {
            return KEY.getBytes();
        }
    }
}
