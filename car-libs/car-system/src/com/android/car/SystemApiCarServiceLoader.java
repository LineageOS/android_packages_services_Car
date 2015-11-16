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
package com.android.car;

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.support.car.CarLibLog;
import android.support.car.CarManagerBase;
import android.support.car.DefaultCarServiceLoader;
import android.support.car.ServiceConnectionListener;
import android.util.Log;

import com.android.car.hardware.radio.CarRadioManager;
import com.android.car.hardware.radio.ICarRadio;

public class SystemApiCarServiceLoader extends DefaultCarServiceLoader {

    public SystemApiCarServiceLoader(Context context, ServiceConnectionListener listener,
            Looper looper) {
        super(context, listener, looper);
    }

    @Override
    public CarManagerBase createCarManager(String serviceName, IBinder binder) {
        //TODO populate system only Car*Managers
        CarManagerBase manager = null;
        switch (serviceName) {
            case CarSystem.RADIO_SERVICE:
                manager =
                    new CarRadioManager(
                        getContext(), ICarRadio.Stub.asInterface(binder), getLooper());
                if (manager == null) {
                    Log.d(CarLibLog.TAG_CAR, "Radio manager could not be loaded!");
                }
                break;
        }
        if (manager == null) {
            return super.createCarManager(serviceName, binder);
        }
        return manager;
    }
}
