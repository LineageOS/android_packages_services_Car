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
 * Allows apps to know when the {@link Car} service has been connected or disconnected.  This
 * allows application to know when and if these services are available.  This is analogous to the
 * {@link ServiceConnection} class but for {@link Car}.
 */
public abstract class ServiceConnectionCallback {
    public abstract void onServiceConnected(ComponentName name);
    public abstract void onServiceDisconnected(ComponentName name);
    //TODO define cause values
    public abstract void onServiceSuspended(int cause);
    //TODO define cause values
    public abstract void onServiceConnectionFailed(int cause);
}
