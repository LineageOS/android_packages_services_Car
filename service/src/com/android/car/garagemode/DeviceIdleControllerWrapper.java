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

package com.android.car.garagemode;

import android.content.Context;
import android.os.IDeviceIdleController;
import android.os.IMaintenanceActivityListener;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.car.garagemode.Controller.CommandHandler;
import com.android.internal.util.Preconditions;

class DeviceIdleControllerWrapper {
    private static final Logger LOG = new Logger("DeviceIdleControllerWrapper");

    private IDeviceIdleController mDeviceIdleController;
    private final CommandHandler mCommandHandler;
    protected DeviceIdleControllerWrapper.MaintenanceActivityListener mMaintenanceActivityListener =
            new DeviceIdleControllerWrapper.MaintenanceActivityListener();

    DeviceIdleControllerWrapper(CommandHandler commandHandler) {
        Preconditions.checkNotNull(commandHandler);
        mCommandHandler = commandHandler;
    }

    public boolean registerListener() {
        LOG.d("Registering listener to DeviceIdleController ...");

        // Acquiring DeviceIdleController service
        mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));

        try {
            mDeviceIdleController.registerMaintenanceActivityListener(mMaintenanceActivityListener);
        } catch (RemoteException ex) {
            LOG.e("Unable to register listener to DeviceIdleController service", ex);
            return false;
        }

        LOG.d("Successfully registered listener to DeviceIdleController service");

        return true;
    }

    public void unregisterListener() {
        LOG.d("Trying to stop listening to DeviceIdleController");
        try {
            if (mDeviceIdleController != null) {
                mDeviceIdleController
                        .unregisterMaintenanceActivityListener(mMaintenanceActivityListener);
            }
        } catch (RemoteException ex) {
            LOG.e("Filed to unregister DeviceIdleController listener", ex);
            return;
        }
        LOG.d("Successfully unregistered DeviceIdleController listener");
    }

    public void onMaintenanceActivityChanged(boolean active) {
        mCommandHandler.sendMessage(mCommandHandler.obtainMessage(
                Controller.Commands.SET_MAINTENANCE_ACTIVITY.ordinal(), active ? 1 : 0, 0));
    }

    private class MaintenanceActivityListener extends IMaintenanceActivityListener.Stub {
        @Override
        public void onMaintenanceActivityChanged(boolean active) {
            DeviceIdleControllerWrapper.this.onMaintenanceActivityChanged(active);
        }
    }
}
