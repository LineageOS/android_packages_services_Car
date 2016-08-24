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

package android.support.car;

import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of {@link CarAppFocusManager} for embedded.
 * @hide
 */
public class CarAppFocusManagerEmbedded extends CarAppFocusManager {

    private final android.car.CarAppFocusManager mManager;

    private final Map<AppFocusChangeListener, AppFocusChangeListenerProxy>
            mChangeListeners = new HashMap<>();
    private final Map<AppFocusOwnershipChangeListener, AppFocusOwnershipChangeListenerProxy>
            mOwnershipListeners = new HashMap<>();

    /**
     * @hide
     */
    CarAppFocusManagerEmbedded(Object manager) {
        mManager = (android.car.CarAppFocusManager) manager;
    }

    @Override
    public void registerFocusListener(AppFocusChangeListener listener, int appType)
            throws CarNotConnectedException {
        if (listener == null) {
            throw new IllegalArgumentException("null listener");
        }
        AppFocusChangeListenerProxy proxy;
        synchronized (this) {
            proxy = mChangeListeners.get(listener);
            if (proxy == null) {
                proxy = new AppFocusChangeListenerProxy(listener);
                mChangeListeners.put(listener, proxy);
            }
        }
        try {
            mManager.registerFocusListener(proxy, appType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void unregisterFocusListener(AppFocusChangeListener listener, int appType)
            throws CarNotConnectedException {
        AppFocusChangeListenerProxy proxy;
        synchronized (this) {
            proxy = mChangeListeners.get(listener);
            if (proxy == null) {
                return;
            }
        }
        try {
            mManager.unregisterFocusListener(proxy, appType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void unregisterFocusListener(AppFocusChangeListener listener)
            throws CarNotConnectedException {
        AppFocusChangeListenerProxy proxy;
        synchronized (this) {
            proxy = mChangeListeners.remove(listener);
            if (proxy == null) {
                return;
            }
        }
        try {
            mManager.unregisterFocusListener(proxy);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public boolean isOwningFocus(AppFocusOwnershipChangeListener listener, int appType)
            throws CarNotConnectedException {
        AppFocusOwnershipChangeListenerProxy proxy;
        synchronized (this) {
            proxy = mOwnershipListeners.get(listener);
            if (proxy == null) {
                return false;
            }
        }
        try {
            return mManager.isOwningFocus(proxy, appType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public int requestAppFocus(AppFocusOwnershipChangeListener ownershipListener, int appType)
            throws IllegalStateException, SecurityException, CarNotConnectedException {
        if (ownershipListener == null) {
            throw new IllegalArgumentException("null listener");
        }
        AppFocusOwnershipChangeListenerProxy proxy;
        synchronized (this) {
            proxy = mOwnershipListeners.get(ownershipListener);
            if (proxy == null) {
                proxy = new AppFocusOwnershipChangeListenerProxy(ownershipListener);
                mOwnershipListeners.put(ownershipListener, proxy);
            }
        }
        try {
            return mManager.requestAppFocus(proxy, appType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void abandonAppFocus(AppFocusOwnershipChangeListener ownershipListener, int appType)
            throws CarNotConnectedException {
        if (ownershipListener == null) {
            throw new IllegalArgumentException("null listener");
        }
        AppFocusOwnershipChangeListenerProxy proxy;
        synchronized (this) {
            proxy = mOwnershipListeners.get(ownershipListener);
            if (proxy == null) {
                return;
            }
        }
        try {
            mManager.abandonAppFocus(proxy, appType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void abandonAppFocus(AppFocusOwnershipChangeListener ownershipListener)
            throws CarNotConnectedException {
        if (ownershipListener == null) {
            throw new IllegalArgumentException("null listener");
        }
        AppFocusOwnershipChangeListenerProxy proxy;
        synchronized (this) {
            proxy = mOwnershipListeners.get(ownershipListener);
            if (proxy == null) {
                return;
            }
        }
        try {
            mManager.abandonAppFocus(proxy);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    private static class AppFocusChangeListenerProxy implements
            android.car.CarAppFocusManager.AppFocusChangeListener {

        private final AppFocusChangeListener mListener;

        AppFocusChangeListenerProxy(AppFocusChangeListener listener) {
            mListener = listener;
        }

        @Override
        public void onAppFocusChange(int appType, boolean active) {
            mListener.onAppFocusChange(appType, active);
        }
    }

    private static class AppFocusOwnershipChangeListenerProxy implements
            android.car.CarAppFocusManager.AppFocusOwnershipChangeListener {

        private final AppFocusOwnershipChangeListener mListener;

        AppFocusOwnershipChangeListenerProxy(AppFocusOwnershipChangeListener listener) {
            mListener = listener;
        }

        @Override
        public void onAppFocusOwnershipLoss(int focus) {
            mListener.onAppFocusOwnershipLoss(focus);
        }
    }
}
