/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.property.CarPropertyHelper.SYNC_OP_LIMIT_TRY_AGAIN;

import static java.lang.Integer.toHexString;
import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarHvacFanDirection;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.AreaIdConfig;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CruiseControlType;
import android.car.hardware.property.ErrorState;
import android.car.hardware.property.EvStoppingMode;
import android.car.hardware.property.ICarProperty;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.hardware.property.WindshieldWipersSwitch;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.car.hal.PropertyHalService;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.AsyncPropertyServiceRequestList;
import com.android.car.internal.property.CarPropertyConfigList;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.property.CarSubscribeOption;
import com.android.car.internal.property.IAsyncPropertyResultCallback;
import com.android.car.internal.property.InputSanitizationUtils;
import com.android.car.internal.util.ArrayUtils;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.property.CarPropertyServiceClient;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.modules.expresslog.Histogram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * This class implements the binder interface for ICarProperty.aidl to make it easier to create
 * multiple managers that deal with Vehicle Properties. The property Ids in this class are IDs in
 * manager level.
 */
public class CarPropertyService extends ICarProperty.Stub
        implements CarServiceBase, PropertyHalService.PropertyHalListener {
    private static final String TAG = CarLog.tagFor(CarPropertyService.class);
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    // Maximum count of sync get/set property operation allowed at once. The reason we limit this
    // is because each sync get/set property operation takes up one binder thread. If they take
    // all the binder thread, we do not have thread left for the result callback from VHAL. This
    // will cause all the pending sync operation to timeout because result cannot be delivered.
    private static final int SYNC_GET_SET_PROPERTY_OP_LIMIT = 16;
    private static final long TRACE_TAG = TraceHelper.TRACE_TAG_CAR_SERVICE;
    // A list of properties that must not set waitForPropertyUpdate to {@code true} for set async.
    private static final Set<Integer> NOT_ALLOWED_WAIT_FOR_UPDATE_PROPERTIES =
            new HashSet<>(Arrays.asList(
                VehiclePropertyIds.HVAC_TEMPERATURE_VALUE_SUGGESTION
            ));

    private static final Set<Integer> ERROR_STATES =
            new HashSet<Integer>(Arrays.asList(
                    ErrorState.OTHER_ERROR_STATE,
                    ErrorState.NOT_AVAILABLE_DISABLED,
                    ErrorState.NOT_AVAILABLE_SPEED_LOW,
                    ErrorState.NOT_AVAILABLE_SPEED_HIGH,
                    ErrorState.NOT_AVAILABLE_SAFETY
            ));
    private static final Set<Integer> CAR_HVAC_FAN_DIRECTION_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    CarHvacFanDirection.UNKNOWN
            ));
    private static final Set<Integer> CRUISE_CONTROL_TYPE_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    CruiseControlType.OTHER
            ));
    static {
        CRUISE_CONTROL_TYPE_UNWRITABLE_STATES.addAll(ERROR_STATES);
    }
    private static final Set<Integer> EV_STOPPING_MODE_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    EvStoppingMode.STATE_OTHER
            ));
    private static final Set<Integer> WINDSHIELD_WIPERS_SWITCH_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    WindshieldWipersSwitch.OTHER
            ));

    private static final SparseArray<Set<Integer>> PROPERTY_ID_TO_UNWRITABLE_STATES =
            new SparseArray<>();
    static {
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.CRUISE_CONTROL_TYPE,
                CRUISE_CONTROL_TYPE_UNWRITABLE_STATES);
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.EV_STOPPING_MODE,
                EV_STOPPING_MODE_UNWRITABLE_STATES);
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.HVAC_FAN_DIRECTION,
                CAR_HVAC_FAN_DIRECTION_UNWRITABLE_STATES);
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH,
                WINDSHIELD_WIPERS_SWITCH_UNWRITABLE_STATES);
    }

    private static final Histogram sConcurrentSyncOperationHistogram = new Histogram(
            "automotive_os.value_concurrent_sync_operations",
            new Histogram.UniformOptions(/* binCount= */ 17, /* minValue= */ 0,
                    /* exclusiveMaxValue= */ 17));

    private static final Histogram sGetPropertySyncLatencyHistogram = new Histogram(
            "automotive_os.value_sync_get_property_latency",
            new Histogram.ScaledRangeOptions(/* binCount= */ 20, /* minValue= */ 0,
                    /* firstBinWidth= */ 2, /* scaleFactor= */ 1.5f));

    private static final Histogram sSetPropertySyncLatencyHistogram = new Histogram(
            "automotive_os.value_sync_set_property_latency",
            new Histogram.ScaledRangeOptions(/* binCount= */ 20, /* minValue= */ 0,
                    /* firstBinWidth= */ 2, /* scaleFactor= */ 1.5f));

    private static final Histogram sSubscriptionUpdateRateHistogram = new Histogram(
            "automotive_os.value_subscription_update_rate",
            new Histogram.UniformOptions(/* binCount= */ 101, /* minValue= */ 0,
                    /* exclusiveMaxValue= */ 101));
    private static final Histogram sGetAsyncLatencyHistogram = new Histogram(
            "automotive_os.value_get_async_latency",
            new Histogram.UniformOptions(/* binCount= */ 20, /* minValue= */ 0,
                    /* exclusiveMaxValue= */ 1000));

    private static final Histogram sSetAsyncLatencyHistogram = new Histogram(
            "automotive_os.value_set_async_latency",
            new Histogram.UniformOptions(/* binCount= */ 20, /* minValue= */ 0,
                    /* exclusiveMaxValue= */ 1000));

    private final Context mContext;
    private final PropertyHalService mPropertyHalService;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<IBinder, CarPropertyServiceClient> mClientMap = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<List<CarPropertyServiceClient>> mPropIdClientMap =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<SparseArray<CarPropertyServiceClient>> mSetOperationClientMap =
            new SparseArray<>();
    private final HandlerThread mHandlerThread =
            CarServiceUtils.getHandlerThread(getClass().getSimpleName());
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());
    // Use SparseArray instead of map to save memory.
    @GuardedBy("mLock")
    private SparseArray<CarPropertyConfig<?>> mPropertyIdToCarPropertyConfig = new SparseArray<>();
    @GuardedBy("mLock")
    private int mSyncGetSetPropertyOpCount;

    public CarPropertyService(Context context, PropertyHalService propertyHalService) {
        if (DBG) {
            Slogf.d(TAG, "CarPropertyService started!");
        }
        mPropertyHalService = propertyHalService;
        mContext = context;
    }

    @Override
    public void init() {
        synchronized (mLock) {
            // Cache the configs list to avoid subsequent binder calls
            mPropertyIdToCarPropertyConfig = mPropertyHalService.getPropertyList();
            if (DBG) {
                Slogf.d(TAG, "cache CarPropertyConfigs " + mPropertyIdToCarPropertyConfig.size());
            }
        }
        mPropertyHalService.setPropertyHalListener(this);
    }

    @Override
    public void release() {
        synchronized (mLock) {
            mClientMap.clear();
            mPropIdClientMap.clear();
            mPropertyHalService.setPropertyHalListener(null);
            mSetOperationClientMap.clear();
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarPropertyService*");
        writer.increaseIndent();
        synchronized (mLock) {
            writer.println("There are " + mClientMap.size() + " clients that have registered"
                    + " listeners in CarPropertyService.");
            writer.println("Current sync operation count: " + mSyncGetSetPropertyOpCount);
            writer.println("Properties registered: ");
            writer.increaseIndent();
            for (int i = 0; i < mPropIdClientMap.size(); i++) {
                int propId = mPropIdClientMap.keyAt(i);
                List<CarPropertyServiceClient> clients = mPropIdClientMap.valueAt(i);
                writer.println("propId: " + VehiclePropertyIds.toString(propId)
                        + " is registered by " + clients.size() + " client(s).");
                writer.increaseIndent();
                for (int j = 0; j < clients.size(); j++) {
                    int firstAreaId = getCarPropertyConfig(propId).getAreaIds()[0];
                    float subscribedRate = clients.get(j).getUpdateRateHz(propId, firstAreaId);
                    writer.println("Client " + clients.get(j).hashCode() + ": Subscribed at "
                            + subscribedRate + " hz");
                }
                writer.decreaseIndent();
            }
            writer.decreaseIndent();
            writer.println("Properties that have a listener registered for setProperty:");
            writer.increaseIndent();
            for (int i = 0; i < mSetOperationClientMap.size(); i++) {
                int propId = mSetOperationClientMap.keyAt(i);
                SparseArray areaIdToClient = mSetOperationClientMap.valueAt(i);
                for (int j = 0; j < areaIdToClient.size(); j++) {
                    int areaId = areaIdToClient.keyAt(j);
                    writer.println("Client: " + areaIdToClient.valueAt(j).hashCode() + " propId: "
                            + VehiclePropertyIds.toString(propId)  + " areaId: 0x"
                            + toHexString(areaId));
                }
            }
            writer.decreaseIndent();
        }
        writer.decreaseIndent();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dumpProto(ProtoOutputStream proto) {}

    @Override
    public void registerListener(int propertyId, float updateRateHz,
            ICarPropertyEventListener carPropertyEventListener) throws IllegalArgumentException {
        CarSubscribeOption option = new CarSubscribeOption();
        int[] areaIds = new int[0];
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        // carPropertyConfig nullity check will be done in registerListenerWithSubscribeOptions
        if (carPropertyConfig != null) {
            areaIds = carPropertyConfig.getAreaIds();
        }
        option.propertyId = propertyId;
        option.updateRateHz = updateRateHz;
        option.areaIds = areaIds;
        registerListenerWithSubscribeOptions(List.of(option), carPropertyEventListener);
    }

    @Override
    public void registerListenerWithSubscribeOptions(List<CarSubscribeOption> subscribeOptions,
            ICarPropertyEventListener carPropertyEventListener) {
        requireNonNull(carPropertyEventListener);
        List<CarSubscribeOption> propertyHalSubscribeOptions = new ArrayList<>();

        CarPropertyServiceClient finalClient;
        synchronized (mLock) {
            for (int i = 0; i < subscribeOptions.size(); i++) {
                CarSubscribeOption option = subscribeOptions.get(i);
                int propertyId = option.propertyId;
                int[] areaIds = option.areaIds;
                float updateRateHz = option.updateRateHz;

                CarPropertyConfig<?> carPropertyConfig = validateRegisterParameterAndGetConfig(
                        propertyId, areaIds);

                option.updateRateHz = InputSanitizationUtils.sanitizeUpdateRateHz(carPropertyConfig,
                        updateRateHz);
            }
            // Get or create the client for this iCarPropertyEventListener
            IBinder listenerBinder = carPropertyEventListener.asBinder();
            CarPropertyServiceClient client = mClientMap.get(listenerBinder);
            if (client == null) {
                client = new CarPropertyServiceClient(carPropertyEventListener,
                        this::unregisterListenerBinderForProps);
                if (client.isDead()) {
                    Slogf.w(TAG, "the ICarPropertyEventListener is already dead");
                    return;
                }
                mClientMap.put(listenerBinder, client);
            }
            for (int i = 0; i < subscribeOptions.size(); i++) {
                CarSubscribeOption option = subscribeOptions.get(i);
                int propertyId = option.propertyId;
                int[] areaIds = option.areaIds;
                float sanitizedUpdateRateHz = option.updateRateHz;
                sSubscriptionUpdateRateHistogram.logSample(sanitizedUpdateRateHz);
                if (DBG) {
                    Slogf.d(TAG, "registerListener after update rate sanitization, options: "
                            + subscribeOptions.get(i));
                }
                client.addProperty(propertyId, areaIds, sanitizedUpdateRateHz);
                // Insert the client into the propertyId --> clients map
                List<CarPropertyServiceClient> clients = mPropIdClientMap.get(propertyId);
                if (clients == null) {
                    clients = new ArrayList<>();
                    mPropIdClientMap.put(propertyId, clients);
                }
                if (!clients.contains(client)) {
                    clients.add(client);
                }
                CarSubscribeOption filteredOptions = filterSubscribeOptionAreaIds(option);
                if (DBG) {
                    Slogf.d(TAG, "The filtered subscribe options are: %s", filteredOptions);
                }
                if (filteredOptions != null) {
                    propertyHalSubscribeOptions.add(filteredOptions);
                }
            }
            finalClient = client;
        }

        if (!propertyHalSubscribeOptions.isEmpty()) {
            mPropertyHalService.subscribeProperty(propertyHalSubscribeOptions);
        }
        mHandler.post(() ->
                getAndDispatchPropertyInitValue(subscribeOptions, finalClient));
    }

    /**
     * Register property listener for car service's internal usage.
     */
    public boolean registerListenerSafe(int propertyId, float updateRateHz,
            ICarPropertyEventListener iCarPropertyEventListener) {
        try {
            registerListener(propertyId, updateRateHz, iCarPropertyEventListener);
            return true;
        } catch (Exception e) {
            Slogf.e(TAG, e, "registerListenerSafe() failed for property ID: %s updateRateHz: %f",
                    VehiclePropertyIds.toString(propertyId), updateRateHz);
            return false;
        }
    }

    private CarSubscribeOption filterSubscribeOptionAreaIds(CarSubscribeOption option) {
        CarSubscribeOption filteredOption = new CarSubscribeOption();
        int[] areaIds = option.areaIds;
        int propertyId = option.propertyId;
        float sanitizedUpdateRateHz = option.updateRateHz;
        List<Integer> filteredAreaIds = new ArrayList<>();
        for (int areaId : areaIds) {
            if (sanitizedUpdateRateHz > mPropertyHalService.getSubscribedUpdateRateHz(
                    propertyId, areaId)) {
                filteredAreaIds.add(areaId);
            }
        }
        if (filteredAreaIds.isEmpty()) {
            if (DBG) {
                Slogf.d(TAG, "Filtered out %s because they are already subscribed at an equal or "
                        + "higher rate.", option);
            }
            return null;
        }
        filteredOption.propertyId = propertyId;
        filteredOption.areaIds = CarServiceUtils.toIntArray(filteredAreaIds);
        filteredOption.updateRateHz = option.updateRateHz;
        return filteredOption;
    }

    private void getAndDispatchPropertyInitValue(List<CarSubscribeOption> subscribeOptions,
            CarPropertyServiceClient client) {
        List<CarPropertyEvent> events = new ArrayList<>();
        for (int i = 0; i < subscribeOptions.size(); i++) {
            CarSubscribeOption option = subscribeOptions.get(i);
            int propertyId = option.propertyId;
            int[] areaIds = option.areaIds;
            for (int areaId : areaIds) {
                CarPropertyValue carPropertyValue = null;
                try {
                    carPropertyValue = getProperty(propertyId, areaId);
                } catch (ServiceSpecificException e) {
                    Slogf.w("Get initial carPropertyValue for registerCallback failed -"
                                    + " property ID: %s, area ID $s, exception: %s",
                            VehiclePropertyIds.toString(propertyId), Integer.toHexString(areaId),
                            e);
                    int errorCode = CarPropertyHelper.getVhalSystemErrorCode(e.errorCode);
                    long timestampNanos = SystemClock.elapsedRealtimeNanos();
                    CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
                    Object defaultValue = CarPropertyHelper.getDefaultValue(
                            carPropertyConfig.getPropertyType());
                    if (CarPropertyHelper.isNotAvailableVehicleHalStatusCode(errorCode)) {
                        carPropertyValue = new CarPropertyValue<>(propertyId, areaId,
                                CarPropertyValue.STATUS_UNAVAILABLE, timestampNanos, defaultValue);
                    } else {
                        carPropertyValue = new CarPropertyValue<>(propertyId, areaId,
                                CarPropertyValue.STATUS_ERROR, timestampNanos, defaultValue);
                    }
                } catch (Exception e) {
                    // Do nothing.
                    Slogf.e("Get initial carPropertyValue for registerCallback failed -"
                                    + " property ID: %s, area ID $s, exception: %s",
                            VehiclePropertyIds.toString(propertyId), Integer.toHexString(areaId),
                            e);
                }
                if (carPropertyValue != null) {
                    CarPropertyEvent event = new CarPropertyEvent(
                            CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, carPropertyValue);
                    events.add(event);
                }
            }
        }

        if (events.isEmpty()) {
            return;
        }
        try {
            client.onEvent(events);
        } catch (RemoteException ex) {
            // If we cannot send a record, it's likely the connection snapped. Let the binder
            // death handle the situation.
            Slogf.e(TAG, "onEvent calling failed", ex);
        }
    }

    @Override
    public void unregisterListener(int propertyId,
            ICarPropertyEventListener iCarPropertyEventListener) {
        requireNonNull(iCarPropertyEventListener);
        validateRegisterParameterAndGetConfig(propertyId);

        if (DBG) {
            Slogf.d(TAG,
                    "unregisterListener property ID=" + VehiclePropertyIds.toString(propertyId));
        }

        IBinder listenerBinder = iCarPropertyEventListener.asBinder();
        unregisterListenerBinderForProps(List.of(propertyId), listenerBinder);
    }

    /**
     * Unregister property listener for car service's internal usage.
     */
    public boolean unregisterListenerSafe(int propertyId,
            ICarPropertyEventListener iCarPropertyEventListener) {
        try {
            unregisterListener(propertyId, iCarPropertyEventListener);
            return true;
        } catch (Exception e) {
            Slogf.e(TAG, e, "unregisterListenerSafe() failed for property ID: %s",
                    VehiclePropertyIds.toString(propertyId));
            return false;
        }
    }


    @GuardedBy("mLock")
    private void unregisterListenerBinderLocked(int propId, IBinder listenerBinder) {
        CarPropertyServiceClient client = mClientMap.get(listenerBinder);
        List<CarPropertyServiceClient> propertyClients = mPropIdClientMap.get(propId);
        if (mPropertyIdToCarPropertyConfig.get(propId) == null) {
            // Do not attempt to unregister an invalid propId
            Slogf.e(TAG, "unregisterListener: propId is not in config list:0x%s",
                    toHexString(propId));
            return;
        }
        if ((client == null) || (propertyClients == null)) {
            Slogf.e(TAG, "unregisterListenerBinderLocked: Listener was not previously "
                    + "registered.");
            return;
        }
        if (propertyClients.remove(client)) {
            boolean allPropertiesRemoved = client.removeProperty(propId);
            if (allPropertiesRemoved) {
                mClientMap.remove(listenerBinder);
            }
            clearSetOperationRecorderLocked(propId, client);

        } else {
            Slogf.e(TAG, "unregisterListenerBinderLocked: Listener was not registered for "
                    + "propId=0x" + toHexString(propId));
            return;
        }

        if (propertyClients.isEmpty()) {
            // Last listener for this property unsubscribed.  Clean up
            mPropIdClientMap.remove(propId);
            mSetOperationClientMap.remove(propId);
            mPropertyHalService.unsubscribeProperty(propId);
            return;
        }
        ArrayMap<Float, List<Integer>> updateRateHzToAreaIds = new ArrayMap<>();
        // Other listeners are still subscribed.  Calculate the new rate
        for (int i = 0; i < propertyClients.size(); i++) {
            CarPropertyServiceClient c = propertyClients.get(i);
            int[] areaIds = c.getAreaIds(propId);
            for (int areaId : areaIds) {
                float rate = c.getUpdateRateHz(propId, areaId);
                if (Float.compare(rate,
                        mPropertyHalService.getSubscribedUpdateRateHz(propId, areaId)) != 0) {
                    if (!updateRateHzToAreaIds.containsKey(rate)) {
                        updateRateHzToAreaIds.put(rate, new ArrayList<>());
                    }
                    updateRateHzToAreaIds.get(rate).add(areaId);
                }
            }
        }
        // Only reset the sample rate if needed
        if (updateRateHzToAreaIds.isEmpty()) {
            return;
        }
        if (DBG) {
            Slogf.d(TAG, "updating subscription in CarPropertyService for propertyId %d", propId);
            for (int i = 0; i < updateRateHzToAreaIds.size(); i++) {
                Slogf.d(TAG, "updateRate %f, areaIds: %s", updateRateHzToAreaIds.keyAt(i),
                        updateRateHzToAreaIds.valueAt(i));
            }
        }
        List<CarSubscribeOption> propertyHalSubscribeOptions =
                createPropertyHalSubscribeOptions(propId, updateRateHzToAreaIds);
        try {
            mPropertyHalService.subscribeProperty(propertyHalSubscribeOptions);
        } catch (IllegalArgumentException e) {
            Slogf.e(TAG, "failed to subscribe to propId=0x%s, error: %s",
                    toHexString(propId), e);
        }
    }

    private List<CarSubscribeOption> createPropertyHalSubscribeOptions(int propertyId,
            ArrayMap<Float, List<Integer>> updateRateHzToAreaIds) {
        List<CarSubscribeOption> propertyHalSubscribeOptions = new ArrayList<>();
        for (int i = 0; i < updateRateHzToAreaIds.size(); i++) {
            CarSubscribeOption option = new CarSubscribeOption();
            option.propertyId = propertyId;
            option.areaIds = CarServiceUtils.toIntArray(updateRateHzToAreaIds.valueAt(i));
            option.updateRateHz = updateRateHzToAreaIds.keyAt(i);
            propertyHalSubscribeOptions.add(option);
        }
        return propertyHalSubscribeOptions;
    }

    private void unregisterListenerBinderForProps(List<Integer> propIds, IBinder listenerBinder) {
        synchronized (mLock) {
            for (int i = 0; i < propIds.size(); i++) {
                int propId = propIds.get(i);
                unregisterListenerBinderLocked(propId, listenerBinder);
            }
        }
    }

    /**
     * Return the list of properties' configs that the caller may access.
     */
    @NonNull
    @Override
    public CarPropertyConfigList getPropertyList() {
        int[] allPropId;
        // Avoid permission checking under lock.
        synchronized (mLock) {
            allPropId = new int[mPropertyIdToCarPropertyConfig.size()];
            for (int i = 0; i < mPropertyIdToCarPropertyConfig.size(); i++) {
                allPropId[i] = mPropertyIdToCarPropertyConfig.keyAt(i);
            }
        }
        return getPropertyConfigList(allPropId);
    }

    /**
     * @param propIds Array of property Ids
     * @return the list of properties' configs that the caller may access.
     */
    @NonNull
    @Override
    public CarPropertyConfigList getPropertyConfigList(int[] propIds) {
        List<CarPropertyConfig> availableProp = new ArrayList<>();
        if (propIds == null) {
            return new CarPropertyConfigList(availableProp);
        }
        for (int propId : propIds) {
            synchronized (mLock) {
                // Check if context already granted permission first
                if ((mPropertyHalService.isReadable(mContext, propId)
                        || mPropertyHalService.isWritable(mContext, propId))
                        && mPropertyIdToCarPropertyConfig.contains(propId)) {
                    availableProp.add(mPropertyIdToCarPropertyConfig.get(propId));
                }
            }
        }
        if (DBG) {
            Slogf.d(TAG, "getPropertyList returns " + availableProp.size() + " configs");
        }
        return new CarPropertyConfigList(availableProp);
    }

    @Nullable
    private <V> V runSyncOperationCheckLimit(Callable<V> c) {
        synchronized (mLock) {
            if (mSyncGetSetPropertyOpCount >= SYNC_GET_SET_PROPERTY_OP_LIMIT) {
                sConcurrentSyncOperationHistogram.logSample(mSyncGetSetPropertyOpCount);
                throw new ServiceSpecificException(SYNC_OP_LIMIT_TRY_AGAIN);
            }
            mSyncGetSetPropertyOpCount += 1;
            sConcurrentSyncOperationHistogram.logSample(mSyncGetSetPropertyOpCount);
            if (DBG) {
                Slogf.d(TAG, "mSyncGetSetPropertyOpCount: %d", mSyncGetSetPropertyOpCount);
            }
        }
        try {
            Trace.traceBegin(TRACE_TAG, "call sync operation");
            return c.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Slogf.e(TAG, e, "catching unexpected exception for getProperty/setProperty");
            return null;
        } finally {
            Trace.traceEnd(TRACE_TAG);
            synchronized (mLock) {
                mSyncGetSetPropertyOpCount -= 1;
                if (DBG) {
                    Slogf.d(TAG, "mSyncGetSetPropertyOpCount: %d", mSyncGetSetPropertyOpCount);
                }
            }
        }
    }

    @Override
    public CarPropertyValue getProperty(int propertyId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        validateGetParameters(propertyId, areaId);
        Trace.traceBegin(TRACE_TAG, "CarPropertyValue#getProperty");
        long currentTimeMs = System.currentTimeMillis();
        try {
            return runSyncOperationCheckLimit(() -> {
                return mPropertyHalService.getProperty(propertyId, areaId);
            });
        } finally {
            if (DBG) {
                Slogf.d(TAG, "Latency of getPropertySync is: %f", (float) (System
                        .currentTimeMillis() - currentTimeMs));
            }
            sGetPropertySyncLatencyHistogram.logSample((float) (System.currentTimeMillis()
                    - currentTimeMs));
            Trace.traceEnd(TRACE_TAG);
        }
    }

    /**
     * Get property value for car service's internal usage.
     *
     * @return null if property is not implemented or there is an exception in the vehicle.
     */
    @Nullable
    public CarPropertyValue getPropertySafe(int propertyId, int areaId) {
        try {
            return getProperty(propertyId, areaId);
        } catch (Exception e) {
            Slogf.e(TAG, e, "getPropertySafe() failed for property id: %s area id: 0x%s",
                    VehiclePropertyIds.toString(propertyId), toHexString(areaId));
            return null;
        }
    }

    /**
     * Return read permission string for given property ID. The format of the return value of this
     * function has changed over time and thus should not be relied on.
     *
     * @param propId the property ID to query
     * @return the permission needed to read this property, {@code null} if the property ID is not
     * available
     */
    @Nullable
    @Override
    public String getReadPermission(int propId) {
        return mPropertyHalService.getReadPermission(propId);
    }

    /**
     * Return write permission string for given property ID. The format of the return value of this
     * function has changed over time and thus should not be relied on.
     *
     * @param propId the property ID to query
     * @return the permission needed to write this property, {@code null} if the property ID is not
     * available
     */
    @Nullable
    @Override
    public String getWritePermission(int propId) {
        return mPropertyHalService.getWritePermission(propId);
    }

    @Override
    public void setProperty(CarPropertyValue carPropertyValue,
            ICarPropertyEventListener iCarPropertyEventListener)
            throws IllegalArgumentException, ServiceSpecificException {
        requireNonNull(iCarPropertyEventListener);
        validateSetParameters(carPropertyValue);
        long currentTimeMs = System.currentTimeMillis();

        runSyncOperationCheckLimit(() -> {
            mPropertyHalService.setProperty(carPropertyValue);
            return null;
        });

        IBinder listenerBinder = iCarPropertyEventListener.asBinder();
        synchronized (mLock) {
            CarPropertyServiceClient client = mClientMap.get(listenerBinder);
            if (client == null) {
                client = new CarPropertyServiceClient(iCarPropertyEventListener,
                        this::unregisterListenerBinderForProps);
            }
            if (client.isDead()) {
                Slogf.w(TAG, "the ICarPropertyEventListener is already dead");
                return;
            }
            mClientMap.put(listenerBinder, client);
            updateSetOperationRecorderLocked(carPropertyValue.getPropertyId(),
                    carPropertyValue.getAreaId(), client);
            if (DBG) {
                Slogf.d(TAG, "Latency of setPropertySync is: %f", (float) (System
                        .currentTimeMillis() - currentTimeMs));
            }
            sSetPropertySyncLatencyHistogram.logSample((float) (System.currentTimeMillis()
                    - currentTimeMs));
        }
    }

    // Updates recorder for set operation.
    @GuardedBy("mLock")
    private void updateSetOperationRecorderLocked(int propId, int areaId,
            CarPropertyServiceClient client) {
        if (mSetOperationClientMap.get(propId) != null) {
            mSetOperationClientMap.get(propId).put(areaId, client);
        } else {
            SparseArray<CarPropertyServiceClient> areaIdToClient = new SparseArray<>();
            areaIdToClient.put(areaId, client);
            mSetOperationClientMap.put(propId, areaIdToClient);
        }
    }

    // Clears map when client unregister for property.
    @GuardedBy("mLock")
    private void clearSetOperationRecorderLocked(int propId, CarPropertyServiceClient client) {
        SparseArray<CarPropertyServiceClient> areaIdToClient = mSetOperationClientMap.get(propId);
        if (areaIdToClient != null) {
            List<Integer> indexNeedToRemove = new ArrayList<>();
            for (int index = 0; index < areaIdToClient.size(); index++) {
                if (client.equals(areaIdToClient.valueAt(index))) {
                    indexNeedToRemove.add(index);
                }
            }

            for (int index : indexNeedToRemove) {
                if (DBG) {
                    Slogf.d("ErrorEvent", " Clear propId:0x" + toHexString(propId)
                            + " areaId: 0x" + toHexString(areaIdToClient.keyAt(index)));
                }
                areaIdToClient.removeAt(index);
            }
        }
    }

    // Implement PropertyHalListener interface
    @Override
    public void onPropertyChange(List<CarPropertyEvent> events) {
        Map<CarPropertyServiceClient, List<CarPropertyEvent>> eventsToDispatch = new ArrayMap<>();
        synchronized (mLock) {
            for (int i = 0; i < events.size(); i++) {
                CarPropertyEvent event = events.get(i);
                int propId = event.getCarPropertyValue().getPropertyId();
                List<CarPropertyServiceClient> clients = mPropIdClientMap.get(propId);
                if (clients == null) {
                    Slogf.e(TAG, "onPropertyChange: no listener registered for propId=0x%s",
                            toHexString(propId));
                    continue;
                }

                for (int j = 0; j < clients.size(); j++) {
                    CarPropertyServiceClient c = clients.get(j);
                    List<CarPropertyEvent> p = eventsToDispatch.get(c);
                    if (p == null) {
                        // Initialize the linked list for the listener
                        p = new ArrayList<CarPropertyEvent>();
                        eventsToDispatch.put(c, p);
                    }
                    p.add(event);
                }
            }
        }

        // Parse the dispatch list to send events. We must call the callback outside the
        // scoped lock since the callback might call some function in CarPropertyService
        // which might cause deadlock.
        // In rare cases, if this specific client is unregistered after the lock but before
        // the callback, we would call callback on an unregistered client which should be ok because
        // 'onEvent' is an async oneway callback that might be delivered after unregistration
        // anyway.
        for (CarPropertyServiceClient client : eventsToDispatch.keySet()) {
            try {
                client.onEvent(eventsToDispatch.get(client));
            } catch (RemoteException ex) {
                // If we cannot send a record, it's likely the connection snapped. Let binder
                // death handle the situation.
                Slogf.e(TAG, "onEvent calling failed: " + ex);
            }
        }
    }

    @Override
    public void onPropertySetError(int property, int areaId, int errorCode) {
        CarPropertyServiceClient lastOperatedClient = null;
        synchronized (mLock) {
            if (mSetOperationClientMap.get(property) != null
                    && mSetOperationClientMap.get(property).get(areaId) != null) {
                lastOperatedClient = mSetOperationClientMap.get(property).get(areaId);
            } else {
                Slogf.e(TAG, "Can not find the client changed propertyId: 0x"
                        + toHexString(property) + " in areaId: 0x" + toHexString(areaId));
            }

        }
        if (lastOperatedClient != null) {
            dispatchToLastClient(property, areaId, errorCode, lastOperatedClient);
        }
    }

    private void dispatchToLastClient(int property, int areaId, int errorCode,
            CarPropertyServiceClient lastOperatedClient) {
        try {
            List<CarPropertyEvent> eventList = new ArrayList<>();
            eventList.add(
                    CarPropertyEvent.createErrorEventWithErrorCode(property, areaId,
                            errorCode));
            lastOperatedClient.onEvent(eventList);
        } catch (RemoteException ex) {
            Slogf.e(TAG, "onEvent calling failed: " + ex);
        }
    }

    private static void validateGetSetAsyncParameters(AsyncPropertyServiceRequestList requests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) throws IllegalArgumentException {
        requireNonNull(requests);
        requireNonNull(asyncPropertyResultCallback);
        Preconditions.checkArgument(timeoutInMs > 0, "timeoutInMs must be a positive number");
    }

    /**
     * Gets CarPropertyValues asynchronously.
     */
    @Override
    public void getPropertiesAsync(
            AsyncPropertyServiceRequestList getPropertyServiceRequestsParcelable,
            IAsyncPropertyResultCallback asyncPropertyResultCallback, long timeoutInMs) {
        validateGetSetAsyncParameters(getPropertyServiceRequestsParcelable,
                asyncPropertyResultCallback, timeoutInMs);
        long currentTime = System.currentTimeMillis();
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests =
                getPropertyServiceRequestsParcelable.getList();
        for (int i = 0; i < getPropertyServiceRequests.size(); i++) {
            validateGetParameters(getPropertyServiceRequests.get(i).getPropertyId(),
                    getPropertyServiceRequests.get(i).getAreaId());
        }
        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                asyncPropertyResultCallback, timeoutInMs, currentTime);
        if (DBG) {
            Slogf.d(TAG, "Latency of getPropertyAsync is: %f", (float) (System
                    .currentTimeMillis() - currentTime));
        }
        sGetAsyncLatencyHistogram.logSample((float) (System.currentTimeMillis() - currentTime));
    }

    /**
     * Sets CarPropertyValues asynchronously.
     */
    @SuppressWarnings("FormatString")
    @Override
    public void setPropertiesAsync(AsyncPropertyServiceRequestList setPropertyServiceRequests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) {
        validateGetSetAsyncParameters(setPropertyServiceRequests, asyncPropertyResultCallback,
                timeoutInMs);
        long currentTime = System.currentTimeMillis();
        List<AsyncPropertyServiceRequest> setPropertyServiceRequestList =
                setPropertyServiceRequests.getList();
        for (int i = 0; i < setPropertyServiceRequestList.size(); i++) {
            AsyncPropertyServiceRequest request = setPropertyServiceRequestList.get(i);
            CarPropertyValue carPropertyValueToSet = request.getCarPropertyValue();
            int propertyId = request.getPropertyId();
            int valuePropertyId = carPropertyValueToSet.getPropertyId();
            int areaId = request.getAreaId();
            int valueAreaId = carPropertyValueToSet.getAreaId();
            String propertyName = VehiclePropertyIds.toString(propertyId);
            if (valuePropertyId != propertyId) {
                throw new IllegalArgumentException(String.format(
                        "Property ID in request and CarPropertyValue mismatch: %s vs %s",
                        VehiclePropertyIds.toString(valuePropertyId), propertyName).toString());
            }
            if (valueAreaId != areaId) {
                throw new IllegalArgumentException(String.format(
                        "For property: %s, area ID in request and CarPropertyValue mismatch: %d vs"
                        + " %d", propertyName, valueAreaId, areaId).toString());
            }
            validateSetParameters(carPropertyValueToSet);
            if (request.isWaitForPropertyUpdate()) {
                if (NOT_ALLOWED_WAIT_FOR_UPDATE_PROPERTIES.contains(propertyId)) {
                    throw new IllegalArgumentException("Property: "
                            + propertyName + " must set waitForPropertyUpdate to false");
                }
                validateGetParameters(propertyId, areaId);
            }
        }
        mPropertyHalService.setCarPropertyValuesAsync(setPropertyServiceRequestList,
                asyncPropertyResultCallback, timeoutInMs, currentTime);
        if (DBG) {
            Slogf.d(TAG, "Latency of setPropertyAsync is: %f", (float) (System
                    .currentTimeMillis() - currentTime));
        }
        sSetAsyncLatencyHistogram.logSample((float) (System.currentTimeMillis() - currentTime));
    }

    /**
     * Cancel on-going async requests.
     *
     * @param serviceRequestIds A list of async get/set property request IDs.
     */
    @Override
    public void cancelRequests(int[] serviceRequestIds) {
        mPropertyHalService.cancelRequests(serviceRequestIds);
    }

    private static void assertPropertyIsReadable(CarPropertyConfig<?> carPropertyConfig) {
        Preconditions.checkArgument(
                carPropertyConfig.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                        || carPropertyConfig.getAccess()
                        == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE,
                "Property is not readable: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()));
    }

    private static void assertConfigIsNotNull(int propertyId,
            CarPropertyConfig<?> carPropertyConfig) {
        Preconditions.checkArgument(carPropertyConfig != null,
                "property ID is not in carPropertyConfig list, and so it is not supported: %s",
                VehiclePropertyIds.toString(propertyId));
    }

    private static void assertAreaIdIsSupported(int areaId,
            CarPropertyConfig<?> carPropertyConfig) {
        Preconditions.checkArgument(ArrayUtils.contains(carPropertyConfig.getAreaIds(), areaId),
                "area ID: 0x" + toHexString(areaId) + " not supported for property ID: "
                        + VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()));
    }

    @Nullable
    private CarPropertyConfig<?> getCarPropertyConfig(int propertyId) {
        CarPropertyConfig<?> carPropertyConfig;
        synchronized (mLock) {
            carPropertyConfig = mPropertyIdToCarPropertyConfig.get(propertyId);
        }
        return carPropertyConfig;
    }

    private void assertReadPermissionGranted(int propertyId) {
        if (!mPropertyHalService.isReadable(mContext, propertyId)) {
            throw new SecurityException(
                    "Platform does not have permission to read value for property ID: "
                            + VehiclePropertyIds.toString(propertyId));
        }
    }

    private CarPropertyConfig validateRegisterParameterAndGetConfig(int propertyId) {
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        assertConfigIsNotNull(propertyId, carPropertyConfig);
        assertPropertyIsReadable(carPropertyConfig);
        assertReadPermissionGranted(propertyId);
        return carPropertyConfig;
    }

    private CarPropertyConfig validateRegisterParameterAndGetConfig(int propertyId,
            int[] areaIds) {
        CarPropertyConfig<?> carPropertyConfig = validateRegisterParameterAndGetConfig(propertyId);
        Preconditions.checkArgument(areaIds != null, "AreaIds must not be null");
        for (int i = 0; i < areaIds.length; i++) {
            assertAreaIdIsSupported(areaIds[i], carPropertyConfig);
        }
        return carPropertyConfig;
    }

    private void validateGetParameters(int propertyId, int areaId) {
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        assertConfigIsNotNull(propertyId, carPropertyConfig);
        assertPropertyIsReadable(carPropertyConfig);
        assertReadPermissionGranted(propertyId);
        assertAreaIdIsSupported(areaId, carPropertyConfig);
    }

    private void validateSetParameters(CarPropertyValue<?> carPropertyValue) {
        requireNonNull(carPropertyValue);
        int propertyId = carPropertyValue.getPropertyId();
        int areaId = carPropertyValue.getAreaId();
        Object valueToSet = carPropertyValue.getValue();
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        assertConfigIsNotNull(propertyId, carPropertyConfig);

        // Assert property is writable.
        Preconditions.checkArgument(
                carPropertyConfig.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE
                        || carPropertyConfig.getAccess()
                        == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE,
                "Property is not writable: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()));

        // Assert write permission is granted.
        if (!mPropertyHalService.isWritable(mContext, propertyId)) {
            throw new SecurityException(
                    "Platform does not have permission to write value for property ID: "
                            + VehiclePropertyIds.toString(propertyId));
        }

        assertAreaIdIsSupported(areaId, carPropertyConfig);

        // Assert set value is valid for property.
        Preconditions.checkArgument(valueToSet != null,
                "setProperty: CarPropertyValue's must not be null - property ID: %s area ID: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                toHexString(areaId));
        Preconditions.checkArgument(
                valueToSet.getClass().equals(carPropertyConfig.getPropertyType()),
                "setProperty: CarPropertyValue's value's type does not match property's type. - "
                        + "property ID: %s area ID: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                toHexString(areaId));

        AreaIdConfig<?> areaIdConfig = carPropertyConfig.getAreaIdConfig(areaId);
        if (areaIdConfig.getMinValue() != null) {
            boolean isGreaterThanOrEqualToMinValue = false;
            if (carPropertyConfig.getPropertyType().equals(Integer.class)) {
                isGreaterThanOrEqualToMinValue =
                        (Integer) valueToSet >= (Integer) areaIdConfig.getMinValue();
            } else if (carPropertyConfig.getPropertyType().equals(Long.class)) {
                isGreaterThanOrEqualToMinValue =
                        (Long) valueToSet >= (Long) areaIdConfig.getMinValue();
            } else if (carPropertyConfig.getPropertyType().equals(Float.class)) {
                isGreaterThanOrEqualToMinValue =
                        (Float) valueToSet >= (Float) areaIdConfig.getMinValue();
            }
            Preconditions.checkArgument(isGreaterThanOrEqualToMinValue,
                    "setProperty: value to set must be greater than or equal to the area ID min "
                            + "value. - " + "property ID: %s area ID: 0x%s min value: %s",
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId), areaIdConfig.getMinValue());

        }

        if (areaIdConfig.getMaxValue() != null) {
            boolean isLessThanOrEqualToMaxValue = false;
            if (carPropertyConfig.getPropertyType().equals(Integer.class)) {
                isLessThanOrEqualToMaxValue =
                        (Integer) valueToSet <= (Integer) areaIdConfig.getMaxValue();
            } else if (carPropertyConfig.getPropertyType().equals(Long.class)) {
                isLessThanOrEqualToMaxValue =
                        (Long) valueToSet <= (Long) areaIdConfig.getMaxValue();
            } else if (carPropertyConfig.getPropertyType().equals(Float.class)) {
                isLessThanOrEqualToMaxValue =
                        (Float) valueToSet <= (Float) areaIdConfig.getMaxValue();
            }
            Preconditions.checkArgument(isLessThanOrEqualToMaxValue,
                    "setProperty: value to set must be less than or equal to the area ID max "
                            + "value. - " + "property ID: %s area ID: 0x%s min value: %s",
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId), areaIdConfig.getMaxValue());

        }

        if (!areaIdConfig.getSupportedEnumValues().isEmpty()) {
            Preconditions.checkArgument(areaIdConfig.getSupportedEnumValues().contains(valueToSet),
                    "setProperty: value to set must exist in set of supported enum values. - "
                            + "property ID: %s area ID: 0x%s supported enum values: %s",
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId), areaIdConfig.getSupportedEnumValues());
        }

        if (PROPERTY_ID_TO_UNWRITABLE_STATES.contains(carPropertyConfig.getPropertyId())) {
            Preconditions.checkArgument(!(PROPERTY_ID_TO_UNWRITABLE_STATES
                    .get(carPropertyConfig.getPropertyId()).contains(valueToSet)),
                    "setProperty: value to set: %s must not be an unwritable state value. - "
                            + "property ID: %s area ID: 0x%s unwritable states: %s",
                    valueToSet,
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId),
                    PROPERTY_ID_TO_UNWRITABLE_STATES.get(carPropertyConfig.getPropertyId()));
        }
    }
}
