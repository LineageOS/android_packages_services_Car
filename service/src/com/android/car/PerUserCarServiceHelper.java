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

import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;

import static com.android.car.CarServiceUtils.getHandlerThread;
import static com.android.car.CarServiceUtils.isEventOfType;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.IPerUserCarService;
import android.car.builtin.util.Slogf;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.UserLifecycleEventFilter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;

/**
 * A Helper class that helps with the following:
 * 1. Provide methods to Bind/Unbind to the {@link PerUserCarService} as the current User
 * 2. Set up a listener to UserSwitch Broadcasts and call clients that have registered callbacks.
 *
 */
public class PerUserCarServiceHelper implements CarServiceBase {

    private static final String TAG = CarLog.tagFor(PerUserCarServiceHelper.class);
    private static boolean DBG = false;

    private final Context mContext;
    private final CarUserService mUserService;
    private final Handler mHandler;

    private IPerUserCarService mPerUserCarService;
    // listener to call on a ServiceConnection to PerUserCarService
    private List<ServiceCallback> mServiceCallbacks;
    private final Object mServiceBindLock = new Object();
    @GuardedBy("mServiceBindLock")
    private boolean mBound;

    public PerUserCarServiceHelper(Context context, CarUserService userService) {
        mContext = context;
        mServiceCallbacks = new ArrayList<>();
        mUserService = userService;
        mHandler = new Handler(getHandlerThread(
                PerUserCarServiceHelper.class.getSimpleName()).getLooper());
        UserLifecycleEventFilter userSwitchingEventFilter = new UserLifecycleEventFilter.Builder()
                .addEventType(USER_LIFECYCLE_EVENT_TYPE_SWITCHING).build();
        mUserService.addUserLifecycleListener(userSwitchingEventFilter, mUserLifecycleListener);
    }

    @Override
    public void init() {
        synchronized (mServiceBindLock) {
            bindToPerUserCarService();
        }
    }

    @Override
    public void release() {
        synchronized (mServiceBindLock) {
            unbindFromPerUserCarService();
            mUserService.removeUserLifecycleListener(mUserLifecycleListener);
        }
    }

    private final UserLifecycleListener mUserLifecycleListener = event -> {
        if (!isEventOfType(TAG, event, USER_LIFECYCLE_EVENT_TYPE_SWITCHING)) {
            return;
        }
        if (DBG) {
            Slogf.d(TAG, "onEvent(" + event + ")");
        }
        List<ServiceCallback> callbacks;
        int userId = event.getUserId();
        if (DBG) {
            Slogf.d(TAG, "User Switch Happened. New User" + userId);
        }

        // Before unbinding, notify the callbacks about unbinding from the service
        // so the callbacks can clean up their state through the binder before the service is
        // killed.
        synchronized (mServiceBindLock) {
            // copy the callbacks
            callbacks = new ArrayList<>(mServiceCallbacks);
        }
        // call them
        for (ServiceCallback callback : callbacks) {
            callback.onPreUnbind();
        }
        // unbind from the service running as the previous user.
        unbindFromPerUserCarService();
        // bind to the service running as the new user
        bindToPerUserCarService();
    };

    /**
     * ServiceConnection to detect connecting/disconnecting to {@link PerUserCarService}
     */
    private final ServiceConnection mUserServiceConnection = new ServiceConnection() {
        // Handle ServiceConnection on a separate thread because the tasks performed on service
        // connected/disconnected take long time to complete and block the executing thread.
        // Executing these tasks on the main thread will result in CarService ANR.

        // On connecting to the service, get the binder object to the CarBluetoothService
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mHandler.post(() -> {
                List<ServiceCallback> callbacks;
                if (DBG) {
                    Slogf.d(TAG, "Connected to User Service");
                }
                mPerUserCarService = IPerUserCarService.Stub.asInterface(service);
                if (mPerUserCarService != null) {
                    synchronized (mServiceBindLock) {
                        // copy the callbacks
                        callbacks = new ArrayList<>(mServiceCallbacks);
                    }
                    // call them
                    for (ServiceCallback callback : callbacks) {
                        callback.onServiceConnected(mPerUserCarService);
                    }
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mHandler.post(() -> {
                List<ServiceCallback> callbacks;
                if (DBG) {
                    Slogf.d(TAG, "Disconnected from User Service");
                }
                synchronized (mServiceBindLock) {
                    // copy the callbacks
                    callbacks = new ArrayList<>(mServiceCallbacks);
                }
                // call them
                for (ServiceCallback callback : callbacks) {
                    callback.onServiceDisconnected();
                }
            });
        }
    };

    /**
     * Bind to the PerUserCarService {@link PerUserCarService} which is created to run as the
     * Current User.
     */
    private void bindToPerUserCarService() {
        if (DBG) {
            Slogf.d(TAG, "Binding to User service");
        }
        // This crosses both process and package boundary.
        Intent startIntent = BuiltinPackageDependency.addClassNameToIntent(mContext, new Intent(),
                BuiltinPackageDependency.PER_USER_CAR_SERVICE_CLASS);
        synchronized (mServiceBindLock) {
            mBound = true;
            boolean bindSuccess = mContext.bindServiceAsUser(startIntent, mUserServiceConnection,
                    mContext.BIND_AUTO_CREATE, UserHandle.CURRENT);
            // If valid connection not obtained, unbind
            if (!bindSuccess) {
                Slogf.e(TAG, "bindToPerUserCarService() failed to get valid connection");
                unbindFromPerUserCarService();
            }
        }
    }

    /**
     * Unbind from the {@link PerUserCarService} running as the Current user.
     */
    private void unbindFromPerUserCarService() {
        synchronized (mServiceBindLock) {
            // mBound flag makes sure we are unbinding only when the service is bound.
            if (mBound) {
                if (DBG) {
                    Slogf.d(TAG, "Unbinding from User Service");
                }
                mContext.unbindService(mUserServiceConnection);
                mBound = false;
            }
        }
    }

    /**
     * Register a listener that gets called on Connection state changes to the
     * {@link PerUserCarService}
     * @param listener - Callback to invoke on user switch event.
     */
    public void registerServiceCallback(ServiceCallback listener) {
        if (listener != null) {
            if (DBG) {
                Slogf.d(TAG, "Registering PerUserCarService Listener");
            }
            synchronized (mServiceBindLock) {
                mServiceCallbacks.add(listener);
            }
        }
    }

    /**
     * Unregister the Service Listener
     * @param listener - Callback method to unregister
     */
    public void unregisterServiceCallback(ServiceCallback listener) {
        if (DBG) {
            Slogf.d(TAG, "Unregistering PerUserCarService Listener");
        }
        if (listener != null) {
            synchronized (mServiceBindLock) {
                mServiceCallbacks.remove(listener);
            }
        }
    }

    /**
     * Listener to the PerUserCarService connection status that clients need to implement.
     */
    public interface ServiceCallback {
        /**
         * Invoked when a service connects.
         *
         * @param perUserCarService the instance of IPerUserCarService.
         */
        void onServiceConnected(IPerUserCarService perUserCarService);

        /**
         * Invoked before an unbind call is going to be made.
         */
        void onPreUnbind();

        /**
         * Invoked when a service is crashed or disconnected.
         */
        void onServiceDisconnected();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public final void dump(IndentingPrintWriter pw) {
        pw.println("PerUserCarServiceHelper");
        pw.increaseIndent();
        synchronized (mServiceBindLock) {
            pw.printf("bound: %b\n", mBound);
            if (mServiceCallbacks == null) {
                pw.println("no callbacks");
            } else {
                int size = mServiceCallbacks.size();
                pw.printf("%d callback%s\n", size, (size > 1 ? "s" : ""));
            }
        }
        pw.decreaseIndent();
    }
}
