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

package android.support.car;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import java.lang.ref.WeakReference;

/**
 * CarAppContextManager allows applications to set and listen for the current application context
 * like active navigation or active voice command. Usually only one instance of such application
 * should run in the system, and other app setting the flag for the matching app should
 * lead into other app to stop.
 */
public class CarAppContextManager implements CarManagerBase {
    /**
     * Listener to get notification for app getting information on app context change or
     * ownership loss.
     */
    public interface AppContextChangeListener {
        /**
         * Application context has changed. Note that {@link CarAppContextManager} instance
         * causing the change will not get this notification.
         * @param activeContexts
         */
        void onAppContextChange(int activeContexts);

        /**
         * Lost ownership for the context, which happens when other app has set the context.
         * The app losing context should stop the action associated with the context.
         * For example, navigaiton app currently running active navigation should stop navigation
         * upon getting this for {@link CarAppContextManager#APP_CONTEXT_NAVIGATION}.
         * @param context
         */
        void onAppContextOwnershipLoss(int context);
    }

    /** @hide */
    public static final int APP_CONTEXT_START_FLAG = 0x1;
    /**
     * Flag for active navigation.
     */
    public static final int APP_CONTEXT_NAVIGATION = 0x1;
    /**
     * Flag for active voice command.
     */
    public static final int APP_CONTEXT_VOICE_COMMAND = 0x2;
    /**
     * Update this after adding a new flag.
     * @hide
     */
    public static final int APP_CONTEXT_END_FLAG = 0x2;

    private final IAppContext mService;
    private final Handler mHandler;
    private final IAppContextListenerImpl mBinderListener;

    private AppContextChangeListener mListener;
    private int mContextFilter;


    /**
     * @hide
     */
    CarAppContextManager(IAppContext service, Looper looper) {
        mService = service;
        mHandler = new Handler(looper);
        mBinderListener = new IAppContextListenerImpl(this);
    }

    /**
     * Register listener to monitor app context change. Only one listener can be registered and
     * registering multiple times will lead into only the last listener to be active.
     * @param listener
     * @param contextFilter Flags of cotexts to get notification.
     */
    public void registerContextListener(AppContextChangeListener listener, int contextFilter) {
        synchronized(this) {
            if (listener == null) {
                throw new IllegalArgumentException("null listener");
            }
            if (mListener == null || mContextFilter != contextFilter) {
                try {
                    mService.registerContextListener(IAppContextListenerImpl.CLIENT_VERSION,
                            mBinderListener, contextFilter);
                } catch (RemoteException e) {
                    //ignore as CarApi will handle disconnection anyway.
                }
            }
            mListener = listener;
            mContextFilter = contextFilter;
        }
    }

    /**
     * Unregister listener and stop listening context change events. If app has owned a context
     * by {@link #setActiveContext(int)}, it will be reset to inactive state.
     */
    public void unregisterContextListener() {
        synchronized(this) {
            try {
                mService.unregisterContextListener(mBinderListener);
            } catch (RemoteException e) {
                //ignore as CarApi will handle disconnection anyway.
            }
            mListener = null;
            mContextFilter = 0;
        }
    }

    public int getActiveAppContexts() {
        try {
            return mService.getActiveAppContexts();
        } catch (RemoteException e) {
            //ignore as CarApi will handle disconnection anyway.
        }
        return 0;
    }

    public boolean isOwningContext(int context) {
        try {
            return mService.isOwningContext(mBinderListener, context);
        } catch (RemoteException e) {
            //ignore as CarApi will handle disconnection anyway.
        }
        return false;
    }

    /**
     * Set the given contexts as active. By setting this, the application is becoming owner
     * of the context, and will get {@link AppContextChangeListener#onAppContextOwnershipLoss(int)}
     * if ownership is given to other app by calling this. Fore-ground app will have higher priority
     * and other app cannot set the same context while owner is in fore-ground.
     * Before calling this, {@link #registerContextListener(AppContextChangeListener, int)} should
     * be called first. Otherwise, it will throw IllegalStateException
     * @param contexts
     * @throws IllegalStateException If listener was not registered.
     * @throws SecurityException If owner cannot be changed.
     */
    public void setActiveContexts(int contexts) throws IllegalStateException, SecurityException {
        synchronized (this) {
            if (mListener == null) {
                throw new IllegalStateException("register listerner first");
            }
            try {
                mService.setActiveContexts(mBinderListener, contexts);
            } catch (RemoteException e) {
                //ignore as CarApi will handle disconnection anyway.
            }
        }
    }

    /**
     * Reset the given contexts, i.e. mark them as inactive. This also involves releasing ownership
     * for the context.
     * @param contexts
     */
    public void resetActiveContexts(int contexts) {
        try {
            mService.resetActiveContexts(mBinderListener, contexts);
        } catch (RemoteException e) {
            //ignore as CarApi will handle disconnection anyway.
        }
    }

    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    private void handleAppContextChange(int activeContexts) {
        AppContextChangeListener listener;
        int newContext;
        synchronized (this) {
            if (mListener == null) {
                return;
            }
            listener = mListener;
            newContext = activeContexts & mContextFilter;
        }
        listener.onAppContextChange(newContext);
    }

    private void handleAppContextOwnershipLoss(int context) {
        AppContextChangeListener listener;
        synchronized (this) {
            if (mListener == null) {
                return;
            }
            listener = mListener;
        }
        listener.onAppContextOwnershipLoss(context);
    }

    private static class IAppContextListenerImpl extends IAppContextListener.Stub {
        private static final int CLIENT_VERSION = 1;

        private final WeakReference<CarAppContextManager> mManager;

        private IAppContextListenerImpl(CarAppContextManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void onAppContextChange(final int activeContexts) {
            final CarAppContextManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    manager.handleAppContextChange(activeContexts);
                }
            });
        }

        @Override
        public void onAppContextOwnershipLoss(final int context) {
            final CarAppContextManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    manager.handleAppContextOwnershipLoss(context);
                }
            });
        }
    }
}
