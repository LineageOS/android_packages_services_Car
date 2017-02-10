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

package android.car.hardware;

import android.annotation.SystemApi;
import android.car.CarApiUtil;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.content.Context;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;

/** API for monitoring car diagnostic data. */
/** @hide */
public final class CarDiagnosticManager implements CarManagerBase {
    public static final int FRAME_TYPE_FLAG_LIVE = 0;
    public static final int FRAME_TYPE_FLAG_FREEZE = 1;

    private final ICarDiagnostic mService;

    /** Handles call back into projected apps. */
    private final Handler mHandler;
    private final Callback mHandlerCallback = new Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            //TODO(egranata): dispatch diagnostic messages to listeners
            return true;
        }
    };

    public CarDiagnosticManager(IBinder service, Context context, Handler handler) {
        mService = ICarDiagnostic.Stub.asInterface(service);
        mHandler = new Handler(handler.getLooper(), mHandlerCallback);
    }

    @Override
    public void onCarDisconnected() {}

    /** Listener for diagnostic events. Callbacks are called in the Looper context. */
    public interface OnDiagnosticEventListener {
        /**
         * Called when there is a diagnostic event from the car.
         *
         * @param carDiagnosticEvent
         */
        void onDiagnosticEvent(final CarDiagnosticManager manager,
                final CarDiagnosticEvent carDiagnosticEvent);
    }

    // ICarDiagnostic forwards

    /**
     * Register a new diagnostic events listener, or update an existing registration.
     * @param frameType
     * @param rate
     * @param listener
     * @return true if registration successfully occurs
     * @throws CarNotConnectedException
     */
    public boolean registerOrUpdateDiagnosticListener(int frameType, int rate,
            ICarDiagnosticEventListener listener) throws CarNotConnectedException {
        try {
            return mService.registerOrUpdateDiagnosticListener(frameType, rate, listener);
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
        return false;
    }

    /**
     * Retrieve the most-recently acquired live frame data from the car.
     * @return
     * @throws CarNotConnectedException
     */
    public CarDiagnosticEvent getLatestLiveFrame() throws CarNotConnectedException {
        try {
            return mService.getLatestLiveFrame();
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
        return null;
    }

    /**
     * Return the list of the timestamps for which a freeze frame is currently stored.
     * @return
     * @throws CarNotConnectedException
     */
    public long[] getFreezeFrameTimestamps() throws CarNotConnectedException {
        try {
            return mService.getFreezeFrameTimestamps();
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
        return new long[]{};
    }

    /**
     * Retrieve the freeze frame event data for a given timestamp, if available.
     * @param timestamp
     * @return
     * @throws CarNotConnectedException
     */
    public CarDiagnosticEvent getFreezeFrame(long timestamp) throws CarNotConnectedException {
        try {
            return mService.getFreezeFrame(timestamp);
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
        return null;
    }

    /**
     * Clear the freeze frame information from vehicle memory at the given timestamps.
     * @param timestamps
     * @return
     * @throws CarNotConnectedException
     */
    public boolean clearFreezeFrames(long... timestamps) throws CarNotConnectedException {
        try {
            return mService.clearFreezeFrames(timestamps);
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
        return false;
    }

    /**
     * Unregister a diagnostic events listener, such that the listener will stop receiveing events.
     * This only removes the registration for one type of frame, such that if a listener is
     * receiving events for multiple types of frames, it will keep receiving them for types other
     * than the given frameType.
     * @param frameType
     * @param listener
     * @throws CarNotConnectedException
     */
    public void unregisterDiagnosticListener(int frameType,
            ICarDiagnosticEventListener listener) throws CarNotConnectedException {
        try {
            mService.unregisterDiagnosticListener(frameType, listener);
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
    }
}
