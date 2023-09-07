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

package com.android.car.hal;

import static android.car.VehiclePropertyIds.CLUSTER_DISPLAY_STATE;
import static android.car.VehiclePropertyIds.CLUSTER_NAVIGATION_STATE;
import static android.car.VehiclePropertyIds.CLUSTER_REPORT_STATE;
import static android.car.VehiclePropertyIds.CLUSTER_REQUEST_DISPLAY;
import static android.car.VehiclePropertyIds.CLUSTER_SWITCH_UI;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.car.builtin.util.Slogf;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.SparseBooleanArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;

/**
 * Translates HAL input events to higher-level semantic information.
 */
public final class ClusterHalService extends HalServiceBase {
    private static final String TAG = ClusterHalService.class.getSimpleName();
    public static final int DISPLAY_OFF = 0;
    public static final int DISPLAY_ON = 1;
    public static final int DONT_CARE = -1;

    /**
     * Interface to receive incoming Cluster HAL events.
     */
    public interface ClusterHalEventCallback {
        /**
         * Called when CLUSTER_SWITCH_UI message is received.
         *
         * @param uiType uiType ClusterOS wants to switch to
         */
        void onSwitchUi(int uiType);

        /**
         * Called when CLUSTER_DISPLAY_STATE message is received.
         *
         * @param onOff 0 - off, 1 - on
         * @param bounds the area to render the cluster Activity in pixel
         * @param insets Insets of the cluster display
         */
        void onDisplayState(int onOff, Rect bounds, Insets insets);
    };

    private static final int[] SUPPORTED_PROPERTIES = new int[]{
            CLUSTER_SWITCH_UI,
            CLUSTER_DISPLAY_STATE,
            CLUSTER_REPORT_STATE,
            CLUSTER_REQUEST_DISPLAY,
            CLUSTER_NAVIGATION_STATE,
    };

    private static final int[] CORE_PROPERTIES = new int[]{
            CLUSTER_SWITCH_UI,
            CLUSTER_REPORT_STATE,
            CLUSTER_DISPLAY_STATE,
            CLUSTER_REQUEST_DISPLAY,
    };

    private static final int[] SUBSCRIBABLE_PROPERTIES = new int[]{
            CLUSTER_SWITCH_UI,
            CLUSTER_DISPLAY_STATE,
    };

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ClusterHalEventCallback mCallback;

    private final VehicleHal mHal;

    // The actual availability of each supported property in SUPPORTED_PROPERTIES.
    private final SparseBooleanArray mAvailableProperties =
            new SparseBooleanArray(SUPPORTED_PROPERTIES.length);

    private final HalPropValueBuilder mPropValueBuilder;

    public ClusterHalService(VehicleHal hal) {
        mHal = hal;
        mPropValueBuilder = hal.getHalPropValueBuilder();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that {@link #takeProperties} must be called before this method, so that available
     * properties are correctly initialized.</p>
     */
    @Override
    public void init() {
        Slogf.d(TAG, "initClusterHalService");

        for (int property : SUBSCRIBABLE_PROPERTIES) {
            if (isPropertyAvailable(property)) {
                mHal.subscribeProperty(this, property);
            }
        }
    }

    @Override
    public void release() {
        Slogf.d(TAG, "releaseClusterHalService");
        synchronized (mLock) {
            mCallback = null;
        }
    }

    /**
     * Sets the event callback to receive Cluster HAL events.
     */
    public void setCallback(ClusterHalEventCallback callback) {
        synchronized (mLock) {
            mCallback = callback;
        }
    }

    @NonNull
    @Override
    public int[] getAllSupportedProperties() {
        return SUPPORTED_PROPERTIES;
    }

    @Override
    public void takeProperties(@NonNull Collection<HalPropConfig> properties) {
        mAvailableProperties.clear();
        for (HalPropConfig property : properties) {
            mAvailableProperties.put(property.getPropId(), true);
        }
        Slogf.d(TAG, "takeProperties: actuallySupportedProperties=%s", mAvailableProperties);
    }

    /** Checks if the property with the given {@code corePropertyId} is available. */
    @VisibleForTesting
    boolean isPropertyAvailable(int propertyId) {
        return mAvailableProperties.get(propertyId);
    }

    public boolean isNavigationStateSupported() {
        return mAvailableProperties.get(CLUSTER_NAVIGATION_STATE);
    }

    @Override
    public void onHalEvents(List<HalPropValue> values) {
        Slogf.d(TAG, "handleHalEvents(): %s", values);
        ClusterHalEventCallback callback;
        synchronized (mLock) {
            callback = mCallback;
        }
        if (callback == null) {
            return;
        }

        for (HalPropValue value : values) {
            switch (value.getPropId()) {
                case CLUSTER_SWITCH_UI:
                    if (value.getInt32ValuesSize() < 1) {
                        Slogf.e(TAG, "received invalid CLUSTER_SWITCH_UI property from HAL, "
                                + "expect at least 1 int value.");
                        break;
                    }
                    int uiType = value.getInt32Value(0);
                    callback.onSwitchUi(uiType);
                    break;
                case CLUSTER_DISPLAY_STATE:
                    if (value.getInt32ValuesSize() < 9) {
                        Slogf.e(TAG, "received invalid CLUSTER_DISPLAY_STATE property from HAL, "
                                + "expect at least 9 int value.");
                        break;
                    }
                    int onOff = value.getInt32Value(0);
                    Rect bounds = null;
                    if (hasNoDontCare(value, /* start= */ 1, /* length= */ 4, "bounds")) {
                        bounds =
                                new Rect(
                                        value.getInt32Value(1), value.getInt32Value(2),
                                        value.getInt32Value(3), value.getInt32Value(4));
                    }
                    Insets insets = null;
                    if (hasNoDontCare(value, /* start= */ 5, /* length= */ 4, "insets")) {
                        insets =
                                Insets.of(
                                        value.getInt32Value(5), value.getInt32Value(6),
                                        value.getInt32Value(7), value.getInt32Value(8));
                    }
                    callback.onDisplayState(onOff, bounds, insets);
                    break;
                default:
                    Slogf.w(TAG, "received unsupported event from HAL: %s", value);
            }
        }
    }

    private static boolean hasNoDontCare(HalPropValue value, int start, int length,
                                         String fieldName) {
        int count = 0;
        for (int i = start; i < start + length; ++i) {
            if (value.getInt32Value(i) == DONT_CARE) {
                ++count;
            }
        }
        if (count == 0) {
            return true;
        }
        if (count != length) {
            Slogf.w(TAG, "Don't care should be set in the whole %s.", fieldName);
        }
        return false;
    }

    /**
     * Reports the current display state and ClusterUI state.
     *
     * @param onOff 0 - off, 1 - on
     * @param bounds the area to render the cluster Activity in pixel
     * @param insets Insets of the cluster display
     * @param uiTypeMain uiType that ClusterHome tries to show in main area
     * @param uiTypeSub uiType that ClusterHome tries to show in sub area
     * @param uiAvailability the byte array to represent the availability of ClusterUI.
     */
    public void reportState(int onOff, Rect bounds, Insets insets,
            int uiTypeMain, int uiTypeSub, byte[] uiAvailability) {
        if (!isPropertyAvailable(CLUSTER_REPORT_STATE)) {
            throw new IllegalStateException(
                    "reportState: CLUSTER_REPORT_STATE property is not supported on this device");
        }
        int[] intValues = new int[]{
            onOff,
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            insets.left,
            insets.top,
            insets.right,
            insets.bottom,
            uiTypeMain,
            uiTypeSub
        };
        HalPropValue request = mPropValueBuilder.build(CLUSTER_REPORT_STATE,
                /* areaId= */ 0, SystemClock.elapsedRealtime(), VehiclePropertyStatus.AVAILABLE,
                /* int32Values= */ intValues, /* floatValues= */ new float[0],
                /* int64Values= */ new long[0], /* stringValue= */ new String(),
                /* byteValues= */ uiAvailability);
        send(request);
    }

    /**
     * Requests to turn the cluster display on to show some ClusterUI.
     *
     * @param uiType uiType that ClusterHome tries to show in main area
     */
    public void requestDisplay(int uiType) {
        if (!isPropertyAvailable(CLUSTER_REQUEST_DISPLAY)) {
            throw new IllegalStateException("requestDisplay: CLUSTER_REQUEST_DISPLAY property is "
                    + "not supported on this device");
        }
        HalPropValue request = mPropValueBuilder.build(CLUSTER_REQUEST_DISPLAY,
                /* areaId= */ 0, SystemClock.elapsedRealtime(), VehiclePropertyStatus.AVAILABLE,
                /* value= */ uiType);
        send(request);
    }


    /**
     * Informs the current navigation state.
     *
     * @param navigateState the serialized message of {@code NavigationStateProto}
     */
    public void sendNavigationState(byte[] navigateState) {
        if (!isNavigationStateSupported()) {
            return;
        }
        HalPropValue request = mPropValueBuilder.build(CLUSTER_NAVIGATION_STATE,
                /* areaId= */ 0, SystemClock.elapsedRealtime(), VehiclePropertyStatus.AVAILABLE,
                /* value= */ navigateState);
        send(request);
    }

    private void send(HalPropValue request) {
        try {
            mHal.set(request);
        } catch (ServiceSpecificException | IllegalArgumentException e) {
            Slogf.e(TAG, "Failed to send request: " + request, e);
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(PrintWriter writer) {
        writer.println("*Cluster HAL*");
        writer.println("mAvailableProperties: " + mAvailableProperties);
    }
}
