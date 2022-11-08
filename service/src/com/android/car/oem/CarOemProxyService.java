/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.oem;

import android.annotation.Nullable;
import android.car.builtin.content.pm.PackageManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.oem.IOemCarAudioFocusService;
import android.car.oem.IOemCarService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.TimeoutException;

/**
 * Manages access to OemCarService.
 *
 * <p>All calls in this class are blocking on OEM service initialization, so should be called as
 *  late as possible.
 *
 * <b>NOTE</b>: All {@link CarOemProxyService} call should be after init of ICarImpl. If any
 * component calls {@link CarOemProxyService} before init of ICarImpl complete, it would throw
 * {@link IllegalStateException}.
 */
public final class CarOemProxyService implements CarServiceBase {

    private static final String TAG = CarOemProxyService.class.getSimpleName();
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);

    private final int mOemServiceConnectionTimeoutMs;
    private final int mOemServiceReadyTimeoutMs;

    private final Object mLock = new Object();
    private final boolean mIsFeatureEnabled;
    private final String mComponentName;
    private final Context mContext;
    private final boolean mIsOemServiceBound;
    @Nullable
    private final CarOemProxyServiceHelper mHelper;

    // True once OemService return true for {@code isOemServiceReady} call. It means that OEM
    // service has completed all the initialization and ready to serve requests.
    @GuardedBy("mLock")
    private boolean mIsOemServiceReady;
    // True once OEM service is connected. It means that OEM service has return binder for
    // communication. OEM service may still not be ready.
    @GuardedBy("mLock")
    private boolean mIsOemServiceConnected;
    @GuardedBy("mLock")
    private boolean mInitComplete;
    @GuardedBy("mLock")
    private IOemCarService mOemCarService;

    private final ServiceConnection mCarOemServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Slogf.i(TAG, "onServiceConnected: %s, %s", componentName, iBinder);
            synchronized (mLock) {
                if (mOemCarService == IOemCarService.Stub.asInterface(iBinder)) {
                    return; // already connected.
                }
                Slogf.i(TAG, "car oem service binder changed, was %s now: %s",
                        mOemCarService, iBinder);
                mOemCarService = IOemCarService.Stub.asInterface(iBinder);
                Slogf.i(TAG, "**CarOemService connected**");
                mIsOemServiceConnected = true;
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Slogf.e(TAG, "OEM service crashed. Crashing the CarService. ComponentName:%s",
                    componentName);
            mHelper.crashCarService("Service Disconnected");
        }
    };


    public CarOemProxyService(Context context) {
        // Bind to the OemCarService
        mContext = context;
        Resources res = mContext.getResources();
        mOemServiceConnectionTimeoutMs = res
                .getInteger(R.integer.config_oemCarService_connection_timeout_ms);
        mOemServiceReadyTimeoutMs = res
                .getInteger(R.integer.config_oemCarService_serviceReady_timeout_ms);

        mComponentName = res.getString(R.string.config_oemCarService);

        Slogf.i(TAG, "Oem Car Service Config. Connection timeout:%s, Service Ready timeout:%d, "
                + "component Name:%s", mOemServiceConnectionTimeoutMs, mOemServiceReadyTimeoutMs,
                mComponentName);

        if (isInvalidComponentName(context, mComponentName)) {
            // feature disabled
            mIsFeatureEnabled = false;
            mIsOemServiceBound = false;
            mHelper = null;
            Slogf.i(TAG, "**CarOemService is disabled.**");
            return;
        }

        Intent intent = (new Intent())
                .setComponent(ComponentName.unflattenFromString(mComponentName));

        Slogf.i(TAG, "Binding to Oem Service with intent: %s", intent);

        mIsOemServiceBound = mContext.bindServiceAsUser(intent, mCarOemServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.SYSTEM);

        if (mIsOemServiceBound) {
            mIsFeatureEnabled = true;
            Slogf.i(TAG, "OemCarService bounded.");
        } else {
            mIsFeatureEnabled = false;
            Slogf.e(TAG,
                    "Couldn't bound to OemCarService. Oem service feature is marked disabled.");
        }
        mHelper = new CarOemProxyServiceHelper(mContext);
    }

    private boolean isInvalidComponentName(Context context, String componentName) {
        if (componentName == null || componentName.isEmpty()) {
            if (DBG) {
                Slogf.d(TAG, "ComponentName is null or empty.");
            }
            return true;
        }
        // Only pre-installed package can be used for OEM Service.
        String packageName = ComponentName.unflattenFromString(componentName).getPackageName();
        PackageInfo info;
        try {
            info = context.getPackageManager().getPackageInfo(packageName, /* flags= */ 0);
        } catch (NameNotFoundException e) {
            Slogf.e(TAG, "componentName %s not found.", componentName);
            return true;
        }

        if (info == null || info.applicationInfo == null
                || !(PackageManagerHelper.isSystemApp(info.applicationInfo)
                        || PackageManagerHelper.isUpdatedSystemApp(info.applicationInfo)
                        || PackageManagerHelper.isOemApp(info.applicationInfo)
                        || PackageManagerHelper.isOdmApp(info.applicationInfo)
                        || PackageManagerHelper.isVendorApp(info.applicationInfo)
                        || PackageManagerHelper.isProductApp(info.applicationInfo)
                        || PackageManagerHelper.isSystemExtApp(info.applicationInfo))) {
            if (DBG) {
                Slogf.d(TAG, "Invalid component name. Info: %s", info);
            }
            return true;
        }

        if (DBG) {
            Slogf.d(TAG, "Valid component name %s, ", componentName);
        }

        return false;
    }

    @Override
    public void init() {
        // Nothing to be done as OemCarService was initialized in the constructor.
    }

    @Override
    public void release() {
        // Stop OEM Service;
        if (mIsOemServiceBound) {
            Slogf.i(TAG, "Unbinding Oem Service");
            mContext.unbindService(mCarOemServiceConnection);
        }
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.printf("***CarOemProxyService dump***");
        writer.increaseIndent();
        synchronized (mLock) {
            writer.printf("mIsFeatureEnabled: %s", mIsFeatureEnabled);
            writer.printf("mIsOemServiceBound: %s", mIsOemServiceBound);
            writer.printf("mIsOemServiceReady: %s", mIsOemServiceReady);
            writer.printf("mIsOemServiceConnected: %s", mIsOemServiceConnected);
            writer.printf("mInitComplete: %s", mInitComplete);
            writer.printf("OEM_CAR_SERVICE_CONNECTED_TIMEOUT_MS: %s",
                    mOemServiceConnectionTimeoutMs);
            writer.printf("OEM_CAR_SERVICE_READY_TIMEOUT_MS: %s", mOemServiceReadyTimeoutMs);
            writer.decreaseIndent();
        }
    }

    /**
     * Gets OEM audio focus service.
     */
    @Nullable
    public CarOemAudioFocusProxyService getCarOemAudioFocusService() {
        if (!mIsFeatureEnabled) {
            if (DBG) {
                Slogf.d(TAG, "Oem Car Service is disabled, returning null for"
                        + " getCarOemAudioFocusService");
            }
            return null;
        }

        waitForOemService();

        // TODO(b/240615622): Domain owner to decide if retry or default or crash.
        IOemCarAudioFocusService oemAudioFocusService = mHelper.doBinderTimedCall(
                () -> getOemService().getOemAudioFocusService(), /* defaultValue= */ null);

        if (oemAudioFocusService == null) {
            if (DBG) {
                Slogf.d(TAG, "Oem Car Service doesn't implement AudioFocusService, returning null"
                        + " for getCarOemAudioFocusService");
            }
            return null;
        }
        return new CarOemAudioFocusProxyService(mHelper, oemAudioFocusService);
    }

    /**
     * Should be called when CarService is ready for communication. It updates the OEM service that
     * CarService is ready.
     */
    public void onCarServiceReady() {
        waitForOemServiceConnected();
        mHelper.doBinderOneWayCall(() -> {
            try {
                getOemService().onCarServiceReady();
            } catch (RemoteException ex) {
                Slogf.e(TAG, "Binder call received RemoteException, calling to crash CarService",
                        ex);
                mHelper.crashCarService("Remote Exception");
            }
        });
    }

    private void waitForOemServiceConnected() {
        synchronized (mLock) {
            if (!mInitComplete) {
                // No CarOemService call should be made before or during init of ICarImpl.
                throw new IllegalStateException(
                        "CarOemService should not be call before CarService initialization");
            }

            if (mIsOemServiceConnected) {
                return;
            }
            waitForOemServiceConnectedLocked();
        }
    }

    @GuardedBy("mLock")
    private void waitForOemServiceConnectedLocked() {
        long startTime = SystemClock.elapsedRealtime();
        long remainingTime = mOemServiceConnectionTimeoutMs;

        while (!mIsOemServiceConnected && remainingTime > 0) {
            try {
                Slogf.i(TAG, "waiting to connect to OemService. wait time: %s", remainingTime);
                mLock.wait(mOemServiceConnectionTimeoutMs);
                remainingTime = mOemServiceConnectionTimeoutMs
                        - (SystemClock.elapsedRealtime() - startTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Slogf.w(TAG, "InterruptedException received. Reset interrupted status.", e);
            }
        }

        if (!mIsOemServiceConnected) {
            Slogf.e(TAG, "OEM Service is not connected within: %dms, calling to crash CarService",
                    mOemServiceConnectionTimeoutMs);
            mHelper.crashCarService("Timeout Exception");
        }
    }

    private void waitForOemService() {
        waitForOemServiceConnected();
        synchronized (mLock) {
            if (mIsOemServiceReady) {
                return;
            }
        }
        waitForOemServiceReady();
    }

    // TODO(b/226406223): Instead of polling, pass a callback as part of OnCarServiceReady. OEM
    // would call the callback once OEM is ready. Thus no need to do sleep or polling.
    private void waitForOemServiceReady() {
        boolean isOemServiceReady = false;
        long startTime = SystemClock.elapsedRealtime();
        long remainingTime = mOemServiceReadyTimeoutMs;

        while (!isOemServiceReady && remainingTime > 0) {
            try {
                Slogf.i(TAG, "waiting to connect to be ready. wait time: %s", remainingTime);
                isOemServiceReady = mHelper
                        .doBinderTimedCall(() -> getOemService().isOemServiceReady(),
                                remainingTime);
            } catch (TimeoutException e) {
                Log.e(TAG, "OEM Service is not ready within: " + mOemServiceReadyTimeoutMs
                        + "ms, calling to crash CarService");
                mHelper.crashCarService("Timeout Exception");
            }
            remainingTime = mOemServiceReadyTimeoutMs
                    - (SystemClock.uptimeMillis() - startTime);
            if (!isOemServiceReady) {
                // It is possible that CarService is connected and isOemServiceReady call is
                // returning false. In that case, we have to wait till
                // OEM_CAR_SERVICE_READY_TIMEOUT_MS and keep querying OEM Service.
                try {
                    // wait for 100 milliseconds before another call
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (!isOemServiceReady) {
            mHelper.crashCarService("Service not ready");
        }

        synchronized (mLock) {
            mIsOemServiceReady = isOemServiceReady;
        }
    }

    /**
     * Informs CarOemService that ICarImpl's init is complete.
     */
    // This would set mInitComplete, which is an additional check so that no car service component
    // calls CarOemService during or before ICarImpl's init.
    public void onInitComplete() {
        if (!mIsFeatureEnabled) {
            if (DBG) {
                Slogf.d(TAG, "Oem Car Service is disabled, No-op for onInitComplete");
            }
            return;
        }

        synchronized (mLock) {
            mInitComplete = true;
        }
        // inform OEM Service that CarService is ready for communication.
        onCarServiceReady();
    }

    private IOemCarService getOemService() {
        synchronized (mLock) {
            return mOemCarService;
        }
    }
}
