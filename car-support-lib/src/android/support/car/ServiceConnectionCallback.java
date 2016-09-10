/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.support.car;

import android.content.ComponentName;
import android.content.ServiceConnection;

/**
 * Enables applications to know when the {@link Car} Service has been connected or disconnected and
 * when (or if) these services are available. Analogous to the {@link ServiceConnection} class.
 * Calls are made from the main thread.
 */
public abstract class ServiceConnectionCallback {
    /**
     * Called when the Car Service and all APIs are available.
     */
    public abstract void onServiceConnected();

    /**
     * Car Service disconnected for reasons such as a Car Service crash or user disconnection.
     * Client should re-connect to the Car Service as all previously-created Car*Managers
     * are invalid after disconnection.
     */
    public abstract void onServiceDisconnected();

    /**
     * Car Service is temporarily suspended for reasons such as an update.
     * @param cause The reason for the suspension. Currently unused.
     */
    public abstract void onServiceSuspended(int cause);

    /**
     * Connection failed. Client may retry.
     * @param cause The reason for the failure. Currently unused.
     */
    public abstract void onServiceConnectionFailed(int cause);
}
