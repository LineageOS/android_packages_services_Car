/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.car.hal.ClusterHalService.DISPLAY_OFF;
import static com.android.car.hal.ClusterHalService.DISPLAY_ON;
import static com.android.car.hal.ClusterHalService.DONT_CARE;

import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.cluster.ClusterHomeManager;
import android.car.cluster.ClusterState;
import android.car.cluster.IClusterHomeCallback;
import android.car.cluster.IClusterHomeService;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.view.Display;

import com.android.car.CarLog;
import com.android.car.CarOccupantZoneService;
import com.android.car.CarServiceBase;
import com.android.car.hal.ClusterHalService;

/**
 * Service responsible for interactions between ClusterOS and ClusterHome.
 */
public class ClusterHomeService extends IClusterHomeService.Stub
        implements CarServiceBase, ClusterNavigationService.ClusterNavigationServiceCallback,
        ClusterHalService.ClusterHalEventCallback {
    private static final String TAG = CarLog.TAG_CLUSTER;
    private static final int DEFAULT_MIN_UPDATE_INTERVAL_MILLIS = 1000;
    private static final String NAV_STATE_PROTO_BUNDLE_KEY = "navstate2";

    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final ClusterHalService mClusterHalService;
    private final ClusterNavigationService mClusterNavigationService;
    private final CarOccupantZoneService mOccupantZoneService;
    private final InstrumentClusterService mOldClusterService;

    private boolean mServiceEnabled;

    private int mOnOff = DISPLAY_OFF;
    private int mWidth;
    private int mHeight;
    private Insets mInsets = Insets.NONE;
    private int mUiType = ClusterHomeManager.UI_TYPE_CLUSTER_HOME;

    private final RemoteCallbackList<IClusterHomeCallback> mClientCallbacks =
            new RemoteCallbackList<>();

    public ClusterHomeService(Context context, ClusterHalService clusterHalService,
            InstrumentClusterService oldClusterService,
            ClusterNavigationService navigationService,
            CarOccupantZoneService occupantZoneService) {
        mContext = context;
        mClusterHalService = clusterHalService;
        mOldClusterService = oldClusterService;
        mClusterNavigationService = navigationService;
        mOccupantZoneService = occupantZoneService;
    }

    @Override
    public void init() {
        if (DBG) Slog.d(TAG, "initClusterHomeService");
        if (mOldClusterService != null) {
            Slog.i(TAG, "Old instrument cluster service is activated");
            return;
        }
        if (!mClusterHalService.isCoreSupported()) {
            Slog.e(TAG, "No Cluster HAL properties");
            return;
        }

        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        int clusterDisplayId = mOccupantZoneService.getDisplayIdForDriver(
                CarOccupantZoneManager.DISPLAY_TYPE_INSTRUMENT_CLUSTER);
        if (clusterDisplayId == Display.INVALID_DISPLAY) {
            Slog.i(TAG, "No cluster display is defined");
            return;
        }
        Display clusterDisplay = displayManager.getDisplay(clusterDisplayId);
        Point size = new Point();
        clusterDisplay.getRealSize(size);
        mWidth = size.x;
        mHeight = size.y;
        if (DBG) {
            Slog.d(TAG, "Found cluster displayId=" + clusterDisplayId
                    + ", width=" + mWidth + ", height=" + mHeight);
        }

        mServiceEnabled = true;
        mClusterHalService.setCallback(this);
        mClusterNavigationService.setClusterServiceCallback(this);
    }

    @Override
    public void release() {
        if (DBG) Slog.d(TAG, "releaseClusterHomeService");
        mClusterHalService.setCallback(null);
        mClusterNavigationService.setClusterServiceCallback(null);
        mClientCallbacks.kill();
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        // TODO: record the latest states from both sides
    }

    // ClusterHalEventListener starts
    @Override
    public void onSwitchUi(int uiType) {
        if (DBG) Slog.d(TAG, "onSwitchUi: uiType=" + uiType);
        int changes = 0;
        if (mUiType != uiType) {
            mUiType = uiType;
            changes |= ClusterHomeManager.CONFIG_UI_TYPE;
        }
        final ClusterState state = createClusterState();
        final int n = mClientCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            IClusterHomeCallback callback = mClientCallbacks.getBroadcastItem(i);
            try {
                callback.onClusterStateChanged(state, changes);
            } catch (RemoteException ignores) {
                // ignore
            }
        }
        mClientCallbacks.finishBroadcast();
    }

    @Override
    public void onDisplayState(int onOff, int width, int height, Insets insets) {
        if (DBG) {
            Slog.d(TAG, "onDisplayState: onOff=" + onOff + ", width=" + width
                    + ", height=" + height + ", insets=" + insets);
        }
        int changes = 0;
        if (onOff != DONT_CARE && mOnOff != onOff) {
            mOnOff = onOff;
            changes |= ClusterHomeManager.CONFIG_DISPLAY_ON_OFF;
        }
        if (width != DONT_CARE && height != DONT_CARE
                && (mWidth != width || mHeight != height)) {
            mWidth = width;
            mHeight = height;
            changes |= ClusterHomeManager.CONFIG_DISPLAY_SIZE;
        }
        if (insets != null && !mInsets.equals(insets)) {
            mInsets = insets;
            changes |= ClusterHomeManager.CONFIG_DISPLAY_INSETS;
        }
        // TODO: need to change actual display state based on the arguments.

        final ClusterState state = createClusterState();
        final int n = mClientCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            IClusterHomeCallback callback = mClientCallbacks.getBroadcastItem(i);
            try {
                callback.onClusterStateChanged(state, changes);
            } catch (RemoteException ignores) {
                // ignore
            }
        }
        mClientCallbacks.finishBroadcast();
    }
    // ClusterHalEventListener sends

    // ClusterNavigationServiceCallback starts
    @Override
    public void onNavigationStateChanged(Bundle bundle) {
        byte[] protoBytes = bundle.getByteArray(NAV_STATE_PROTO_BUNDLE_KEY);

        final int n = mClientCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            IClusterHomeCallback callback = mClientCallbacks.getBroadcastItem(i);
            try {
                callback.onNavigationStateChanged(protoBytes);
            } catch (RemoteException ignores) {
                // ignore
            }
        }
        mClientCallbacks.finishBroadcast();

        if (!mClusterHalService.isNavigationStateSupported()) {
            if (DBG) Slog.d(TAG, "No Cluster NavigationState HAL property");
            return;
        }
        mClusterHalService.sendNavigationState(protoBytes);
    }

    @Override
    public CarNavigationInstrumentCluster getInstrumentClusterInfo() {
        return CarNavigationInstrumentCluster.createCluster(DEFAULT_MIN_UPDATE_INTERVAL_MILLIS);
    }

    @Override
    public void notifyNavContextOwnerChanged(ClusterNavigationService.ContextOwner owner) {
        // TODO: Implement this
    }
    // ClusterNavigationServiceCallback ends

    // IClusterHomeService starts
    @Override
    public void reportState(int uiTypeMain, int uiTypeSub, byte[] uiAvailability) {
        if (DBG) Slog.d(TAG, "requestDisplay: main=" + uiTypeMain + ", sub=" + uiTypeSub);
        enforcePermission(Car.PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL);
        if (!mServiceEnabled) throw new IllegalStateException("Service is not enabled");

        mClusterHalService.reportState(mOnOff, mWidth, mHeight, mInsets,
                uiTypeMain, uiTypeSub, uiAvailability);
    }

    @Override
    public void requestDisplay(int uiType) {
        if (DBG) Slog.d(TAG, "requestDisplay: uiType=" + uiType);
        enforcePermission(Car.PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL);
        if (!mServiceEnabled) throw new IllegalStateException("Service is not enabled");

        mClusterHalService.requestDisplay(uiType);
    }

    @Override
    public boolean startFixedActivityModeForDisplayAndUser(Intent intent,
            Bundle activityOptionsBundle, int userId) {
        enforcePermission(Car.PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL);
        if (!mServiceEnabled) throw new IllegalStateException("Service is not enabled");

        // TODO: Implement this
        return true;
    }

    @Override
    public void stopFixedActivityMode(int displayId) {
        enforcePermission(Car.PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL);
        if (!mServiceEnabled) throw new IllegalStateException("Service is not enabled");

        // TODO: Implement this
    }

    @Override
    public void registerCallback(IClusterHomeCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL);
        if (!mServiceEnabled) throw new IllegalStateException("Service is not enabled");

        mClientCallbacks.register(callback);
    }

    @Override
    public void unregisterCallback(IClusterHomeCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL);
        if (!mServiceEnabled) throw new IllegalStateException("Service is not enabled");

        mClientCallbacks.unregister(callback);
    }

    @Override
    public ClusterState getClusterState() {
        if (DBG) Slog.d(TAG, "getClusterState");
        enforcePermission(Car.PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL);
        if (!mServiceEnabled) throw new IllegalStateException("Service is not enabled");
        return createClusterState();
    }
    // IClusterHomeService ends

    private void enforcePermission(String permissionName) {
        if (mContext.checkCallingOrSelfPermission(permissionName)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires permission " + permissionName);
        }
    }

    private ClusterState createClusterState() {
        ClusterState state = new ClusterState();
        state.on = mOnOff == DISPLAY_ON;
        state.width = mWidth;
        state.height = mHeight;
        state.insets = mInsets;
        state.uiType = mUiType;
        return state;
    }
}
