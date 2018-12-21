/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EncryptionRunnerTest {

    private static final byte[] sTestData = "test data".getBytes();

    @Test
    public void happyFlow() throws Exception {
        // This performs a handshake and then sends an "encrypted" message back and forth.
        // Any encryption runner should be able to do this.
        // Right now just using the dummy runner, when we have a real runner we can extract this
        // method or just have the factory create a real runner.
        EncryptionRunner clientRunner = EncryptionRunnerFactory.newDummyRunner();
        EncryptionRunner serverRunner = EncryptionRunnerFactory.newDummyRunner();
        HandshakeMessage initialClientMessage = clientRunner.initHandshake();

        assertThat(initialClientMessage.isHandShakeComplete()).isFalse();
        assertThat(initialClientMessage.getKey()).isNull();
        assertThat(initialClientMessage.getNextMessage()).isNotNull();

        HandshakeMessage initialServerMessage =
                serverRunner.respondToInitRequest(initialClientMessage.getNextMessage());

        assertThat(initialServerMessage.isHandShakeComplete()).isFalse();
        assertThat(initialServerMessage.getKey()).isNull();
        assertThat(initialServerMessage.getNextMessage()).isNotNull();

        HandshakeMessage clientMessage =
                clientRunner.continueHandshake(initialServerMessage.getNextMessage());

        assertThat(clientMessage.isHandShakeComplete()).isTrue();
        assertThat(clientMessage.getKey()).isNotNull();
        assertThat(clientMessage.getNextMessage()).isNotNull();

        HandshakeMessage serverMessage =
                serverRunner.continueHandshake(clientMessage.getNextMessage());

        assertThat(serverMessage.isHandShakeComplete()).isTrue();
        assertThat(serverMessage.getKey()).isNotNull();
        assertThat(serverMessage.getNextMessage()).isNull();

        assertThat(serverRunner.decryptData(
                serverMessage.getKey(),
                clientRunner.encryptData(clientMessage.getKey(), sTestData))).isEqualTo(sTestData);
        assertThat(clientRunner.decryptData(
                clientMessage.getKey(),
                serverRunner.encryptData(serverMessage.getKey(), sTestData))).isEqualTo(sTestData);
    }

}
