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

package android.car.hardware.property;

import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.List;

/**
 * API for creating Car*Manager
 * @hide
 */
public class CarPropertyManagerBase {
    private final boolean mDbg;
    private final Handler mHandler;
    private final ArraySet<CarPropertyEventListener> mListeners = new ArraySet<>();
    private CarPropertyEventListenerToService mListenerToService = null;
    private final ICarProperty mService;
    private final String mTag;

    /** Callback functions for property events */
    public interface CarPropertyEventListener {
        /** Called when a property is updated */
        void onChangeEvent(CarPropertyValue value);

        /** Called when an error is detected with a property */
        void onErrorEvent(int propertyId, int zone);
    }

    private final static class EventCallbackHandler extends Handler {
        /** Constants handled in the handler */
        private static final int MSG_GENERIC_EVENT = 0;

        private final WeakReference<CarPropertyManagerBase> mMgr;

        EventCallbackHandler(CarPropertyManagerBase mgr, Looper looper) {
            super(looper);
            mMgr = new WeakReference<>(mgr);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GENERIC_EVENT:
                    CarPropertyManagerBase mgr = mMgr.get();
                    if (mgr != null) {
                        mgr.dispatchEventToClient((CarPropertyEvent) msg.obj);
                    }
                    break;
                default:
                    Log.e("EventtCallbackHandler", "Event type not handled:  " + msg);
                    break;
            }
        }
    }

    private static class CarPropertyEventListenerToService extends ICarPropertyEventListener.Stub {
        private final WeakReference<CarPropertyManagerBase> mManager;

        public CarPropertyEventListenerToService(
                CarPropertyManagerBase manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void onEvent(CarPropertyEvent event) {
            CarPropertyManagerBase manager = mManager.get();
            if (manager != null) {
                manager.handleEvent(event);
            }
        }
    }

    /**
     * Get an instance of the CarPropertyManagerBase.
     */
    public CarPropertyManagerBase(IBinder service, Context context, Looper looper, boolean dbg,
            String tag) {
        mDbg = dbg;
        mTag = tag;
        mService = ICarProperty.Stub.asInterface(service);
        mHandler = new EventCallbackHandler(this, looper);
    }

    /**
     * Register {@link CarPropertyEventListener} to get property changes
     *
     * @param listener Implements onEvent() for property change updates
     */
    public synchronized void registerListener(CarPropertyEventListener listener)
            throws CarNotConnectedException {
        if(mListeners.isEmpty()) {
            try {
                mListenerToService = new CarPropertyEventListenerToService(this);
                mService.registerListener(mListenerToService);
            } catch (RemoteException ex) {
                Log.e(mTag, "Could not connect: ", ex);
                throw new CarNotConnectedException(ex);
            } catch (IllegalStateException ex) {
                Car.checkCarNotConnectedExceptionFromCarService(ex);
            }
        }
        mListeners.add(listener);
    }

    /**
     * Unregister {@link CarPropertyEventListener}.
     * @param listener CarPropertyEventListener to unregister
     */
    public synchronized void unregisterListener(CarPropertyEventListener listener)
            throws CarNotConnectedException {
        if (mDbg) {
            Log.d(mTag, "unregisterListener");
        }
        mListeners.remove(listener);
        if(mListeners.isEmpty()) {
            try {
                mService.unregisterListener(mListenerToService);
            } catch (RemoteException ex) {
                Log.e(mTag, "Could not unregister: ", ex);
                throw new CarNotConnectedException(ex);
            }
            mListenerToService = null;
        }
    }

    /**
     * Returns the list of properties available.
     *
     * @return Caller must check the property type and typecast to the appropriate subclass
     * (CarPropertyBooleanProperty, CarPropertyFloatProperty, CarrPropertyIntProperty)
     */
    public List<CarPropertyConfig> getPropertyList()  throws CarNotConnectedException {
        try {
            return mService.getPropertyList();
        } catch (RemoteException e) {
            Log.w(mTag, "Exception in getPropertyList", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Returns value of a bool property
     *
     * @param prop Property ID to get
     * @param area Area of the property to get
     */
    public boolean getBooleanProperty(int prop, int area) throws CarNotConnectedException {
        CarPropertyValue<Boolean> carProp = getProperty(Boolean.class, prop, area);
        return carProp != null ? carProp.getValue() : false;
    }

    /**
     * Returns value of a float property
     *
     * @param prop Property ID to get
     * @param area Area of the property to get
     */
    public float getFloatProperty(int prop, int area) throws CarNotConnectedException {
        CarPropertyValue<Float> carProp = getProperty(Float.class, prop, area);
        return carProp != null ? carProp.getValue() : 0f;
    }

    /**
     * Returns value of a integer property
     *
     * @param prop Property ID to get
     * @param area Zone of the property to get
     */
    public int getIntProperty(int prop, int area) throws CarNotConnectedException {
        CarPropertyValue<Integer> carProp = getProperty(Integer.class, prop, area);
        return carProp != null ? carProp.getValue() : 0;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <E> CarPropertyValue<E> getProperty(Class<E> clazz, int propId, int area)
            throws CarNotConnectedException {
        if (mDbg) {
            Log.d(mTag, "getProperty, propId: 0x" + toHexString(propId)
                    + ", area: 0x" + toHexString(area) + ", clazz: " + clazz);
        }
        try {
            CarPropertyValue<E> propVal = mService.getProperty(propId, area);
            if (propVal != null && propVal.getValue() != null) {
                Class<?> actualClass = propVal.getValue().getClass();
                if (actualClass != clazz) {
                    throw new IllegalArgumentException("Invalid property type. " + "Expected: "
                            + clazz + ", but was: " + actualClass);
                }
            }
            return propVal;
        } catch (RemoteException e) {
            Log.e(mTag, "getProperty failed with " + e.toString()
                    + ", propId: 0x" + toHexString(propId) + ", area: 0x" + toHexString(area), e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Modifies a property.  If the property modification doesn't occur, an error event shall be
     * generated and propagated back to the application.
     *
     * @param prop Property ID to modify
     * @param area Area to apply the modification.
     * @param val Value to set
     */
    public void setBooleanProperty(int prop, int area, boolean val)
            throws CarNotConnectedException {
        if (mDbg) {
            Log.d(mTag, "setBooleanProperty:  prop = " + prop + " area = " + area + " val = " + val);
        }
        try {
            mService.setProperty(new CarPropertyValue<>(prop, area, val));
        } catch (RemoteException e) {
            Log.e(mTag, "setBooleanProperty failed with " + e.toString(), e);
            throw new CarNotConnectedException(e);
        }
    }

    public void setFloatProperty(int prop, int area, float val) throws CarNotConnectedException {
        if (mDbg) {
            Log.d(mTag, "setFloatProperty:  prop = " + prop + " area = " + area + " val = " + val);
        }
        try {
            mService.setProperty(new CarPropertyValue<>(prop, area, val));
        } catch (RemoteException e) {
            Log.e(mTag, "setBooleanProperty failed with " + e.toString(), e);
            throw new CarNotConnectedException(e);
        }
    }

    public void setIntProperty(int prop, int area, int val) throws CarNotConnectedException {
        if (mDbg) {
            Log.d(mTag, "setIntProperty:  prop = " + prop + " area = " + area + " val = " + val);
        }
        try {
            mService.setProperty(new CarPropertyValue<>(prop, area, val));
        } catch (RemoteException e) {
            Log.e(mTag, "setIntProperty failed with " + e.toString(), e);
            throw new CarNotConnectedException(e);
        }
    }

    private synchronized void dispatchEventToClient(CarPropertyEvent event) {
        Collection<CarPropertyEventListener> listeners;
        synchronized (this) {
            listeners = new ArraySet<>(mListeners);
        }
        if (!listeners.isEmpty()) {
            CarPropertyValue propVal = event.getCarPropertyValue();
            switch(event.getEventType()) {
                case CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE:
                    for (CarPropertyEventListener l: listeners) {
                        l.onChangeEvent(propVal);
                    }
                    break;
                case CarPropertyEvent.PROPERTY_EVENT_ERROR:
                    for (CarPropertyEventListener l: listeners) {
                        l.onErrorEvent(propVal.getPropertyId(), propVal.getAreaId());
                    }
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        } else {
            Log.e(mTag, "Listener died, not dispatching event.");
        }
    }

    private void handleEvent(CarPropertyEvent event) {
        mHandler.sendMessage(mHandler.obtainMessage(EventCallbackHandler.MSG_GENERIC_EVENT, event));
    }

    public void onCarDisconnected() {
        for(CarPropertyEventListener l: mListeners) {
            try {
                unregisterListener(l);
            } catch (CarNotConnectedException e) {
                // Ignore, car is disconnecting.
            }
        }
    }
}
