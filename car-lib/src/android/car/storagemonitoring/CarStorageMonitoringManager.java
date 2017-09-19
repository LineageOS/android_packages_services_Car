/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.car.storagemonitoring;

import android.annotation.SystemApi;
import android.car.CarApiUtil;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * API for retrieving information and metrics about the flash storage.
 *
 * @hide
 */
@SystemApi
public final class CarStorageMonitoringManager implements CarManagerBase {
    private final ICarStorageMonitoring mService;

    public static final int PRE_EOL_INFO_UNKNOWN = 0;
    public static final int PRE_EOL_INFO_NORMAL = 1;
    public static final int PRE_EOL_INFO_WARNING = 2;
    public static final int PRE_EOL_INFO_URGENT = 3;

    /** @hide */
    public CarStorageMonitoringManager(IBinder service) {
        mService = ICarStorageMonitoring.Stub.asInterface(service);
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
    }

    // ICarStorageMonitoring forwards

    /**
     * This method returns the value of the "pre EOL" indicator for the flash storage
     * as retrieved during the current boot cycle.
     *
     * It will return either PRE_EOL_INFO_UNKNOWN if the value can't be determined,
     * or one of PRE_EOL_INFO_{NORMAL|WARNING|URGENT} depending on the device state.
     * @return
     * @throws CarNotConnectedException
     */
    public int getPreEolIndicatorStatus() throws CarNotConnectedException {
        try {
            return mService.getPreEolIndicatorStatus();
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
        return PRE_EOL_INFO_UNKNOWN;
    }
}

