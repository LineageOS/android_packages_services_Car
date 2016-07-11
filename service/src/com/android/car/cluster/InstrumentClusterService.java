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
package com.android.car.cluster;

import android.annotation.SystemApi;
import android.car.CarAppFocusManager;
import android.car.cluster.renderer.IInstrumentCluster;
import android.car.cluster.renderer.IInstrumentClusterNavigation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;

import com.android.car.AppFocusService;
import com.android.car.AppFocusService.FocusOwnershipListener;
import com.android.car.CarInputService;
import com.android.car.CarInputService.KeyEventListener;
import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.R;

import java.io.PrintWriter;

/**
 * Service responsible for interaction with car's instrument cluster.
 *
 * @hide
 */
@SystemApi
public class InstrumentClusterService implements CarServiceBase,
        FocusOwnershipListener, KeyEventListener {

    private static final String TAG = CarLog.TAG_CLUSTER;
    private static final Boolean DBG = true;

    private final Context mContext;
    private final AppFocusService mAppFocusService;
    private final CarInputService mCarInputService;

    private Pair<Integer, Integer> mNavContextOwner;

    private IInstrumentCluster mRendererService;
    private boolean mRendererBound = false;

    private final ServiceConnection mRendererServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (DBG) {
                Log.d(TAG, "onServiceConnected, name: " + name + ", binder: " + binder);
            }
            mRendererService = IInstrumentCluster.Stub.asInterface(binder);

            if (mNavContextOwner != null) {
                notifyNavContextOwnerChanged(mNavContextOwner.first, mNavContextOwner.second);
            }

            try {
                binder.linkToDeath(() -> CarServiceUtils.runOnMainSync(() -> {
                    Log.w(TAG, "Instrument cluster renderer died, trying to rebind");
                    mRendererService = null;
                    // Try to rebind with instrument cluster.
                    mRendererBound = bindInstrumentClusterRendererService();
                }), 0);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected, name: " + name);
        }
    };

    public InstrumentClusterService(Context context, AppFocusService appFocusService,
            CarInputService carInputService) {
        mContext = context;
        mAppFocusService = appFocusService;
        mCarInputService = carInputService;
    }

    @Override
    public void init() {
        if (DBG) {
            Log.d(TAG, "init");
        }

        mAppFocusService.registerContextOwnerChangedListener(this /* FocusOwnershipListener */);
        mCarInputService.setInstrumentClusterKeyListener(this /* KeyEventListener */);
        mRendererBound = bindInstrumentClusterRendererService();
    }

    @Override
    public void release() {
        if (DBG) {
            Log.d(TAG, "release");
        }

        mAppFocusService.unregisterContextOwnerChangedListener(this);
        if (mRendererBound) {
            mContext.unbindService(mRendererServiceConnection);
            mRendererBound = false;
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("**" + getClass().getSimpleName() + "**");
        writer.println("bound with renderer: " + mRendererBound);
        writer.println("renderer service: " + mRendererService);
    }

    @Override
    public void onFocusAcquired(int appType, int uid, int pid) {
        if (appType != CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION) {
            return;
        }

        mNavContextOwner = new Pair<>(uid, pid);

        notifyNavContextOwnerChanged(uid, pid);
    }

    @Override
    public void onFocusAbandoned(int appType, int uid, int pid) {
        if (appType != CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION) {
            return;
        }

        if (mNavContextOwner != null
                && mNavContextOwner.first == uid
                && mNavContextOwner.second == pid) {
            notifyNavContextOwnerChanged(0, 0);  // Reset focus ownership
        }
    }

    private void notifyNavContextOwnerChanged(int uid, int pid) {
        if (mRendererService != null) {
            try {
                mRendererService.setNavigationContextOwner(uid, pid);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call setNavigationContextOwner", e);
            }
        }
    }

    private boolean bindInstrumentClusterRendererService() {
        String rendererService = mContext.getString(R.string.instrumentClusterRendererService);
        if (TextUtils.isEmpty(rendererService)) {
            Log.i(TAG, "Instrument cluster renderer was not configured");
            return false;
        }

        Log.d(TAG, "bindInstrumentClusterRendererService, component: " + rendererService);

        Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(rendererService));
        // Explicitly start service as we do not use BIND_AUTO_CREATE flag to handle renderer crash.
        mContext.startService(intent);
        return mContext.bindService(intent, mRendererServiceConnection, Context.BIND_IMPORTANT);
    }

    public IInstrumentClusterNavigation getNavigationService() {
        try {
            return mRendererService == null ? null : mRendererService.getNavigationService();
        } catch (RemoteException e) {
            Log.e(TAG, "getNavigationServiceBinder" , e);
            return null;
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (DBG) {
            Log.d(TAG, "InstrumentClusterService#onKeyEvent: " + event);
        }
        if (mRendererService != null) {
            try {
                mRendererService.onKeyEvent(event);
            } catch (RemoteException e) {
                Log.e(TAG, "onKeyEvent", e);
            }
        }
        return true;
    }
}
