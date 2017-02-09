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

package com.android.car;

import android.car.Car;
import android.car.annotation.FutureFeature;
import android.car.vms.IOnVmsMessageReceivedListener;
import android.car.vms.IVmsSubscriberService;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.hal.VmsHalService;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * + Receives HAL updates by implementing VmsHalService.VmsHalListener.
 * + Offers subscriber/publisher services by implementing IVmsService.Stub.
 * TODO(antoniocortes): implement layer subscription logic (i.e. routing).
 */
@FutureFeature
public class VmsSubscriberService extends IVmsSubscriberService.Stub
        implements CarServiceBase, VmsHalService.VmsHalSubscriberListener {
    private static final boolean DBG = true;
    private static final String PERMISSION = Car.PERMISSION_VMS_SUBSCRIBER;
    private static final String TAG = "VmsSubscriberService";

    private final Context mContext;
    private final VmsHalService mHal;
    private final VmsListenerManager mMessageReceivedManager = new VmsListenerManager();

    /**
     * Keeps track of listeners of this service.
     */
    class VmsListenerManager {
        /**
         * Allows to modify mListenerMap and mListenerDeathRecipientMap as a single unit.
         */
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final Map<IBinder, ListenerDeathRecipient> mListenerDeathRecipientMap =
                new HashMap<>();
        @GuardedBy("mLock")
        private final Map<IBinder, IOnVmsMessageReceivedListener> mListenerMap = new HashMap<>();

        class ListenerDeathRecipient implements IBinder.DeathRecipient {
            private IBinder mListenerBinder;

            ListenerDeathRecipient(IBinder listenerBinder) {
                mListenerBinder = listenerBinder;
            }

            /**
             * Listener died. Remove it from this service.
             */
            @Override
            public void binderDied() {
                if (DBG) {
                    Log.d(TAG, "binderDied " + mListenerBinder);
                }
                VmsListenerManager.this.removeListener(mListenerBinder);
            }

            void release() {
                mListenerBinder.unlinkToDeath(this, 0);
            }
        }

        public void release() {
            for (ListenerDeathRecipient recipient : mListenerDeathRecipientMap.values()) {
                recipient.release();
            }
            mListenerDeathRecipientMap.clear();
            mListenerMap.clear();
        }

        /**
         * Adds the listener and a death recipient associated to it.
         *
         * @param listener to be added.
         * @throws IllegalArgumentException if the listener is null.
         * @throws IllegalStateException    if it was not possible to link a death recipient to the
         *                                  listener.
         */
        public void add(IOnVmsMessageReceivedListener listener) {
            ICarImpl.assertPermission(mContext, PERMISSION);
            if (listener == null) {
                Log.e(TAG, "register: listener is null.");
                throw new IllegalArgumentException("listener cannot be null.");
            }
            if (DBG) {
                Log.d(TAG, "register: " + listener);
            }
            IBinder listenerBinder = listener.asBinder();
            synchronized (mLock) {
                if (mListenerMap.containsKey(listenerBinder)) {
                    // Already registered, nothing to do.
                    return;
                }
                ListenerDeathRecipient deathRecipient = new ListenerDeathRecipient(listenerBinder);
                try {
                    listenerBinder.linkToDeath(deathRecipient, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to link death for recipient. ", e);
                    throw new IllegalStateException(Car.CAR_NOT_CONNECTED_EXCEPTION_MSG);
                }
                mListenerDeathRecipientMap.put(listenerBinder, deathRecipient);
                mListenerMap.put(listenerBinder, listener);
            }
        }

        /**
         * Removes the listener and associated death recipient.
         *
         * @param listener to be removed.
         * @throws IllegalArgumentException if listener is null.
         */
        public void remove(IOnVmsMessageReceivedListener listener) {
            if (DBG) {
                Log.d(TAG, "unregisterListener");
            }
            ICarImpl.assertPermission(mContext, PERMISSION);
            if (listener == null) {
                Log.e(TAG, "unregister: listener is null.");
                throw new IllegalArgumentException("Listener is null");
            }
            IBinder listenerBinder = listener.asBinder();
            removeListener(listenerBinder);
        }

        // Removes the listenerBinder from the current state.
        // The function assumes that binder will exist both in listeners and death recipients list.
        private void removeListener(IBinder listenerBinder) {
            synchronized (mLock) {
                boolean found = mListenerMap.remove(listenerBinder) != null;
                if (found) {
                    mListenerDeathRecipientMap.get(listenerBinder).release();
                    mListenerDeathRecipientMap.remove(listenerBinder);
                } else {
                    Log.e(TAG, "removeListener: listener was not previously registered.");
                }
            }
        }

        /**
         * Returns list of listeners currently registered.
         *
         * @return list of listeners.
         */
        public List<IOnVmsMessageReceivedListener> getListeners() {
            synchronized (mLock) {
                return new ArrayList<>(mListenerMap.values());
            }
        }
    }

    public VmsSubscriberService(Context context, VmsHalService hal) {
        mContext = context;
        mHal = hal;
    }

    // Implements CarServiceBase interface.
    @Override
    public void init() {
        mHal.addSubscriberListener(this);
    }

    @Override
    public void release() {
        mMessageReceivedManager.release();
        mHal.removeSubscriberListener(this);
    }

    @Override
    public void dump(PrintWriter writer) {
    }

    // Implements IVmsService interface.
    @Override
    public void addOnVmsMessageReceivedListener(int layer, int version,
            IOnVmsMessageReceivedListener listener, boolean silent) {
        mMessageReceivedManager.add(listener);
    }

    @Override
    public void removeOnVmsMessageReceivedListener(int layer, int version,
            IOnVmsMessageReceivedListener listener) {
        mMessageReceivedManager.remove(listener);
    }

    // Implements VmsHalSubscriberListener interface
    @Override
    public void onChange(int layerId, int layerVersion, byte[] payload) {
        for (IOnVmsMessageReceivedListener subscriber : mMessageReceivedManager.getListeners()) {
            try {
                subscriber.onVmsMessageReceived(layerId, layerVersion, payload);
            } catch (RemoteException e) {
                // If we could not send a record, its likely the connection snapped. Let the binder
                // death handle the situation.
                Log.e(TAG, "onVmsMessageReceived calling failed: ", e);
            }
        }
    }
}
