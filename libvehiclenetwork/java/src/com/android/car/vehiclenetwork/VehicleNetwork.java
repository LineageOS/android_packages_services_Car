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
package com.android.car.vehiclenetwork;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValues;

import java.lang.ref.WeakReference;

/**
 * System API to access Vehicle network. This is only for system services and applications should
 * not use this.
 */
public class VehicleNetwork {
    public interface VehicleNetworkListener {
        void onVehicleNetworkEvents(VehiclePropValues values);
    }

    public static final int NO_ERROR = 0;
    public static final int ERROR_UNKNOWN = -1;

    private static final String TAG = VehicleNetwork.class.getSimpleName();

    private final IVehicleNetwork mService;
    private final VehicleNetworkListener mListener;
    private final IVehicleNetworkListenerImpl mVehicleNetworkListener;
    private final EventHandler mEventHandler;

    public VehicleNetwork createVehicleNetwork(VehicleNetworkListener listener, Looper looper) {
        IVehicleNetwork service = IVehicleNetwork.Stub.asInterface(ServiceManager.getService(
                IVehicleNetwork.class.getCanonicalName()));
        if (service == null) {
            throw new RuntimeException("Vehicle network service not available");
        }
        return new VehicleNetwork(service, listener, looper);
    }

    private VehicleNetwork(IVehicleNetwork service, VehicleNetworkListener listener,
            Looper looper) {
        mService = service;
        mListener = listener;
        mEventHandler = new EventHandler(looper);
        mVehicleNetworkListener = new IVehicleNetworkListenerImpl(this);
    }

    public VehiclePropConfigs listProperties(int property) {
        try {
            VehiclePropConfigsParcelable parcelable = mService.listProperties(property);
            if (parcelable != null) {
                return parcelable.configs;
            }
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
        return null;
    }

    public int setProperty(VehiclePropValue value) {
        VehiclePropValueParcelable parcelable = new VehiclePropValueParcelable(value);
        try {
            int r = mService.setProperty(parcelable);
            return r;
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
        return ERROR_UNKNOWN;
    }

    public VehiclePropValue getProperty(int property) {
        try {
            VehiclePropValueParcelable parcelable = mService.getProperty(property);
            if (parcelable != null) {
                return parcelable.value;
            }
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
        return null;
    }

    public int subscribe(int property, float sampleRate) {
        try {
            int r = mService.subscribe(mVehicleNetworkListener, property, sampleRate);
            return r;
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
        return ERROR_UNKNOWN;
    }

    public void unsubscribe(int property) {
        try {
            mService.unsubscribe(mVehicleNetworkListener, property);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void handleRemoteException(RemoteException e) {
        throw new RuntimeException("Vehicle network service not working ", e);
    }

    private void handleVehicleNetworkEvents(VehiclePropValues values) {
        mEventHandler.notifyEvents(values);
    }

    private void doHandleVehicleNetworkEvents(VehiclePropValues values) {
        mListener.onVehicleNetworkEvents(values);
    }

    private class EventHandler extends Handler {
        private static final int MSG_EVENTS = 0;

        private EventHandler(Looper looper) {
            super(looper);
        }

        private void notifyEvents(VehiclePropValues values) {
            Message msg = obtainMessage(MSG_EVENTS, values);
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_EVENTS:
                    doHandleVehicleNetworkEvents((VehiclePropValues)msg.obj);
                    break;
                default:
                    Log.w(TAG, "unown message:" + msg.what, new RuntimeException());
                    break;
            }
        }
    }

    private static class IVehicleNetworkListenerImpl extends IVehicleNetworkListener.Stub {
        private final WeakReference<VehicleNetwork> mVehicleNetwork;

        private IVehicleNetworkListenerImpl(VehicleNetwork vehicleNewotk) {
            mVehicleNetwork = new WeakReference<VehicleNetwork>(vehicleNewotk);
        }

        @Override
        public void onVehicleNetworkEvents(VehiclePropValuesParcelable values) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork != null) {
                vehicleNetwork.handleVehicleNetworkEvents(values.values);
            }
        }
    }
}
