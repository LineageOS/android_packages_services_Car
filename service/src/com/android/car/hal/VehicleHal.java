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

package com.android.car.hal;

import static android.os.SystemClock.uptimeMillis;

import static com.android.car.hal.property.HalPropertyDebugUtils.toAccessString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toAreaIdString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toAreaTypeString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toChangeModeString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toGroupString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toHalPropIdAreaIdString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toHalPropIdAreaIdsString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toPropertyIdString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toValueTypeString;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.CheckResult;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.BuildHelper;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.car.feature.FeatureFlags;
import android.car.feature.FeatureFlagsImpl;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.Context;
import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.CarSystemService;
import com.android.car.VehicleStub;
import com.android.car.VehicleStub.MinMaxSupportedRawPropValues;
import com.android.car.VehicleStub.SubscriptionClient;
import com.android.car.hal.fakevhal.SimulationVehicleStub;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.common.DispatchList;
import com.android.car.internal.property.PropIdAreaId;
import com.android.car.internal.util.ImmutablePairSparseArray;
import com.android.car.internal.util.ImmutableSparseArray;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.internal.util.Lists;
import com.android.car.internal.util.PairSparseArray;
import com.android.car.systeminterface.DisplayHelperInterface;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Abstraction for vehicle HAL. This class handles interface with native HAL and does basic parsing
 * of received data (type check). Then each event is sent to corresponding {@link HalServiceBase}
 * implementation. It is the responsibility of {@link HalServiceBase} to convert data to
 * corresponding Car*Service for Car*Manager API.
 */
public class VehicleHal implements VehicleHalCallback, CarSystemService {
    private static final boolean DBG = Slogf.isLoggable(CarLog.TAG_HAL, Log.DEBUG);
    private static final long TRACE_TAG = TraceHelper.TRACE_TAG_CAR_SERVICE;

    private static final int GLOBAL_AREA_ID = 0;

    /**
     * If call to vehicle HAL returns StatusCode.TRY_AGAIN, we will retry to invoke that method
     * again for this amount of milliseconds.
     */
    private static final int MAX_DURATION_FOR_RETRIABLE_RESULT_MS = 2000;

    private static final int SLEEP_BETWEEN_RETRIABLE_INVOKES_MS = 100;
    private static final float PRECISION_THRESHOLD = 0.001f;

    // The timeout in seconds for the default executor to temrinate.
    private static final int EXECUTOR_TERMINATE_TIMEOUT_SECONDS = 10;

    private final SubscriptionClient mSubscriptionClient;

    private final PowerHalService mPowerHal;
    private final PropertyHalService mPropertyHal;
    private final InputHalService mInputHal;
    private final VmsHalService mVmsHal;
    private final UserHalService mUserHal;
    private final DiagnosticHalService mDiagnosticHal;
    private final ClusterHalService mClusterHalService;
    private final EvsHalService mEvsHal;
    private final TimeHalService mTimeHalService;
    private final HalPropValueBuilder mPropValueBuilder;
    private final AtomicReference<VehicleStub> mSimulationVehicleStub = new AtomicReference<>();
    private AtomicReference<VehicleStub> mVehicleStub;

    private final AtomicReference<ExecutorService> mDefaultExecutorRef = new AtomicReference<>();
    private final ConcurrentHashMap<HalServiceBase, Executor> mExecutorByService =
            new ConcurrentHashMap<>();

    // Only updated during constructor.
    private final List<HalServiceBase> mCreatedHalServices = new ArrayList<>();

    private final Object mLock = new Object();

    private FeatureFlags mFeatureFlags = new FeatureFlagsImpl();

    // Only changed for test.
    private int mMaxDurationForRetryMs = MAX_DURATION_FOR_RETRIABLE_RESULT_MS;
    // Only changed for test.
    private int mSleepBetweenRetryMs = SLEEP_BETWEEN_RETRIABLE_INVOKES_MS;

    /** Stores handler for each HAL property. Property events are sent to handler. */
    @GuardedBy("mLock")
    private final SparseArray<HalServiceBase> mPropertyHandlers = new SparseArray<>();
    // This is for iterating all HalServices with fixed order. Only initialized during
    // constructor.
    private final List<HalServiceBase> mAllServices;
    @GuardedBy("mLock")
    private PairSparseArray<RateInfo> mRateInfoByPropIdAreaId = new PairSparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<HalServiceBase, ArraySet<PropIdAreaId>>
            mSupportedValuesChangePropIdAreaIdsByService = new ArrayMap<>();

    @GuardedBy("mLock")
    private final SparseArray<VehiclePropertyEventInfo> mEventLog = new SparseArray<>();

    // Used by injectVHALEvent for testing purposes.  Delimiter for an array of data
    private static final String DATA_DELIMITER = ",";
    private final AtomicReference<RecordingListenerHandler> mListenerHandlerRef =
            new AtomicReference<>();
    private final AtomicReference<ImmutableSparseArray<HalPropConfig>> mPropertyConfigsByPropIdRef =
            new AtomicReference<>(new ImmutableSparseArray<>(new SparseArray<>()));
    private final AtomicReference<ImmutablePairSparseArray<Integer>> mAccessByPropIdAreaIdRef =
            new AtomicReference<>(new ImmutablePairSparseArray<>(new PairSparseArray<>()));

    /** A structure to store update rate in hz and whether to enable VUR. */
    private static final class RateInfo {
        public float updateRateHz;
        public boolean enableVariableUpdateRate;
        public float resolution;

        RateInfo(float updateRateHz, boolean enableVariableUpdateRate, float resolution) {
            this.updateRateHz = updateRateHz;
            this.enableVariableUpdateRate = enableVariableUpdateRate;
            this.resolution = resolution;
        }
    }

    /* package */ static final class HalSubscribeOptions {
        private final int mHalPropId;
        private final int[] mAreaIds;
        private final float mUpdateRateHz;
        private final boolean mEnableVariableUpdateRate;
        private final float mResolution;

        HalSubscribeOptions(int halPropId, int[] areaIds, float updateRateHz) {
            this(halPropId, areaIds, updateRateHz, /* enableVariableUpdateRate= */ false,
                    /* resolution= */ 0.0f);
        }

        HalSubscribeOptions(int halPropId, int[] areaIds, float updateRateHz,
                boolean enableVariableUpdateRate) {
            this(halPropId, areaIds, updateRateHz, enableVariableUpdateRate,
                    /* resolution= */ 0.0f);
        }

        HalSubscribeOptions(int halPropId, int[] areaIds, float updateRateHz,
                            boolean enableVariableUpdateRate, float resolution) {
            mHalPropId = halPropId;
            mAreaIds = areaIds;
            mUpdateRateHz = updateRateHz;
            mEnableVariableUpdateRate = enableVariableUpdateRate;
            mResolution = resolution;
        }

        int getHalPropId() {
            return mHalPropId;
        }

        int[] getAreaId() {
            return mAreaIds;
        }

        float getUpdateRateHz() {
            return mUpdateRateHz;
        }

        boolean isVariableUpdateRateEnabled() {
            return mEnableVariableUpdateRate;
        }
        float getResolution() {
            return mResolution;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof VehicleHal.HalSubscribeOptions)) {
                return false;
            }

            VehicleHal.HalSubscribeOptions o = (VehicleHal.HalSubscribeOptions) other;

            return mHalPropId == o.getHalPropId() && mUpdateRateHz == o.getUpdateRateHz()
                    && Arrays.equals(mAreaIds, o.getAreaId())
                    && mEnableVariableUpdateRate == o.isVariableUpdateRateEnabled()
                    && mResolution == o.getResolution();
        }

        @Override
        public String toString() {
            return "HalSubscribeOptions{"
                    + "PropertyId: " + mHalPropId
                    + ", AreaId: " + Arrays.toString(mAreaIds)
                    + ", UpdateRateHz: " + mUpdateRateHz
                    + ", enableVariableUpdateRate: " + mEnableVariableUpdateRate
                    + ", Resolution: " + mResolution
                    + "}";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mHalPropId, Arrays.hashCode(mAreaIds), mUpdateRateHz,
                    mEnableVariableUpdateRate, mResolution);
        }
    }

    private final class PropertySetErrorDispatchList extends
            DispatchList<HalServiceBase, VehiclePropError> {
        @Override
        protected void dispatchToClient(HalServiceBase service, List<VehiclePropError> events) {
            // Copy to make sure events are not modified.
            var eventsCopy = List.copyOf(events);
            var executor = getExecutorForService(service);
            if (executor == null) {
                return;
            }
            executor.execute(() -> {
                service.onPropertySetError(eventsCopy);
            });
        }
    }

    private @Nullable Executor getExecutorForService(HalServiceBase service) {
        var executor = mExecutorByService.getOrDefault(service, mDefaultExecutorRef.get());
        if (executor == null) {
            Slogf.w(CarLog.TAG_HAL, "Default executor is null, the service is ending");
        }
        return executor;
    }

    private final class HalEventsDispatchList extends
            DispatchList<HalServiceBase, HalPropValue> {
        @Override
        protected void dispatchToClient(HalServiceBase service, List<HalPropValue> events) {
            // Copy to make sure events are not modified.
            var eventsCopy = List.copyOf(events);
            var executor = getExecutorForService(service);
            if (executor == null) {
                return;
            }
            executor.execute(() -> {
                service.onHalEvents(eventsCopy);
            });
        }
    }

    /**
     * Constructs a new {@link VehicleHal} object given the {@link Context} and {@link IVehicle}
     * both passed as parameters.
     */
    public VehicleHal(Context context, VehicleStub vehicle) {
        this(context, /* powerHal= */ null, /* propertyHal= */ null,
                /* inputHal= */ null, /* vmsHal= */ null, /* userHal= */ null,
                /* diagnosticHal= */ null, /* clusterHalService= */ null,
                /* timeHalService= */ null,
                vehicle);
    }

    /**
     * Constructs a new {@link VehicleHal} object given the services passed as parameters.
     * This method must be used by tests only.
     */
    @VisibleForTesting
    public VehicleHal(Context context,
            PowerHalService powerHal,
            PropertyHalService propertyHal,
            InputHalService inputHal,
            VmsHalService vmsHal,
            UserHalService userHal,
            DiagnosticHalService diagnosticHal,
            ClusterHalService clusterHalService,
            TimeHalService timeHalService,
            VehicleStub vehicle) {
        // Must be initialized before HalService so that HalService could use this.
        mPropValueBuilder = vehicle.getHalPropValueBuilder();
        mPowerHal = getOrCreate(powerHal, () -> new PowerHalService(context, mFeatureFlags, this,
                new DisplayHelperInterface.DefaultImpl()));
        mPropertyHal = getOrCreate(propertyHal, () -> new PropertyHalService(this));
        mInputHal = getOrCreate(inputHal, () -> new InputHalService(this));
        mVmsHal = getOrCreate(vmsHal, () -> new VmsHalService(context, this));
        mUserHal = getOrCreate(userHal, () -> new UserHalService(this));
        mDiagnosticHal = getOrCreate(diagnosticHal, () -> new DiagnosticHalService(this));
        mClusterHalService = getOrCreate(clusterHalService,
                () -> new ClusterHalService(context, this));
        mEvsHal = getOrCreate(null, () -> new EvsHalService(this));
        mTimeHalService = getOrCreate(timeHalService, () -> new TimeHalService(context, this));

        mAllServices = List.of(
                mPowerHal,
                mInputHal,
                mDiagnosticHal,
                mVmsHal,
                mUserHal,
                mClusterHalService,
                mEvsHal,
                mTimeHalService,
                // mPropertyHal must be the last so that on init/release it can be used for all
                // other HAL services properties.
                mPropertyHal);
        mVehicleStub = new AtomicReference<>(vehicle);
        mSubscriptionClient = vehicle.newSubscriptionClient(this);
    }

    private <T extends HalServiceBase> T getOrCreate(@Nullable T passedInService,
            Callable<T> createFunc) {
        if (passedInService != null) {
            return passedInService;
        }
        try {
            var service = createFunc.call();
            mCreatedHalServices.add(service);
            return service;
        } catch (Exception e) {
            // Must not happen.
            throw new RuntimeException("Failed to construct hal service", e);
        }
    }

    /**
     * Gets the current vehicle stub
     * @return The current vehicle stub
     */
    @VisibleForTesting
    public VehicleStub getVehicleStub() {
        return mVehicleStub.get();
    }

    /** Sets fake feature flag for unit testing. */
    @VisibleForTesting
    public void setFeatureFlags(FeatureFlags fakeFeatureFlags) {
        mFeatureFlags = fakeFeatureFlags;
    }

    @VisibleForTesting
    void setMaxDurationForRetryMs(int maxDurationForRetryMs) {
        mMaxDurationForRetryMs = maxDurationForRetryMs;
    }

    @VisibleForTesting
    void setSleepBetweenRetryMs(int sleepBetweenRetryMs) {
        mSleepBetweenRetryMs = sleepBetweenRetryMs;
    }

    @VisibleForTesting
    void fetchAllPropConfigs() {
        var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
        if (propertyConfigsByPropId.size() != 0) { // already set
            Slogf.i(CarLog.TAG_HAL, "fetchAllPropConfigs already fetched");
            return;
        }
        HalPropConfig[] configs;
        try {
            configs = getAllPropConfigs();
            if (configs == null || configs.length == 0) {
                Slogf.e(CarLog.TAG_HAL, "getAllPropConfigs returned empty configs");
                return;
            }
        } catch (RemoteException | ServiceSpecificException e) {
            throw new RuntimeException("Unable to retrieve vehicle property configuration", e);
        }

        SparseArray<HalPropConfig> allPropertyConfigsByPropId = new SparseArray<>();
        PairSparseArray<Integer> accessByPropIdAreaId = new PairSparseArray<>();
        // Create map of all properties
        for (HalPropConfig p : configs) {
            if (DBG) {
                Slogf.d(CarLog.TAG_HAL, "Add config for prop: 0x%x config: %s", p.getPropId(),
                        p.toString());
            }
            allPropertyConfigsByPropId.put(p.getPropId(), p);
            if (p.getAreaConfigs().length == 0) {
                accessByPropIdAreaId.put(p.getPropId(), /* areaId */ 0, p.getAccess());
            } else {
                for (HalAreaConfig areaConfig : p.getAreaConfigs()) {
                    accessByPropIdAreaId.put(p.getPropId(), areaConfig.getAreaId(),
                            areaConfig.getAccess());
                }
            }
        }

        mPropertyConfigsByPropIdRef.set(new ImmutableSparseArray<HalPropConfig>(
                allPropertyConfigsByPropId));
        mAccessByPropIdAreaIdRef.set(new ImmutablePairSparseArray<Integer>(
                accessByPropIdAreaId));
    }

    private void handleOnPropertyEvent(List<HalPropValue> propValues) {
        var filteredPropValues = maybeHandleRecordingAndInjection(propValues);
        if (filteredPropValues.isEmpty()) {
            Slogf.d(CarLog.TAG_HAL, "All onPropertyEvent properties filtered: %s",
                    Arrays.toString(propValues.toArray()));
            return;
        }
        dispatchPropertyEvents(filteredPropValues);
    }

    private void dispatchPropertyEvents(List<HalPropValue> propValues) {
        var dispatchList = new HalEventsDispatchList();
        synchronized (mLock) {
            for (int i = 0; i < propValues.size(); i++) {
                HalPropValue v = propValues.get(i);
                int propId = v.getPropId();
                HalServiceBase service = mPropertyHandlers.get(propId);
                if (service == null) {
                    Slogf.e(CarLog.TAG_HAL, "dispatchPropertyEvents: HalService not found for %s",
                            v);
                    continue;
                }
                dispatchList.addEvent(service, v);
                VehiclePropertyEventInfo info = mEventLog.get(propId);
                if (info == null) {
                    info = new VehiclePropertyEventInfo(v);
                    mEventLog.put(propId, info);
                } else {
                    info.addNewEvent(v);
                }
            }
        }
        dispatchList.dispatchToClients();
    }

    private static String errorMessage(String action, HalPropValue propValue, String errorMsg) {
        return String.format("Failed to %s value for: %s, error: %s", action,
                propValue, errorMsg);
    }

    private HalPropValue getValueWithRetry(HalPropValue value) {
        return getValueWithRetry(value, /* maxRetries= */ 0);
    }

    private HalPropValue getValueWithRetry(HalPropValue value, int maxRetries) {
        HalPropValue result;
        Trace.traceBegin(TRACE_TAG, "VehicleStub#getValueWithRetry");
        try {
            result = invokeRetriable((requestValue) -> {
                Trace.traceBegin(TRACE_TAG, "VehicleStub#get");
                try {
                    return mVehicleStub.get().get(requestValue);
                } finally {
                    Trace.traceEnd(TRACE_TAG);
                }
            }, "get", value, mMaxDurationForRetryMs, mSleepBetweenRetryMs, maxRetries);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }

        if (result == null) {
            // If VHAL returns null result, but the status is OKAY. We treat that as NOT_AVAILABLE.
            throw new ServiceSpecificException(StatusCode.NOT_AVAILABLE,
                    errorMessage("get", value, "VHAL returns null for property value"));
        }
        return result;
    }

    private void setValueWithRetry(HalPropValue value)  {
        invokeRetriable((requestValue) -> {
            Trace.traceBegin(TRACE_TAG, "VehicleStub#set");
            mVehicleStub.get().set(requestValue);
            Trace.traceEnd(TRACE_TAG);
            return null;
        }, "set", value, mMaxDurationForRetryMs, mSleepBetweenRetryMs, /* maxRetries= */ 0);
    }

    /**
     * Inits the vhal configurations.
     */
    @Override
    public void init() {
        // nothing to init as everything was done on priorityInit
    }

    /**
     * PriorityInit for the vhal configurations.
     */
    public void priorityInit() {
        mDefaultExecutorRef.set(Executors.newSingleThreadExecutor());

        fetchAllPropConfigs();

        // PropertyHalService will take most properties, so make it big enough.
        ArrayMap<HalServiceBase, ArrayList<HalPropConfig>> configsForAllServices =
                new ArrayMap<>(mAllServices.size());
        var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
        synchronized (mLock) {
            for (int i = 0; i < mAllServices.size(); i++) {
                ArrayList<HalPropConfig> configsForService = new ArrayList();
                HalServiceBase service = mAllServices.get(i);
                configsForAllServices.put(service, configsForService);
                int[] supportedProps = service.getAllSupportedProperties();
                if (supportedProps.length == 0) {
                    for (int j = 0; j < propertyConfigsByPropId.size(); j++) {
                        Integer propId = propertyConfigsByPropId.keyAt(j);
                        if (service.isSupportedProperty(propId)) {
                            HalPropConfig config = propertyConfigsByPropId.valueAt(j);
                            mPropertyHandlers.append(propId, service);
                            configsForService.add(config);
                        }
                    }
                } else {
                    for (int prop : supportedProps) {
                        HalPropConfig config = propertyConfigsByPropId.get(prop);
                        if (config == null) {
                            continue;
                        }
                        mPropertyHandlers.append(prop, service);
                        configsForService.add(config);
                    }
                }
            }
        }

        for (Map.Entry<HalServiceBase, ArrayList<HalPropConfig>> entry
                : configsForAllServices.entrySet()) {
            HalServiceBase service = entry.getKey();
            ArrayList<HalPropConfig> configsForService = entry.getValue();
            service.takeProperties(configsForService);
            service.init();
        }
    }

    /**
     * Releases all connected services (power management service, input service, etc).
     */
    @Override
    public void release() {
        // release in reverse order from init
        for (int i = mAllServices.size() - 1; i >= 0; i--) {
            mAllServices.get(i).release();
        }
        ArraySet<Integer> subscribedProperties = new ArraySet<>();
        synchronized (mLock) {
            for (int i = 0; i < mRateInfoByPropIdAreaId.size(); i++) {
                int propertyId = mRateInfoByPropIdAreaId.keyPairAt(i)[0];
                subscribedProperties.add(propertyId);
            }
            mRateInfoByPropIdAreaId.clear();
        }
        mPropertyConfigsByPropIdRef.set(new ImmutableSparseArray<>(new SparseArray<>()));
        mAccessByPropIdAreaIdRef.set(new ImmutablePairSparseArray<>(new PairSparseArray<>()));
        for (int i = 0; i < subscribedProperties.size(); i++) {
            try {
                mSubscriptionClient.unsubscribe(subscribedProperties.valueAt(i));
            } catch (RemoteException | ServiceSpecificException e) {
                //  Ignore exceptions on shutdown path.
                Slogf.w(CarLog.TAG_HAL, "Failed to unsubscribe", e);
            }
        }
        var defaultExecutor = mDefaultExecutorRef.getAndSet(null);
        if (defaultExecutor != null) {
            Slogf.d(CarLog.TAG_HAL, "Shutting down VehicleHal default executor");
            defaultExecutor.shutdown();
            try {
                // 10s should be more than enough for the posted tasks to finish.
                boolean result = defaultExecutor.awaitTermination(
                        EXECUTOR_TERMINATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!result) {
                    Slogf.e(CarLog.TAG_HAL, "VehicleHal default executor not finishing within 10s");
                } else {
                    Slogf.d(CarLog.TAG_HAL, "VehicleHal default executor shutdown complete");
                }
            } catch (InterruptedException e) {
                Slogf.w(CarLog.TAG_HAL, "Interrupted while shutting down default executor");
                defaultExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void destroy() {
        VehicleStub simulationVehicleStub;
        synchronized (mLock) {
            simulationVehicleStub = mSimulationVehicleStub.getAndSet(null);
        }
        if (simulationVehicleStub != null) {
            simulationVehicleStub.destroy();
        }
        for (int i = 0; i < mCreatedHalServices.size(); i++) {
            mCreatedHalServices.get(i).destroy();
        }
    }

    public DiagnosticHalService getDiagnosticHal() {
        return mDiagnosticHal;
    }

    public PowerHalService getPowerHal() {
        return mPowerHal;
    }

    public PropertyHalService getPropertyHal() {
        return mPropertyHal;
    }

    public InputHalService getInputHal() {
        return mInputHal;
    }

    public UserHalService getUserHal() {
        return mUserHal;
    }

    public VmsHalService getVmsHal() {
        return mVmsHal;
    }

    public ClusterHalService getClusterHal() {
        return mClusterHalService;
    }

    public EvsHalService getEvsHal() {
        return mEvsHal;
    }

    public TimeHalService getTimeHalService() {
        return mTimeHalService;
    }

    public HalPropValueBuilder getHalPropValueBuilder() {
        return mPropValueBuilder;
    }

    @GuardedBy("mLock")
    private void assertServiceOwnerLocked(HalServiceBase service, int property) {
        if (service != mPropertyHandlers.get(property)) {
            throw new IllegalArgumentException(String.format(
                    "Property 0x%x  is not owned by service: %s", property, service));
        }
    }

    /**
     * Sets the callback executor for the service.
     *
     * <p>The callback (e.g. onHalEvents) will be called using the executor.
     *
     * <p>If not set, a default single thread executor is used for all services. Services that
     * have light-weight callback functions can set a direct executor to avoid the overhead
     * introduced by the default executor.
     */
    public void setCallbackExecutor(HalServiceBase service, Executor executor) {
        mExecutorByService.put(service, executor);
    }

    /**
     * Subscribes given properties with sampling rate defaults to 0 and no special flags provided.
     *
     * @throws IllegalArgumentException thrown if property is not supported by VHAL
     * @throws ServiceSpecificException if VHAL returns error or lost connection with VHAL.
     * @see #subscribeProperty(HalServiceBase, int, float)
     */
    public void subscribeProperty(HalServiceBase service, int property)
            throws IllegalArgumentException, ServiceSpecificException {
        subscribeProperty(service, property, /* samplingRateHz= */ 0f);
    }

    /**
     * Similar to {@link #subscribeProperty(HalServiceBase, int)} except that all exceptions
     * are caught and are logged.
     */
    public void subscribePropertySafe(HalServiceBase service, int property) {
        try {
            subscribeProperty(service, property);
        } catch (IllegalArgumentException | ServiceSpecificException e) {
            Slogf.w(CarLog.TAG_HAL, "Failed to subscribe for property: "
                    + VehiclePropertyIds.toString(property), e);
        }
    }

    /**
     * Subscribe given property. Only Hal service owning the property can subscribe it.
     *
     * @param service HalService that owns this property
     * @param property property id (VehicleProperty)
     * @param samplingRateHz sampling rate in Hz for continuous properties
     * @throws IllegalArgumentException thrown if property is not supported by VHAL
     * @throws ServiceSpecificException if VHAL returns error or lost connection with VHAL.
     */
    public void subscribeProperty(HalServiceBase service, int property, float samplingRateHz)
            throws IllegalArgumentException, ServiceSpecificException {
        HalSubscribeOptions options = new HalSubscribeOptions(property, new int[0], samplingRateHz);
        subscribeProperty(service, List.of(options));
    }

    /**
     * Similar to {@link #subscribeProperty(HalServiceBase, int, float)} except that all exceptions
     * are caught and converted to logs.
     */
    public void subscribePropertySafe(HalServiceBase service, int property, float sampleRateHz) {
        try {
            subscribeProperty(service, property, sampleRateHz);
        } catch (IllegalArgumentException | ServiceSpecificException e) {
            Slogf.w(CarLog.TAG_HAL, e, "Failed to subscribe for property: %s, sample rate: %f hz",
                    VehiclePropertyIds.toString(property), sampleRateHz);
        }
    }

    /**
     * Subscribe given property. Only Hal service owning the property can subscribe it.
     *
     * @param service HalService that owns this property
     * @param halSubscribeOptions Information needed to subscribe to VHAL
     * @throws IllegalArgumentException thrown if property is not supported by VHAL
     * @throws ServiceSpecificException if VHAL returns error or lost connection with VHAL.
     */
    public void subscribeProperty(HalServiceBase service, List<HalSubscribeOptions>
            halSubscribeOptions) throws IllegalArgumentException, ServiceSpecificException {
        synchronized (mLock) {
            PairSparseArray<RateInfo> previousState = cloneState(mRateInfoByPropIdAreaId);
            SubscribeOptions[] subscribeOptions = createVhalSubscribeOptionsLocked(
                    service, halSubscribeOptions);
            if (subscribeOptions.length == 0) {
                if (DBG) {
                    Slogf.d(CarLog.TAG_HAL,
                            "Ignore the subscribeProperty request, SubscribeOptions is length 0");
                }
                return;
            }
            try {
                mSubscriptionClient.subscribe(subscribeOptions);
            } catch (RemoteException e) {
                mRateInfoByPropIdAreaId = previousState;
                Slogf.w(CarLog.TAG_HAL, "Failed to subscribe, connection to VHAL failed", e);
                // Convert RemoteException to ServiceSpecificException so that it could be passed
                // back to the client.
                throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                        "Failed to subscribe, connection to VHAL failed, error: " + e);
            } catch (ServiceSpecificException e) {
                mRateInfoByPropIdAreaId = previousState;
                Slogf.w(CarLog.TAG_HAL, "Failed to subscribe, received error from VHAL", e);
                throw e;
            }
        }
    }

    /**
     * Converts {@link HalSubscribeOptions} to {@link SubscribeOptions} which is the data structure
     * used by VHAL.
     */
    @GuardedBy("mLock")
    private SubscribeOptions[] createVhalSubscribeOptionsLocked(HalServiceBase service,
            List<HalSubscribeOptions> halSubscribeOptions) throws IllegalArgumentException {
        if (DBG) {
            Slogf.d(CarLog.TAG_HAL, "creating subscribeOptions from HalSubscribeOptions of size: "
                    + halSubscribeOptions.size());
        }
        List<SubscribeOptions> subscribeOptionsList = new ArrayList<>();
        var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
        for (int i = 0; i < halSubscribeOptions.size(); i++) {
            HalSubscribeOptions halSubscribeOption = halSubscribeOptions.get(i);
            int property = halSubscribeOption.getHalPropId();
            int[] areaIds = halSubscribeOption.getAreaId();
            float samplingRateHz = halSubscribeOption.getUpdateRateHz();
            boolean enableVariableUpdateRate = halSubscribeOption.isVariableUpdateRateEnabled();
            float resolution = halSubscribeOption.getResolution();

            HalPropConfig config;
            config = propertyConfigsByPropId.get(property);

            if (config == null) {
                throw new IllegalArgumentException("subscribe error: "
                        + toPropertyIdString(property) + " is not supported");
            }

            if (enableVariableUpdateRate) {
                if (config.getChangeMode() != VehiclePropertyChangeMode.CONTINUOUS) {
                    // enableVur should be ignored if property is not continuous, but we set it to
                    // false to be safe.
                    enableVariableUpdateRate = false;
                    Slogf.w(CarLog.TAG_HAL, "VUR is always off for non-continuous property: "
                            + toPropertyIdString(property));
                }
                if (!mFeatureFlags.variableUpdateRate()) {
                    enableVariableUpdateRate = false;
                    Slogf.w(CarLog.TAG_HAL, "VUR feature is not enabled, VUR is always off");
                }
            }

            if (resolution != 0.0f) {
                if (config.getChangeMode() != VehiclePropertyChangeMode.CONTINUOUS) {
                    // resolution should be ignored if property is not continuous, but we set it to
                    // 0 to be safe.
                    resolution = 0.0f;
                    Slogf.w(CarLog.TAG_HAL, "resolution is always 0 for non-continuous property: "
                            + toPropertyIdString(property));
                }
            }

            if (isStaticProperty(config)) {
                Slogf.w(CarLog.TAG_HAL, "Ignore subscribing to static property: "
                        + toPropertyIdString(property));
                continue;
            }

            if (areaIds.length == 0) {
                if (!isPropertySubscribable(config)) {
                    throw new IllegalArgumentException("Property: " + toPropertyIdString(property)
                            + " is not subscribable");
                }
                areaIds = getAllAreaIdsFromPropertyId(config);
            } else {
                var accessByPropIdAreaId = mAccessByPropIdAreaIdRef.get();
                for (int j = 0; j < areaIds.length; j++) {
                    Integer access = accessByPropIdAreaId.get(config.getPropId(), areaIds[j]);
                    if (access == null) {
                        throw new IllegalArgumentException(
                                "Cannot subscribe to " + toPropertyIdString(property)
                                + " at areaId " + toAreaIdString(property, areaIds[j])
                                + " the property does not have the requested areaId");
                    }
                    if (!isPropIdAreaIdReadable(config, access.intValue())) {
                        throw new IllegalArgumentException(
                                "Cannot subscribe to " + toPropertyIdString(property)
                                + " at areaId " + toAreaIdString(property, areaIds[j])
                                + " the property's access mode does not contain READ");
                    }
                }
            }
            SubscribeOptions opts = new SubscribeOptions();
            opts.propId = property;
            opts.sampleRate = samplingRateHz;
            opts.enableVariableUpdateRate = enableVariableUpdateRate;
            opts.resolution = resolution;
            RateInfo rateInfo = new RateInfo(samplingRateHz, enableVariableUpdateRate, resolution);
            int[] filteredAreaIds = filterAreaIdsWithSameRateInfo(property, areaIds, rateInfo);
            opts.areaIds = filteredAreaIds;
            if (opts.areaIds.length == 0) {
                if (DBG) {
                    Slogf.d(CarLog.TAG_HAL, "property: " + VehiclePropertyIds.toString(property)
                            + " is already subscribed at rate: " + samplingRateHz + " hz");
                }
                continue;
            }
            assertServiceOwnerLocked(service, property);
            for (int j = 0; j < filteredAreaIds.length; j++) {
                if (DBG) {
                    Slogf.d(CarLog.TAG_HAL, "Update subscription rate for propertyId:"
                                    + " %s, areaId: %d, SampleRateHz: %f, enableVur: %b,"
                                    + " resolution: %f",
                            VehiclePropertyIds.toString(opts.propId), filteredAreaIds[j],
                            samplingRateHz, enableVariableUpdateRate, resolution);
                }
                mRateInfoByPropIdAreaId.put(property, filteredAreaIds[j], rateInfo);
            }
            subscribeOptionsList.add(opts);
        }
        return subscribeOptionsList.toArray(new SubscribeOptions[0]);
    }

    private int[] filterAreaIdsWithSameRateInfo(int property, int[] areaIds, RateInfo rateInfo) {
        List<Integer> areaIdList = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < areaIds.length; i++) {
                RateInfo savedRateInfo = mRateInfoByPropIdAreaId.get(property, areaIds[i]);

                // Strict equality (==) is used here for comparing resolutions. This approach does
                // not introduce a margin of error through PRECISION_THRESHOLD, and thus can allow
                // clients to request the highest possible resolution without being limited by a
                // predefined threshold. This approach is assumed to be feasible under the
                // hypothesis that the floating point representation of numbers is consistent
                // across the system. That is, if two clients specify a resolution of 0.01f,
                // their internal representations will match, enabling an exact comparison despite
                // floating point inaccuracies. If this is inaccurate, we must introduce a margin
                // of error (ideally 1e-7 as floats can reliably represent up to 7 significant
                // figures, but can be higher if necessary), and update the documentation in {@link
                // android.car.hardware.property.Subscription.Builder#setResolution(float)}
                // appropriately.
                if (savedRateInfo != null
                        && (Math.abs(savedRateInfo.updateRateHz - rateInfo.updateRateHz)
                                < PRECISION_THRESHOLD)
                        && (savedRateInfo.enableVariableUpdateRate
                                == rateInfo.enableVariableUpdateRate)
                        && savedRateInfo.resolution == rateInfo.resolution) {
                    if (DBG) {
                        Slogf.d(CarLog.TAG_HAL, "Property: %s is already subscribed at rate: %f hz"
                                + ", enableVur: %b, resolution: %f",
                                toPropertyIdString(property), rateInfo.updateRateHz,
                                rateInfo.enableVariableUpdateRate, rateInfo.resolution);
                    }
                    continue;
                }
                areaIdList.add(areaIds[i]);
            }
        }
        return CarServiceUtils.toIntArray(areaIdList);
    }

    private int[] getAllAreaIdsFromPropertyId(HalPropConfig config) {
        HalAreaConfig[] allAreaConfigs = config.getAreaConfigs();
        if (allAreaConfigs.length == 0) {
            return new int[]{/* areaId= */ 0};
        }
        int[] areaId = new int[allAreaConfigs.length];
        for (int i = 0; i < allAreaConfigs.length; i++) {
            areaId[i] = allAreaConfigs[i].getAreaId();
        }
        return areaId;
    }

    /**
     * Like {@link unsubscribeProperty} except that exceptions are logged.
     */
    public void unsubscribePropertySafe(HalServiceBase service, int property) {
        try {
            unsubscribeProperty(service, property);
        } catch (ServiceSpecificException e) {
            Slogf.w(CarLog.TAG_SERVICE, "Failed to unsubscribe: "
                    + toPropertyIdString(property), e);
        }
    }

    /**
     * Unsubscribes from receiving notifications for the property and HAL services passed
     * as parameters.
     */
    public void unsubscribeProperty(HalServiceBase service, int property)
            throws ServiceSpecificException {
        if (DBG) {
            Slogf.d(CarLog.TAG_HAL, "unsubscribeProperty, service:" + service
                    + ", " + toPropertyIdString(property));
        }
        var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
        HalPropConfig config = propertyConfigsByPropId.get(property);
        if (config == null) {
            Slogf.w(CarLog.TAG_HAL, "unsubscribeProperty " + toPropertyIdString(property)
                    + " does not exist");
            return;
        }
        if (isStaticProperty(config)) {
            Slogf.w(CarLog.TAG_HAL, "Unsubscribe to a static property: "
                    + toPropertyIdString(property) + ", do nothing");
            return;
        }
        synchronized (mLock) {
            assertServiceOwnerLocked(service, property);
            HalAreaConfig[] halAreaConfigs = config.getAreaConfigs();
            boolean isSubscribed = false;
            PairSparseArray<RateInfo> previousState = cloneState(mRateInfoByPropIdAreaId);
            if (halAreaConfigs.length == 0) {
                int index = mRateInfoByPropIdAreaId.indexOfKeyPair(property, 0);
                if (hasReadAccess(config.getAccess()) && index >= 0) {
                    mRateInfoByPropIdAreaId.removeAt(index);
                    isSubscribed = true;
                }
            } else {
                for (int i = 0; i < halAreaConfigs.length; i++) {
                    if (!isPropIdAreaIdReadable(config, halAreaConfigs[i].getAccess())) {
                        Slogf.w(CarLog.TAG_HAL,
                                "Cannot unsubscribe to " + toPropertyIdString(property)
                                + " at areaId " + toAreaIdString(property,
                                halAreaConfigs[i].getAreaId())
                                + " the property's access mode does not contain READ");
                        continue;
                    }
                    int index = mRateInfoByPropIdAreaId.indexOfKeyPair(property,
                            halAreaConfigs[i].getAreaId());
                    if (index >= 0) {
                        mRateInfoByPropIdAreaId.removeAt(index);
                        isSubscribed = true;
                    }
                }
            }
            if (!isSubscribed) {
                if (DBG) {
                    Slogf.d(CarLog.TAG_HAL, "Property " + toPropertyIdString(property)
                            + " was not subscribed, do nothing");
                }
                return;
            }
            try {
                mSubscriptionClient.unsubscribe(property);
            } catch (RemoteException e) {
                mRateInfoByPropIdAreaId = previousState;
                Slogf.w(CarLog.TAG_HAL, "Failed to unsubscribe, connection to VHAL failed", e);
                throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                        "Failed to unsubscribe, connection to VHAL failed, error: " + e);
            } catch (ServiceSpecificException e) {
                mRateInfoByPropIdAreaId = previousState;
                Slogf.w(CarLog.TAG_HAL, "Failed to unsubscribe, received error from VHAL", e);
                throw e;
            }
        }
    }

    /**
     * Indicates if the property passed as parameter is supported.
     */
    public boolean isPropertySupported(int propertyId) {
        return mPropertyConfigsByPropIdRef.get().contains(propertyId);
    }

    /**
     * Gets given property with retries.
     *
     * <p>If getting the property fails after all retries, it will throw
     * {@code IllegalStateException}. If the property is not supported, it will simply return
     * {@code null}.
     */
    @Nullable
    public HalPropValue getIfSupportedOrFail(int propertyId, int maxRetries) {
        if (!isPropertySupported(propertyId)) {
            return null;
        }
        try {
            return getValueWithRetry(mPropValueBuilder.build(propertyId, GLOBAL_AREA_ID),
                    maxRetries);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * This works similar to {@link #getIfSupportedOrFail(int, int)} except that this can be called
     * before {@code init()} is called.
     *
     * <p>This call will check if requested vhal property is supported by querying directly to vhal
     * and can have worse performance. Use this only for accessing vhal properties before
     * {@code ICarImpl.init()} phase.
     */
    @Nullable
    public HalPropValue getIfSupportedOrFailForEarlyStage(int propertyId, int maxRetries) {
        fetchAllPropConfigs();
        return getIfSupportedOrFail(propertyId, maxRetries);
    }

    /**
     * Returns the property's {@link HalPropValue} for the property id passed as parameter and
     * not specified area.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public HalPropValue get(int propertyId)
            throws IllegalArgumentException, ServiceSpecificException {
        return get(propertyId, GLOBAL_AREA_ID);
    }

    /**
     * Returns the property's {@link HalPropValue} for the property id and area id passed as
     * parameters.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public HalPropValue get(int propertyId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        if (DBG) {
            Slogf.d(CarLog.TAG_HAL, "get, " + toPropertyIdString(propertyId)
                    + toAreaIdString(propertyId, areaId));
        }
        return getValueWithRetry(mPropValueBuilder.build(propertyId, areaId));
    }

    /**
     * Returns the property object value for the class and property id passed as parameter and
     * no area specified.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public <T> T get(Class clazz, int propertyId)
            throws IllegalArgumentException, ServiceSpecificException {
        return get(clazz, propertyId, GLOBAL_AREA_ID);
    }

    /**
     * Returns the property object value for the class, property id, and area id passed as
     * parameter.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public <T> T get(Class clazz, int propertyId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        return get(clazz, mPropValueBuilder.build(propertyId, areaId));
    }

    /**
     * Returns the property object value for the class and requested property value passed as
     * parameter.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class clazz, HalPropValue requestedPropValue)
            throws IllegalArgumentException, ServiceSpecificException {
        HalPropValue propValue;
        propValue = getValueWithRetry(requestedPropValue);

        if (clazz == Long.class || clazz == long.class) {
            Long value = propValue.getInt64Value(0);
            return (T) value;
        } else if (clazz == Integer.class || clazz == int.class) {
            Integer value = propValue.getInt32Value(0);
            return (T) value;
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            Boolean value = Boolean.valueOf(propValue.getInt32Value(0) == 1);
            return (T) value;
        } else if (clazz == Float.class || clazz == float.class) {
            Float value = propValue.getFloatValue(0);
            return (T) value;
        } else if (clazz == Long[].class) {
            int size = propValue.getInt64ValuesSize();
            Long[] longArray = new Long[size];
            for (int i = 0; i < size; i++) {
                longArray[i] = propValue.getInt64Value(i);
            }
            return (T) longArray;
        } else if (clazz == Integer[].class) {
            int size = propValue.getInt32ValuesSize();
            Integer[] intArray = new Integer[size];
            for (int i = 0; i < size; i++) {
                intArray[i] = propValue.getInt32Value(i);
            }
            return (T) intArray;
        } else if (clazz == Float[].class) {
            int size = propValue.getFloatValuesSize();
            Float[] floatArray = new Float[size];
            for (int i = 0; i < size; i++) {
                floatArray[i] = propValue.getFloatValue(i);
            }
            return (T) floatArray;
        } else if (clazz == long[].class) {
            int size = propValue.getInt64ValuesSize();
            long[] longArray = new long[size];
            for (int i = 0; i < size; i++) {
                longArray[i] = propValue.getInt64Value(i);
            }
            return (T) longArray;
        } else if (clazz == int[].class) {
            int size = propValue.getInt32ValuesSize();
            int[] intArray = new int[size];
            for (int i = 0; i < size; i++) {
                intArray[i] = propValue.getInt32Value(i);
            }
            return (T) intArray;
        } else if (clazz == float[].class) {
            int size = propValue.getFloatValuesSize();
            float[] floatArray = new float[size];
            for (int i = 0; i < size; i++) {
                floatArray[i] = propValue.getFloatValue(i);
            }
            return (T) floatArray;
        } else if (clazz == byte[].class) {
            return (T) propValue.getByteArray();
        } else if (clazz == String.class) {
            return (T) propValue.getStringValue();
        } else {
            throw new IllegalArgumentException("Unexpected type: " + clazz);
        }
    }

    /**
     * Returns the vehicle's {@link HalPropValue} for the requested property value passed
     * as parameter.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public HalPropValue get(HalPropValue requestedPropValue)
            throws IllegalArgumentException, ServiceSpecificException {
        return getValueWithRetry(requestedPropValue);
    }

    /**
     * Set property.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public void set(HalPropValue propValue)
            throws IllegalArgumentException, ServiceSpecificException {
        setValueWithRetry(propValue);
    }

    @CheckResult
    HalPropValueSetter set(int propId) {
        return set(propId, GLOBAL_AREA_ID);
    }

    @CheckResult
    HalPropValueSetter set(int propId, int areaId) {
        return new HalPropValueSetter(propId, areaId);
    }

    private static boolean hasReadAccess(int accessLevel) {
        return accessLevel == VehiclePropertyAccess.READ
                || accessLevel == VehiclePropertyAccess.READ_WRITE;
    }

    private static boolean isPropIdAreaIdReadable(HalPropConfig config, int areaIdAccess) {
        return (areaIdAccess == VehiclePropertyAccess.NONE)
                ? hasReadAccess(config.getAccess()) : hasReadAccess(areaIdAccess);
    }

    /**
     * Returns whether the property is readable and not static.
     */
    static boolean isPropertySubscribable(HalPropConfig config) {
        if (isStaticProperty(config)) {
            Slogf.w(CarLog.TAG_HAL, "Subscribe to a static property: "
                    + toPropertyIdString(config.getPropId()) + ", do nothing");
            return false;
        }
        if (config.getAreaConfigs().length == 0) {
            boolean hasReadAccess = hasReadAccess(config.getAccess());
            if (!hasReadAccess) {
                Slogf.w(CarLog.TAG_HAL, "Cannot subscribe to "
                        + toPropertyIdString(config.getPropId())
                        + " the property's access mode does not contain READ");
            }
            return hasReadAccess;
        }
        for (HalAreaConfig halAreaConfig : config.getAreaConfigs()) {
            if (!isPropIdAreaIdReadable(config, halAreaConfig.getAccess())) {
                Slogf.w(CarLog.TAG_HAL, "Cannot subscribe to "
                        + toPropertyIdString(config.getPropId()) + " at areaId "
                        + toAreaIdString(config.getPropId(), halAreaConfig.getAreaId())
                        + " the property's access mode does not contain READ");
                return false;
            }
        }
        return true;
    }

    /**
     * Sets a passed propertyId+areaId from the shell command.
     *
     * @param propertyId Property ID
     * @param areaId     Area ID
     * @param data       Comma-separated value.
     */
    public void setPropertyFromCommand(int propertyId, int areaId, String data,
            IndentingPrintWriter writer) throws IllegalArgumentException, ServiceSpecificException {
        long timestampNanos = SystemClock.elapsedRealtimeNanos();
        HalPropValue halPropValue = createPropValueForInjecting(mPropValueBuilder, propertyId,
                areaId, List.of(data.split(DATA_DELIMITER)), timestampNanos);
        if (halPropValue == null) {
            throw new IllegalArgumentException(
                    "Unsupported property type: propertyId=" + toPropertyIdString(propertyId)
                            + ", areaId=" + toAreaIdString(propertyId, areaId));
        }
        set(halPropValue);
    }


    @Override
    public void onPropertyEvent(List<HalPropValue> propValues) {
        // Note that this function runs from a binder thread and should not do heavy works.
        Binder.clearCallingIdentity();

        handleOnPropertyEvent(propValues);
    }

    @Override
    public void onInjectionPropertyEvent(List<HalPropValue> propValues) {
        // Note that this function runs from a binder thread and should not do heavy works.
        Binder.clearCallingIdentity();

        dispatchPropertyEvents(propValues);
    }

    @Override
    public void onPropertySetError(List<VehiclePropError> errors) {
        // Note that this function runs from a binder thread and should not do heavy works.
        Binder.clearCallingIdentity();

        var filteredErrors = maybeFilterItemsForInjectionMode(errors,
                (VehiclePropError err) -> err.propId);
        if (filteredErrors.isEmpty()) {
            Slogf.d(CarLog.TAG_HAL, "All onPropertySetError events filtered: %s",
                    Arrays.toString(errors.toArray()));
            return;
        }
        var dispatchList = new PropertySetErrorDispatchList();
        synchronized (mLock) {
            for (int i = 0; i < filteredErrors.size(); i++) {
                VehiclePropError error = filteredErrors.get(i);
                int errorCode = error.errorCode;
                int propId = error.propId;
                int areaId = error.areaId;
                Slogf.w(CarLog.TAG_HAL, "onPropertySetError, errorCode: %d, prop: 0x%x, area: 0x%x",
                        errorCode, propId, areaId);
                if (propId == VehicleProperty.INVALID) {
                    continue;
                }
                HalServiceBase service = mPropertyHandlers.get(propId);
                if (service == null) {
                    Slogf.e(CarLog.TAG_HAL,
                            "onPropertySetError: HalService not found for prop: 0x%x", propId);
                    continue;
                }

                dispatchList.addEvent(service, error);
            }
        }

        dispatchList.dispatchToClients();
    }

    /**
     * Filter the items we received from VHAL if we are in simulation mode.
     */
    private <T> List<T> maybeFilterItemsForInjectionMode(List<T> items,
            Function<T, Integer> propIdExtractor) {
        // In rare cases, mVehicleStub might change after we copy it to vehicleStub locally, in that
        // case we will act on the old vehicle stub. This is okay because we do not lock guard the
        // whole operation of delivering property events/errors to the client so there is no
        // guarantee that all events/errors will be filtered immediately after simulation mode
        // is enabled.
        var vehicleStub = mVehicleStub.get();
        if (!vehicleStub.isSimulatedModeEnabled()) {
            return items;
        }
        return ((SimulationVehicleStub) vehicleStub).filterProperties(items, propIdExtractor);
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("**dump HAL services**");
        for (int i = 0; i < mAllServices.size(); i++) {
            mAllServices.get(i).dump(writer);
        }
        // Dump all VHAL property configure.
        dumpPropertyConfigs(writer, -1);
        writer.printf("**All Events, now ns:%d**\n",
                SystemClock.elapsedRealtimeNanos());
        synchronized (mLock) {
            for (int i = 0; i < mEventLog.size(); i++) {
                VehiclePropertyEventInfo info = mEventLog.valueAt(i);
                writer.printf("event count:%d, lastEvent: ", info.mEventCount);
                dumpPropValue(writer, info.mLastEvent);
            }
            writer.println("**Property handlers**");
            for (int i = 0; i < mPropertyHandlers.size(); i++) {
                int propId = mPropertyHandlers.keyAt(i);
                HalServiceBase service = mPropertyHandlers.valueAt(i);
                writer.printf("Property Id: %d // 0x%x name: %s, service: %s\n", propId, propId,
                        VehiclePropertyIds.toString(propId), service);
            }
        }
    }

    // This class is immutable, hence thread-safe.
    private final class RecordingListenerHandler implements IBinder.DeathRecipient {

        private final ICarPropertyEventListener mCallback;
        private final IBinder mBinder;

        private RecordingListenerHandler(ICarPropertyEventListener callback) {
            mCallback = callback;
            mBinder = mCallback.asBinder();
        }

        private void onEvent(List<CarPropertyEvent> events) {
            try {
                mCallback.onEvent(events);
            } catch (RemoteException e) {
                Slogf.e(CarLog.TAG_HAL, "onEvent failed", e);
            }
        }

        private boolean linkToDeath() {
            try {
                mBinder.linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Slogf.w(CarLog.TAG_HAL, e, "Linking to binder death recipient failed");
            }
            return false;
        }

        private void unlinkToDeath() {
            mBinder.unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            Slogf.w(CarLog.TAG_HAL, "Recording listener died");
            stopRecordingVehicleProperties(mCallback);
        }
    }

    private List<HalPropValue> maybeHandleRecordingAndInjection(List<HalPropValue> halPropValues) {
        if (BuildHelper.isUserBuild()) {
            return halPropValues;
        }
        RecordingListenerHandler recordingListenerHandler = mListenerHandlerRef.get();
        if (recordingListenerHandler != null) {
            List<CarPropertyEvent> events = new ArrayList<>();
            var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
            for (int i = 0; i < halPropValues.size(); i++) {
                HalPropValue halPropValue = halPropValues.get(i);
                HalPropConfig halPropConfig = propertyConfigsByPropId.get(halPropValue.getPropId());
                if (halPropConfig == null) {
                    Slogf.w(CarLog.TAG_HAL, "No HalPropConfig associated with property %d",
                            halPropValue.getPropId());
                    continue;
                }
                CarPropertyValue<?> carPropertyvalue = halPropValues.get(i).toCarPropertyValue(
                        halPropValue.getPropId(), halPropConfig, /* isVhalPropId= */ true);
                events.add(new CarPropertyEvent(
                        CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, carPropertyvalue));
            }
            if (events.isEmpty()) {
                return halPropValues;
            }
            recordingListenerHandler.onEvent(events);
            return halPropValues;
        }

        return maybeFilterItemsForInjectionMode(halPropValues, HalPropValue::getPropId);
    }

    /**
     * Registers a recording listener.
     *
     * @param callback The callback to register
     * @return A list of CarPropertyConfigs that are being recorded
     */
    public List<HalPropConfig> registerRecordingListener(ICarPropertyEventListener callback) {
        if (mListenerHandlerRef.get() != null) {
            throw new IllegalStateException("Recording already in progress");
        }

        var listenerHandler = new RecordingListenerHandler(callback);
        if (!listenerHandler.linkToDeath()) {
            throw new IllegalStateException("Failed to link to death, the client is probably"
                    + " already dead.");
        }
        mListenerHandlerRef.set(listenerHandler);
        List<HalPropConfig> allHalPropConfigs = new ArrayList<>();
        var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
        for (int i = 0; i < propertyConfigsByPropId.size(); i++) {
            allHalPropConfigs.add(propertyConfigsByPropId.valueAt(i));
        }
        return allHalPropConfigs;
    }

    /**
     * @return {@code true} If currently recording vehicle properties
     */
    public boolean isRecordingVehicleProperties() {
        return mListenerHandlerRef.get() != null;
    }

    /**
     * Stops the recording. If no recording is present, treat as no-op.
     *
     * Note that in a rare case, if this happens at the same time of {@link handleOnPropertyEvent},
     * it is possible that some events will still be delivered through the callback after this
     * function returns.
     *
     * @param callback The callback to stop recording.
     */
    public void stopRecordingVehicleProperties(ICarPropertyEventListener callback) {
        synchronized (mLock) {
            var listenerHandler = mListenerHandlerRef.get();
            if (listenerHandler == null) {
                Slogf.w(CarLog.TAG_HAL, "No recording was started");
                return;
            }
            if (listenerHandler.mCallback.asBinder() != callback.asBinder()) {
                Slogf.w(CarLog.TAG_HAL, "ICarPropertyEventListener are not the same");
                return;
            }
            listenerHandler.unlinkToDeath();
            mListenerHandlerRef.set(null);
        }
    }

    /**
     * Disables injection mode.
     */
    public void disableInjectionMode() {
        VehicleStub simulationVehicleStub;
        // Use a lock to synchronize this with disableInjectionMode and enableInjectionMode.
        synchronized (mLock) {
            var vehicleStub = mVehicleStub.get();
            if (!vehicleStub.isSimulatedModeEnabled()) {
                Slogf.w(CarLog.TAG_HAL, "Cannot disable injection mode, injection mode is"
                        + " not enabled");
                return;
            }
            simulationVehicleStub = mSimulationVehicleStub.getAndSet(null);
            mVehicleStub.set(vehicleStub.getRealVehicleStub());
        }
        if (simulationVehicleStub != null) {
            simulationVehicleStub.destroy();
        }
    }

    /**
     * Enables Injection mode with the list of properties to allow to come from the real VHAL.
     * @param propertyIdsFromRealHardware THe list of properties to allow to come from real VHAL.
     */
    public long enableInjectionMode(List<Integer> propertyIdsFromRealHardware) {
        // Use a lock to synchronize this with disableInjectionMode and enableInjectionMode.
        synchronized (mLock) {
            if (isRecordingVehicleProperties()) {
                throw new IllegalStateException("Cannot enable injection mode while recording is in"
                        + " progress");
            }
            var vehicleStub = mVehicleStub.get();
            if (vehicleStub.isSimulatedModeEnabled()) {
                Slogf.w(CarLog.TAG_HAL, "Cannot enable injection mode, it is already in"
                        + " progress");
                return -1L;
            }
            // Creation of SimulationVehicleStub needs to be inside lock because
            // we need to make sure mVehicleStub (copied to vehicleStub) does not change.
            try {
                var simulationVehicleStub = new SimulationVehicleStub(
                        vehicleStub, propertyIdsFromRealHardware, this);
                mVehicleStub.set(simulationVehicleStub);
                mSimulationVehicleStub.set(simulationVehicleStub);
            } catch (RemoteException e) {
                throw new IllegalStateException("Failed to create SimulationVehicleStub", e);
            }
            return mVehicleStub.get().getSimulationStartTimestampNanos();
        }
    }

    /**
     * @return {@code true} if Vehicle property injection mode is enabled, {@code false} otherwise.
     */
    public boolean isVehiclePropertyInjectionModeEnabled() {
        return mVehicleStub.get().isSimulatedModeEnabled();
    }

    /**
     * Gets the last injected vehicle property for the propertyId.
     *
     * @param propertyId The propertyId that was last injected.
     * @return The {@link CarPropertyValue} that was last injected.
     */
    @Nullable
    public CarPropertyValue getLastInjectedVehicleProperty(int propertyId) {
        var vehicleStub = mVehicleStub.get();
        if (!vehicleStub.isSimulatedModeEnabled()) {
            throw new IllegalStateException("Vehicle property injection mode is not enabled!");
        }
        return vehicleStub.getLastInjectedVehicleProperty(propertyId);
    }

    /**
     * Injects the CarPropertyValues.
     * @param carPropertyValues The carPropertyValues to inject.
     */
    public void injectVehicleProperties(List<CarPropertyValue> carPropertyValues) {
        var vehicleStub = mVehicleStub.get();
        if (!vehicleStub.isSimulatedModeEnabled()) {
            throw new IllegalStateException("Vehicle property injection mode is not enabled!");
        }
        vehicleStub.injectVehicleProperties(carPropertyValues);
    }

    /**
    * Dumps or debug VHAL.
    */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dumpVhal(ParcelFileDescriptor fd, List<String> options) throws RemoteException {
        VehicleStub vehicleStub = mVehicleStub.get();
        vehicleStub.dump(fd.getFileDescriptor(), options);
    }

    /**
     * Dumps the list of HALs.
     */
    public void dumpListHals(PrintWriter writer) {
        for (int i = 0; i < mAllServices.size(); i++) {
            writer.println(mAllServices.get(i).getClass().getName());
        }
    }

    /**
     * Dumps the given HALs.
     */
    public void dumpSpecificHals(PrintWriter writer, String... halNames) {
        ArrayMap<String, HalServiceBase> byName = new ArrayMap<>();
        for (int index = 0; index < mAllServices.size(); index++) {
            HalServiceBase halService = mAllServices.get(index);
            byName.put(halService.getClass().getSimpleName(), halService);
        }
        for (String halName : halNames) {
            HalServiceBase service = byName.get(halName);
            if (service == null) {
                writer.printf("No HAL named %s. Valid options are: %s\n",
                        halName, byName.keySet());
                continue;
            }
            service.dump(writer);
        }
    }

    /**
     * Dumps vehicle property values.
     *
     * @param propertyId property id, dump all properties' value if it is {@code -1}.
     * @param areaId areaId of the property, dump the property for all areaIds in the config
     *               if it is {@code -1}
     */
    public void dumpPropertyValueByCommand(PrintWriter writer, int propertyId, int areaId) {
        var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
        if (propertyId == -1) {
            writer.println("**All property values**");
            for (int i = 0; i < propertyConfigsByPropId.size(); i++) {
                HalPropConfig config = propertyConfigsByPropId.valueAt(i);
                dumpPropertyValueByConfig(writer, config);
            }
        } else if (areaId == -1) {
            HalPropConfig config = propertyConfigsByPropId.get(propertyId);
            if (config == null) {
                writer.printf("Property: %s not supported by HAL\n",
                        toPropertyIdString(propertyId));
                return;
            }
            dumpPropertyValueByConfig(writer, config);
        } else {
            try {
                HalPropValue value = get(propertyId, areaId);
                dumpPropValue(writer, value);
            } catch (RuntimeException e) {
                writer.printf("Cannot get property value for property: %s in areaId: %s.\n",
                        toPropertyIdString(propertyId), toAreaIdString(propertyId, areaId));
            }
        }
    }

    /**
     * Gets all property configs from VHAL.
     */
    public HalPropConfig[] getAllPropConfigs() throws RemoteException, ServiceSpecificException {
        return mVehicleStub.get().getAllPropConfigs();
    }

    /**
     * Gets the property config for a property, returns {@code null} if not supported.
     */
    public @Nullable HalPropConfig getPropConfig(int propId) {
        return mPropertyConfigsByPropIdRef.get().get(propId);
    }

    /**
     * Checks whether we are connected to AIDL VHAL: {@code true} or HIDL VHAL: {@code false}.
     */
    public boolean isAidlVhal() {
        return mVehicleStub.get().isAidlVhal();
    }

    /**
     * Checks if fake VHAL mode is enabled.
     *
     * @return {@code true} if car service is connected to FakeVehicleStub.
     */
    public boolean isFakeModeEnabled() {
        return mVehicleStub.get().isFakeModeEnabled();
    }

    private void dumpPropertyValueByConfig(PrintWriter writer, HalPropConfig config) {
        int propertyId = config.getPropId();
        HalAreaConfig[] areaConfigs = config.getAreaConfigs();
        if (areaConfigs == null || areaConfigs.length == 0) {
            try {
                HalPropValue value = get(config.getPropId());
                dumpPropValue(writer, value);
            } catch (RuntimeException e) {
                writer.printf("Can not get property value for property: %s, areaId: %s\n",
                        toPropertyIdString(propertyId), toAreaIdString(propertyId, /*areaId=*/0));
            }
        } else {
            for (HalAreaConfig areaConfig : areaConfigs) {
                int areaId = areaConfig.getAreaId();
                try {
                    HalPropValue value = get(propertyId, areaId);
                    dumpPropValue(writer, value);
                } catch (RuntimeException e) {
                    writer.printf(
                            "Can not get property value for property: %s in areaId: %s\n",
                            toPropertyIdString(propertyId), toAreaIdString(propertyId, areaId));
                }
            }
        }
    }

    /**
     * Dump VHAL property configs.
     * Dump all properties if {@code propertyId} is equal to {@code -1}.
     *
     * @param propertyId the property ID
     */
    public void dumpPropertyConfigs(PrintWriter writer, int propertyId) {
        HalPropConfig[] configs;
        var propertyConfigsByPropId = mPropertyConfigsByPropIdRef.get();
        configs = new HalPropConfig[propertyConfigsByPropId.size()];
        for (int i = 0; i < propertyConfigsByPropId.size(); i++) {
            configs[i] = propertyConfigsByPropId.valueAt(i);
        }

        if (propertyId == -1) {
            writer.println("**All properties**");
            for (HalPropConfig config : configs) {
                dumpPropertyConfigsHelp(writer, config);
            }
            return;
        }
        for (HalPropConfig config : configs) {
            if (config.getPropId() == propertyId) {
                dumpPropertyConfigsHelp(writer, config);
                return;
            }
        }
    }


    /** Dumps VehiclePropertyConfigs */
    private static void dumpPropertyConfigsHelp(PrintWriter writer, HalPropConfig config) {
        int propertyId = config.getPropId();
        writer.printf(
                "Property:%s, group:%s, areaType:%s, valueType:%s,\n    access:%s, changeMode:%s, "
                        + "configArray:%s, minSampleRateHz:%f, maxSampleRateHz:%f\n",
                toPropertyIdString(propertyId), toGroupString(propertyId),
                toAreaTypeString(propertyId), toValueTypeString(propertyId),
                toAccessString(config.getAccess()), toChangeModeString(config.getChangeMode()),
                Arrays.toString(config.getConfigArray()), config.getMinSampleRate(),
                config.getMaxSampleRate());
        if (config.getAreaConfigs() == null) {
            return;
        }
        for (HalAreaConfig area : config.getAreaConfigs()) {
            writer.printf("        areaId:%s, access:%s, f min:%f, f max:%f, i min:%d, i max:%d,"
                            + " i64 min:%d, i64 max:%d\n", toAreaIdString(propertyId,
                            area.getAreaId()), toAccessString(area.getAccess()),
                    area.getMinFloatValue(), area.getMaxFloatValue(), area.getMinInt32Value(),
                    area.getMaxInt32Value(), area.getMinInt64Value(), area.getMaxInt64Value());
        }
    }

    /**
     * Inject a VHAL event
     *
     * @param propertyId       the property ID as defined in the HAL
     * @param areaId           the area ID that this event services
     * @param value            the data value of the event
     * @param delayTimeSeconds add a certain duration to event timestamp
     */
    public void injectVhalEvent(int propertyId, int areaId, String value, int delayTimeSeconds)
            throws NumberFormatException {
        long timestampNanos = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(
                delayTimeSeconds);
        HalPropValue v = createPropValueForInjecting(mPropValueBuilder, propertyId, areaId,
                Arrays.asList(value.split(DATA_DELIMITER)), timestampNanos);
        if (v == null) {
            return;
        }
        handleOnPropertyEvent(Lists.newArrayList(v));
    }

    /**
     * Injects continuous VHAL events.
     *
     * @param property the Vehicle property Id as defined in the HAL
     * @param zone the zone that this event services
     * @param value the data value of the event
     * @param sampleRate the sample rate for events in Hz
     * @param timeDurationInSec the duration for injecting events in seconds
     */
    public void injectContinuousVhalEvent(int property, int zone, String value,
            float sampleRate, long timeDurationInSec) {

        HalPropValue v = createPropValueForInjecting(mPropValueBuilder, property, zone,
                new ArrayList<>(Arrays.asList(value.split(DATA_DELIMITER))), 0);
        if (v == null) {
            return;
        }
        // rate in Hz
        if (sampleRate <= 0) {
            Slogf.e(CarLog.TAG_HAL, "Inject events at an invalid sample rate: " + sampleRate);
            return;
        }
        long period = (long) (1000 / sampleRate);
        long stopTime = timeDurationInSec * 1000 + SystemClock.elapsedRealtime();
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (stopTime < SystemClock.elapsedRealtime()) {
                    timer.cancel();
                    timer.purge();
                } else {
                    // Avoid the fake events be covered by real Event
                    long timestamp = SystemClock.elapsedRealtimeNanos()
                            + TimeUnit.SECONDS.toNanos(timeDurationInSec);
                    HalPropValue v = createPropValueForInjecting(mPropValueBuilder, property, zone,
                            new ArrayList<>(Arrays.asList(value.split(DATA_DELIMITER))), timestamp);
                    handleOnPropertyEvent(Lists.newArrayList(v));
                }
            }
        }, /* delay= */0, period);
    }

    // Returns null if the property type is unsupported.
    @Nullable
    private static HalPropValue createPropValueForInjecting(HalPropValueBuilder builder,
            int propId, int zoneId, List<String> dataList, long timestamp) {
        int propertyType = propId & VehiclePropertyType.MASK;
        // Values can be comma separated list
        switch (propertyType) {
            case VehiclePropertyType.BOOLEAN:
                boolean boolValue = Boolean.parseBoolean(dataList.get(0));
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        boolValue ? 1 : 0);
            case VehiclePropertyType.INT64:
            case VehiclePropertyType.INT64_VEC:
                long[] longValues = new long[dataList.size()];
                for (int i = 0; i < dataList.size(); i++) {
                    longValues[i] = Long.decode(dataList.get(i));
                }
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        longValues);
            case VehiclePropertyType.INT32:
            case VehiclePropertyType.INT32_VEC:
                int[] intValues = new int[dataList.size()];
                for (int i = 0; i < dataList.size(); i++) {
                    intValues[i] = Integer.decode(dataList.get(i));
                }
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        intValues);
            case VehiclePropertyType.FLOAT:
            case VehiclePropertyType.FLOAT_VEC:
                float[] floatValues = new float[dataList.size()];
                for (int i = 0; i < dataList.size(); i++) {
                    floatValues[i] = Float.parseFloat(dataList.get(i));
                }
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        floatValues);
            default:
                Slogf.e(CarLog.TAG_HAL, "Property type unsupported:" + propertyType);
                return null;
        }
    }

    private static class VehiclePropertyEventInfo {
        private int mEventCount;
        private HalPropValue mLastEvent;

        private VehiclePropertyEventInfo(HalPropValue event) {
            mEventCount = 1;
            mLastEvent = event;
        }

        private void addNewEvent(HalPropValue event) {
            mEventCount++;
            mLastEvent = event;
        }
    }

    final class HalPropValueSetter {
        final int mPropId;
        final int mAreaId;

        private HalPropValueSetter(int propId, int areaId) {
            mPropId = propId;
            mAreaId = areaId;
        }

        /**
         * Set the property to the given value.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(boolean value) throws IllegalArgumentException, ServiceSpecificException {
            to(value ? 1 : 0);
        }

        /**
         * Set the property to the given value.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(int value) throws IllegalArgumentException, ServiceSpecificException {
            HalPropValue propValue = mPropValueBuilder.build(mPropId, mAreaId, value);
            submit(propValue);
        }

        /**
         * Set the property to the given values.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(int[] values) throws IllegalArgumentException, ServiceSpecificException {
            HalPropValue propValue = mPropValueBuilder.build(mPropId, mAreaId, values);
            submit(propValue);
        }

        /**
         * Set the property to the given values.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(Collection<Integer> values)
                throws IllegalArgumentException, ServiceSpecificException {
            int[] intValues = new int[values.size()];
            int i = 0;
            for (int value : values) {
                intValues[i] = value;
                i++;
            }
            HalPropValue propValue = mPropValueBuilder.build(mPropId, mAreaId, intValues);
            submit(propValue);
        }

        void submit(HalPropValue propValue)
                throws IllegalArgumentException, ServiceSpecificException {
            if (DBG) {
                Slogf.d(CarLog.TAG_HAL, "set - " + propValue);
            }
            setValueWithRetry(propValue);
        }
    }

    private static void dumpPropValue(PrintWriter writer, HalPropValue value) {
        writer.println(value);
    }

    interface RetriableAction {
        @Nullable HalPropValue run(HalPropValue requestValue)
                throws ServiceSpecificException, RemoteException;
    }

    private static HalPropValue invokeRetriable(RetriableAction action,
            String operation, HalPropValue requestValue, long maxDurationForRetryMs,
            long sleepBetweenRetryMs, int maxRetries)
            throws ServiceSpecificException, IllegalArgumentException {
        Retrier retrier = new Retrier(action, operation, requestValue, maxDurationForRetryMs,
                sleepBetweenRetryMs, maxRetries);
        HalPropValue result = retrier.invokeAction();
        if (DBG) {
            Slogf.d(CarLog.TAG_HAL,
                    "Invoked retriable action for %s - RequestValue: %s - ResultValue: %s, for "
                            + "retrier: %s",
                    operation, requestValue, result, retrier);
        }
        return result;
    }

    private PairSparseArray<RateInfo> cloneState(PairSparseArray<RateInfo> state) {
        PairSparseArray<RateInfo> cloned = new PairSparseArray<>();
        for (int i = 0; i < state.size(); i++) {
            int[] keyPair = state.keyPairAt(i);
            cloned.put(keyPair[0], keyPair[1], state.valueAt(i));
        }
        return cloned;
    }

    private static boolean isStaticProperty(HalPropConfig config) {
        return config.getChangeMode() == VehiclePropertyChangeMode.STATIC;
    }

    private static final class Retrier {
        private final RetriableAction mAction;
        private final String mOperation;
        private final HalPropValue mRequestValue;
        private final long mMaxDurationForRetryMs;
        private final long mSleepBetweenRetryMs;
        private final int mMaxRetries;
        private final long mStartTime;
        private int mRetryCount = 0;

        Retrier(RetriableAction action,
                String operation, HalPropValue requestValue, long maxDurationForRetryMs,
                long sleepBetweenRetryMs, int maxRetries) {
            mAction = action;
            mOperation = operation;
            mRequestValue = requestValue;
            mMaxDurationForRetryMs = maxDurationForRetryMs;
            mSleepBetweenRetryMs = sleepBetweenRetryMs;
            mMaxRetries = maxRetries;
            mStartTime = uptimeMillis();
        }

        HalPropValue invokeAction()
                throws ServiceSpecificException, IllegalArgumentException {
            mRetryCount++;

            try {
                return mAction.run(mRequestValue);
            } catch (ServiceSpecificException e) {
                switch (e.errorCode) {
                    case StatusCode.INVALID_ARG:
                        throw new IllegalArgumentException(errorMessage(mOperation, mRequestValue,
                            e.toString()));
                    case StatusCode.TRY_AGAIN:
                        return sleepAndTryAgain(e);
                    default:
                        throw e;
                }
            } catch (RemoteException e) {
                return sleepAndTryAgain(e);
            }
        }

        public String toString() {
            return "Retrier{"
                    + ", Operation=" + mOperation
                    + ", RequestValue=" + mRequestValue
                    + ", MaxDurationForRetryMs=" + mMaxDurationForRetryMs
                    + ", SleepBetweenRetriesMs=" + mSleepBetweenRetryMs
                    + ", MaxRetries=" + mMaxDurationForRetryMs
                    + ", StartTime=" + mStartTime
                    + "}";
        }

        private HalPropValue sleepAndTryAgain(Exception e)
                throws ServiceSpecificException, IllegalArgumentException {
            Slogf.d(CarLog.TAG_HAL, "trying the request: "
                    + toPropertyIdString(mRequestValue.getPropId()) + ", "
                    + toAreaIdString(mRequestValue.getPropId(), mRequestValue.getAreaId())
                    + " again...");
            try {
                Thread.sleep(mSleepBetweenRetryMs);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                Slogf.w(CarLog.TAG_HAL, "Thread was interrupted while waiting for vehicle HAL.",
                        interruptedException);
                throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                        errorMessage(mOperation, mRequestValue, interruptedException.toString()));
            }

            if (mMaxRetries != 0) {
                // If mMaxRetries is specified, check the retry count.
                if (mMaxRetries == mRetryCount) {
                    throw new ServiceSpecificException(StatusCode.TRY_AGAIN,
                            errorMessage(mOperation, mRequestValue,
                                    "cannot get property after " + mRetryCount + " retires, "
                                    + "last exception: " + e));
                }
            } else if ((uptimeMillis() - mStartTime) >= mMaxDurationForRetryMs) {
                // Otherwise, check whether we have reached timeout.
                throw new ServiceSpecificException(StatusCode.TRY_AGAIN,
                        errorMessage(mOperation, mRequestValue,
                                "cannot get property within " + mMaxDurationForRetryMs
                                + "ms, last exception: " + e));
            }
            return invokeAction();
        }
    }


    /**
     * Queries HalPropValue with list of GetVehicleHalRequest objects.
     *
     * <p>This method gets the HalPropValue using async methods.
     */
    public void getAsync(List<VehicleStub.AsyncGetSetRequest> getVehicleStubAsyncRequests,
            VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback) {
        mVehicleStub.get().getAsync(getVehicleStubAsyncRequests, getVehicleStubAsyncCallback);
    }

    /**
     * Sets vehicle property value asynchronously.
     */
    public void setAsync(List<VehicleStub.AsyncGetSetRequest> setVehicleStubAsyncRequests,
            VehicleStub.VehicleStubCallbackInterface setVehicleStubAsyncCallback) {
        mVehicleStub.get().setAsync(setVehicleStubAsyncRequests, setVehicleStubAsyncCallback);
    }

    /**
     * Cancels all the on-going async requests with the given request IDs.
     */
    public void cancelRequests(List<Integer> vehicleStubRequestIds) {
        mVehicleStub.get().cancelRequests(vehicleStubRequestIds);
    }

    /**
     * Whether the [propId, areaId] supports dynamic supported values API.
     *
     * This is only supported if VHAL AreaIdConfig for it has non-null
     * {@code hasSupportedValuesInfo}.
     */
    public boolean isSupportedValuesImplemented(PropIdAreaId halPropIdAreaId) {
        HalPropConfig halPropConfig = getPropConfig(halPropIdAreaId.propId);
        if (halPropConfig == null) {
            Slogf.e(CarLog.TAG_HAL,
                    "No property config found for: %s, assume isSupportedValuesImplemented to be "
                    + "false", toHalPropIdAreaIdString(halPropIdAreaId));
            return false;
        }
        var areaConfigs = halPropConfig.getAreaConfigs();
        for (int i = 0; i < areaConfigs.length; i++) {
            var areaConfig = areaConfigs[i];
            if (areaConfig.getAreaId() == halPropIdAreaId.areaId) {
                return mVehicleStub.get().isSupportedValuesImplemented(areaConfig);
            }
        }
        Slogf.i(CarLog.TAG_HAL,
                "No area config found for: %s, assume isSupportedValuesImplemented to be "
                + "false", toHalPropIdAreaIdString(halPropIdAreaId));
        return false;

    }

    /**
     * Gets the min/max supported value.
     *
     * This should only be called if {@link #isSupportedValuesImplemented} is {@code true}.
     */
    public MinMaxSupportedRawPropValues getMinMaxSupportedValue(int propertyId, int areaId)
            throws ServiceSpecificException {
        return mVehicleStub.get().getMinMaxSupportedValue(propertyId, areaId);
    }

    /**
     * Gets the supported values list.
     *
     * This should only be called if {@link #isSupportedValuesImplemented} is {@code true}.
     */
    public @Nullable List<RawPropValues> getSupportedValuesList(int propertyId, int areaId)
            throws ServiceSpecificException {
        return mVehicleStub.get().getSupportedValuesList(propertyId, areaId);
    }

    private static class SupportedValuesChangeDispatchList extends
            DispatchList<HalServiceBase, PropIdAreaId> {
        @Override
        protected void dispatchToClient(HalServiceBase client, List<PropIdAreaId> events) {
            client.onSupportedValuesChange(events);
        }
    }

    @Override
    public void onSupportedValuesChange(List<PropIdAreaId> propIdAreaIds) {
        if (DBG) {
            Slogf.i(CarLog.TAG_HAL, "onSupportedValuesChange called for: %s",
                    toHalPropIdAreaIdsString(propIdAreaIds));
        }
        List<PropIdAreaId> filteredPropIdAreaIds = maybeFilterItemsForInjectionMode(
                propIdAreaIds,
                (PropIdAreaId propIdAreaId) -> propIdAreaId.propId);
        if (filteredPropIdAreaIds.isEmpty()) {
            Slogf.d(CarLog.TAG_HAL, "All onSupportedValuesChange events filtered %s",
                    Arrays.toString(filteredPropIdAreaIds.toArray()));
            return;
        }
        var dispatchList = new SupportedValuesChangeDispatchList();
        synchronized (mLock) {
            for (int i = 0; i < filteredPropIdAreaIds.size(); i++) {
                var propIdAreaId = filteredPropIdAreaIds.get(i);
                HalServiceBase service = mPropertyHandlers.get(propIdAreaId.propId);
                if (service == null) {
                    Slogf.e(CarLog.TAG_HAL, "onSupportedValuesChange: HalService not found for %s",
                            toHalPropIdAreaIdString(propIdAreaId));
                    continue;
                }

                var propIdAreaIdsForService = mSupportedValuesChangePropIdAreaIdsByService.get(
                        service);

                if (!propIdAreaIdsForService.contains(propIdAreaId)) {
                    Slogf.e(CarLog.TAG_HAL,
                            "onSupportedValuesChange: not registered for %s, ignore",
                            toHalPropIdAreaIdString(propIdAreaId));
                    continue;
                }
                dispatchList.addEvent(service, propIdAreaId);
            }
        }
        dispatchList.dispatchToClients();
    }

    /**
     * Registers the callback to be called when the min/max supported value or supported values
     * list change.
     *
     * This should only be called if {@link #isSupportedValuesImplemented} is {@code true}.
     *
     * @throws ServiceSpecificException If VHAL returns error.
     * @throws IllegalArgumentException If the service does not own one of the requested property
     *      ID.
     */
    public void registerSupportedValuesChange(HalServiceBase service,
            List<PropIdAreaId> propIdAreaIds) {
        synchronized (mLock) {
            for (int i = 0; i < propIdAreaIds.size(); i++) {
                int propertyId = propIdAreaIds.get(i).propId;
                assertServiceOwnerLocked(service, propertyId);
            }

            var registeredPropIdAreaIds = mSupportedValuesChangePropIdAreaIdsByService.get(service);
            if (registeredPropIdAreaIds == null) {
                registeredPropIdAreaIds = new ArraySet<PropIdAreaId>();
            }

            // Here we do not filter out already registered [propId, areaId]s, we expect each
            // service to filter out duplicate requests.
            mSubscriptionClient.registerSupportedValuesChange(propIdAreaIds);

            for (int i = 0; i < propIdAreaIds.size(); i++) {
                registeredPropIdAreaIds.add(propIdAreaIds.get(i));
            }
            mSupportedValuesChangePropIdAreaIdsByService.put(service, registeredPropIdAreaIds);
        }
    }

    /**
     * Unregisters the [propId, areaId]s previously registered with
     * registerSupportedValuesChange.
     *
     * Do nothing if the [propId, areaId]s were not previously registered.
     *
     * This should only be called if {@link #isSupportedValuesImplemented} is {@code true}.
     *
     * @throws IllegalArgumentException If the service does not own one of the requested property
     *      ID.
     */
    public void unregisterSupportedValuesChange(HalServiceBase service,
            List<PropIdAreaId> propIdAreaIds) {
        synchronized (mLock) {
            for (int i = 0; i < propIdAreaIds.size(); i++) {
                int propertyId = propIdAreaIds.get(i).propId;
                assertServiceOwnerLocked(service, propertyId);
            }
            var registeredPropIdAreaIds = mSupportedValuesChangePropIdAreaIdsByService.get(service);
            if (registeredPropIdAreaIds == null) {
                return;
            }

            List<PropIdAreaId> propIdAreaIdsToUnRegister = new ArrayList<>();
            for (int i = 0; i < propIdAreaIds.size(); i++) {
                var propIdAreaId = propIdAreaIds.get(i);
                if (registeredPropIdAreaIds.remove(propIdAreaId)) {
                    propIdAreaIdsToUnRegister.add(propIdAreaId);
                }
                if (registeredPropIdAreaIds.isEmpty()) {
                    mSupportedValuesChangePropIdAreaIdsByService.remove(service);
                }
            }

            if (propIdAreaIdsToUnRegister.isEmpty()) {
                return;
            }
            mSubscriptionClient.unregisterSupportedValuesChange(propIdAreaIdsToUnRegister);
        }
    }
}
