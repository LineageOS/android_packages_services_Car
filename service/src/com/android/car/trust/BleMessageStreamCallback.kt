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

package com.android.car.trust

import java.util.UUID

/**
 * The callback that will be notified of various actions that occur in a [BleMessageStream].
 */
internal interface BleMessageStreamCallback {
    /**
     * Called if an error was encountered during a processing of a client message.
     *
     * @param uuid The UUID of the characteristic that the client message was retrieved from.
     */
    fun onMessageReceivedError(uuid: UUID)

    /**
     * Called when a complete message is received from the client.
     *
     * @param message The complete message.
     * @param uuid The UUID of the characteristic that the client message was retrieved from.
     */
    fun onMessageReceived(message: ByteArray, uuid: UUID)

    /**
     * Called if there was an error during a write of a message to the stream.
     */
    fun onWriteMessageError()
}
