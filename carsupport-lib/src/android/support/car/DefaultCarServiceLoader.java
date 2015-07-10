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
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * Default CarServiceLoader for system with built-in car service.
 * @hide
 */
public class DefaultCarServiceLoader extends CarServiceLoader {

    private static final String CAR_SERVICE_PACKAGE = "com.android.car";

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            getConnectionListener().onServiceConnected(name, service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // unbind explicitly here.
            disconnect();
            getConnectionListener().onServiceDisconnected(name);
        }
    };

    public DefaultCarServiceLoader(Context context, ServiceConnectionListener listener) {
        super(context, listener);
    }

    @Override
    public void connect() throws IllegalStateException {
        startCarService();
    }

    @Override
    public void disconnect() {
        getContext().unbindService(mServiceConnection);
    }

    private void startCarService() {
        Intent intent = new Intent();
        intent.setPackage(CAR_SERVICE_PACKAGE);
        intent.setAction(Car.CAR_SERVICE_INTERFACE_NAME);
        getContext().startService(intent);
        getContext().bindService(intent, mServiceConnection, 0);
    }
}
