/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.car.hardware.camera;

import android.annotation.SystemApi;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;

/**
 * API for controlling camera system in cars
 */
@SystemApi
public class CarCamera {
    public final static String TAG = CarCamera.class.getSimpleName();
    public final int mCameraType;
    private final ICarCamera mService;

    public CarCamera(ICarCamera service, int cameraType) {
        mService = service;
        mCameraType = cameraType;
    }

    public int getCapabilities() {
        int capabilities;
        try {
            capabilities = mService.getCapabilities(mCameraType);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getCapabilities", e);
            capabilities = 0;
        }
        return capabilities;
    }

    public Rect getCameraCrop() {
        Rect rect;
        try {
            rect = mService.getCameraCrop(mCameraType);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getCameraCrop", e);
            rect = null;
        }
        return rect;
    }

    public void setCameraCrop(Rect rect) {
        try {
            mService.setCameraCrop(mCameraType, rect);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in setCameraCrop", e);
        }
    }

    public Rect getCameraPosition() {
        Rect rect;
        try {
            rect = mService.getCameraPosition(mCameraType);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getCameraPosition", e);
            rect = null;
        }
        return rect;
    }

    public void setCameraPosition(Rect rect) {
        try {
            mService.setCameraPosition(mCameraType, rect);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in setCameraPosition", e);
        }
    }

    public CarCameraState getCameraState() {
        CarCameraState state;
        try {
            state = mService.getCameraState(mCameraType);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getCameraState", e);
            state = null;
        }
        return state;
    }

    public void setCameraState(CarCameraState state) {
        try {
            mService.setCameraState(mCameraType, state);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in setCameraState", e);
        }
    }
}

