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

import android.car.Car;
import android.car.CarAppContextManager;
import android.car.cluster.NavigationRenderer;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.car.navigation.CarNavigationManager;
import android.car.navigation.ICarNavigation;
import android.car.navigation.ICarNavigationEventListener;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that will push navigation event to navigation renderer in instrument cluster.
 */
public class CarNavigationService extends ICarNavigation.Stub
        implements CarServiceBase {
    private static final String TAG = CarLog.TAG_NAV;

    private final List<CarNavigationEventListener> mListeners = new ArrayList<>();

    private CarNavigationInstrumentCluster mInstrumentClusterInfo = null;
    private final AppContextService mAppContextService;
    private final Context mContext;
    private final boolean mRendererAvailable;

    public CarNavigationService(Context context) {
        mContext = context;
        mAppContextService = (AppContextService) ICarImpl.getInstance(mContext)
                .getCarService(Car.APP_CONTEXT_SERVICE);
        mRendererAvailable = InstrumentClusterRendererLoader.isRendererAvailable(mContext);
    }

    @Override
    public void init() {
        Log.d(TAG, "init");
        // TODO: we need to obtain this infromation from CarInstrumentClusterService.
        mInstrumentClusterInfo = CarNavigationInstrumentCluster.createCluster(1000);
    }

    @Override
    public void release() {
        synchronized(mListeners) {
            mListeners.clear();
        }
    }

    @Override
    public void sendNavigationStatus(int status) {
        Log.d(TAG, "sendNavigationStatus, status: " + status);
        if (!checkRendererAvailable()) {
            return;
        }
        verifyNavigationContextOwner();

        if (status == CarNavigationManager.STATUS_ACTIVE) {
            getNavRenderer().onStartNavigation();
        } else if (status == CarNavigationManager.STATUS_INACTIVE
                || status == CarNavigationManager.STATUS_UNAVAILABLE) {
            getNavRenderer().onStopNavigation();
        } else {
            throw new IllegalArgumentException("Unknown navigation status: " + status);
        }
    }

    @Override
    public void sendNavigationTurnEvent(
            int event, String road, int turnAngle, int turnNumber, Bitmap image, int turnSide) {
        Log.d(TAG, "sendNavigationTurnEvent, event:" + event + ", turnAngle: " + turnAngle + ", "
                + "turnNumber: " + turnNumber + ", " + "turnSide: " + turnSide);
        if (!checkRendererAvailable()) {
            return;
        }
        verifyNavigationContextOwner();

        getNavRenderer().onNextTurnChanged(event, road, turnAngle, turnNumber, image, turnSide);
    }

    @Override
    public void sendNavigationTurnDistanceEvent(int distanceMeters, int timeSeconds) {
        Log.d(TAG, "sendNavigationTurnDistanceEvent, distanceMeters:" + distanceMeters + ", "
                + "timeSeconds: " + timeSeconds);
        if (!checkRendererAvailable()) {
            return;
        }
        verifyNavigationContextOwner();

        getNavRenderer().onNextTurnDistanceChanged(distanceMeters, timeSeconds);
    }

    @Override
    public boolean registerEventListener(ICarNavigationEventListener listener) {
        synchronized(mListeners) {
            if (findClientLocked(listener) == null) {
                CarNavigationEventListener eventListener =
                        new CarNavigationEventListener(listener);
                try {
                    listener.asBinder().linkToDeath(eventListener, 0);
                } catch (RemoteException e) {
                    Log.w(TAG, "Adding listener failed.");
                    return false;
                }
                mListeners.add(eventListener);

                // The new listener needs to be told the instrument cluster parameters.
                try {
                    // TODO: onStart and onStop methods might be triggered from vehicle HAL as well.
                    eventListener.listener.onInstrumentClusterStart(mInstrumentClusterInfo);
                } catch (RemoteException e) {
                    Log.e(TAG, "listener.onStart failed.");
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean unregisterEventListener(ICarNavigationEventListener listener) {
        CarNavigationEventListener client;
        synchronized (mListeners) {
            client = findClientLocked(listener);
        }
        return client != null && removeClient(client);
    }

    @Override
    public CarNavigationInstrumentCluster getInstrumentClusterInfo() {
        return mInstrumentClusterInfo;
    }

    @Override
    public boolean isInstrumentClusterSupported() {
        return mInstrumentClusterInfo != null;
    }

    private void verifyNavigationContextOwner() {
        if (!mAppContextService.isContextOwner(
                Binder.getCallingUid(),
                Binder.getCallingPid(),
                CarAppContextManager.APP_CONTEXT_NAVIGATION)) {
            throw new IllegalStateException(
                    "Client is not an owner of APP_CONTEXT_NAVIGATION.");
        }
    }

    private boolean removeClient(CarNavigationEventListener listener) {
        synchronized(mListeners) {
            for (CarNavigationEventListener currentListener : mListeners) {
                // Use asBinder() for comparison.
                if (currentListener == listener) {
                    currentListener.listener.asBinder().unlinkToDeath(currentListener, 0);
                    return mListeners.remove(currentListener);
                }
            }
        }
        return false;
    }

    private CarNavigationEventListener findClientLocked(
            ICarNavigationEventListener listener) {
        for (CarNavigationEventListener existingListener : mListeners) {
            if (existingListener.listener.asBinder() == listener.asBinder()) {
                return existingListener;
            }
        }
        return null;
    }


    private class CarNavigationEventListener implements IBinder.DeathRecipient {
        final ICarNavigationEventListener listener;

        public CarNavigationEventListener(ICarNavigationEventListener listener) {
            this.listener = listener;
        }

        @Override
        public void binderDied() {
            listener.asBinder().unlinkToDeath(this, 0);
            removeClient(this);
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        // TODO Auto-generated method stub
    }

    private NavigationRenderer getNavRenderer() {
        // TODO: implement InstrumentClusterService that will abstract cluster renderer.
        return InstrumentClusterRendererLoader.getRenderer(mContext).getNavigationRenderer();
    }

    private boolean checkRendererAvailable() {
        if (!mRendererAvailable) {
            Log.w(TAG, "Instrument cluster renderer is not available.");
        }
        return mRendererAvailable;
    }
}
