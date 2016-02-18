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

import android.os.Looper;

/**
 * Implementation of {@link CarAppContextManager} for embedded.
 * @hide
 */
public class CarAppContextManagerEmbedded extends CarAppContextManager {

    private final android.car.CarAppContextManager mManager;
    private AppContextChangeListenerProxy mListener;

    /**
     * @hide
     */
    CarAppContextManagerEmbedded(Object manager) {
        mManager = (android.car.CarAppContextManager) manager;
    }

    @Override
    public void registerContextListener(AppContextChangeListener listener, int contextFilter) {
        if (listener == null) {
            throw new IllegalArgumentException("null listener");
        }
        AppContextChangeListenerProxy proxy = new AppContextChangeListenerProxy(listener);
        synchronized(this) {
            mListener = proxy;
        }
        mManager.registerContextListener(proxy, contextFilter);
    }

    @Override
    public void unregisterContextListener() {
        synchronized(this) {
            mListener = null;
        }
        mManager.unregisterContextListener();
    }

    @Override
    public int getActiveAppContexts() {
        return mManager.getActiveAppContexts();
    }

    @Override
    public boolean isOwningContext(int context) {
        return mManager.isOwningContext(context);
    }

    @Override
    public void setActiveContexts(int contexts) throws IllegalStateException, SecurityException {
        mManager.setActiveContexts(contexts);
    }

    @Override
    public void resetActiveContexts(int contexts) {
        mManager.resetActiveContexts(contexts);
    }

    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    private static class AppContextChangeListenerProxy implements
            android.car.CarAppContextManager.AppContextChangeListener {

        private final AppContextChangeListener mListener;

        public AppContextChangeListenerProxy(AppContextChangeListener listener) {
            mListener = listener;
        }

        @Override
        public void onAppContextChange(int activeContexts) {
            mListener.onAppContextChange(activeContexts);
        }

        @Override
        public void onAppContextOwnershipLoss(int context) {
            mListener.onAppContextOwnershipLoss(context);
        }
    }
}
