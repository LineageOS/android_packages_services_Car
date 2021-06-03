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

package com.android.car.telemetry;

import android.os.Bundle;

/**
 * Buffers data passing from publishers to subscribers. There is one channel per script receptor.
 *
 * TODO(b/187525360, b/187743369): make it a class or create an impl class for this.
 */
public interface Channel {
    /**
     * Buffers the given data.
     *
     * TODO(b/189241508): Use ScriptExecutor supported data structure
     */
    void push(Bundle message);

    /** Returns the publisher configuration for this channel. */
    TelemetryProto.Publisher getPublisherConfig();

    // TODO(b/187525360, b/187743369): Add other methods to check/get data from the channel.
}
