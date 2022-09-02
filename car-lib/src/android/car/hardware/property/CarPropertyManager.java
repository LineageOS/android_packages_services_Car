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

package android.car.hardware.property;

import static java.lang.Integer.toHexString;
import static java.util.Objects.requireNonNull;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.VehicleAreaType;
import android.car.VehiclePropertyIds;
import android.car.annotation.AddedInOrBefore;
import android.car.annotation.ApiRequirements;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.internal.CarPropertyEventCallbackController;
import com.android.car.internal.SingleMessageHandler;
import com.android.car.internal.os.HandlerExecutor;
import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides an application interface for interacting with the Vehicle specific properties.
 * For details about the individual properties, see the descriptions in
 * hardware/interfaces/automotive/vehicle/types.hal
 */
public class CarPropertyManager extends CarManagerBase {
    private static final boolean DBG = false;
    private static final String TAG = "CarPropertyManager";
    private static final int MSG_GENERIC_EVENT = 0;
    /**
     * The default timeout in MS for {@link CarPropertyManager#getPropertiesAsync}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final long ASYNC_GET_DEFAULT_TIMEOUT_MS = 10_000;

    private final SingleMessageHandler<CarPropertyEvent> mHandler;
    private final ICarProperty mService;
    private final int mAppTargetSdk;
    private final AtomicInteger mRequestIdCounter = new AtomicInteger(0);
    @GuardedBy("mLock")
    private final SparseArray<GetAsyncPropertyClientInfo> mRequestIdToClientInfo =
            new SparseArray<>();
    private final GetAsyncPropertyResultCallback mGetAsyncPropertyResultCallback =
            new GetAsyncPropertyResultCallback();

    private final CarPropertyEventListenerToService mCarPropertyEventToService =
            new CarPropertyEventListenerToService(this);

    // This lock is shared with all CarPropertyEventCallbackController instances to prevent
    // potential deadlock.
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<CarPropertyEventCallbackController>
            mPropertyIdToCarPropertyEventCallbackController = new SparseArray<>();

    private final CarPropertyEventCallbackController.RegistrationUpdateCallback
            mRegistrationUpdateCallback =
            new CarPropertyEventCallbackController.RegistrationUpdateCallback() {
                @Override
                public boolean register(int propertyId, float updateRateHz) {
                    try {
                        mService.registerListener(propertyId, updateRateHz,
                                mCarPropertyEventToService);
                    } catch (RemoteException e) {
                        handleRemoteExceptionFromCarService(e);
                        return false;
                    }
                    return true;
                }

                @Override
                public void unregister(int propertyId) {
                    try {
                        mService.unregisterListener(propertyId, mCarPropertyEventToService);
                    } catch (RemoteException e) {
                        handleRemoteExceptionFromCarService(e);
                    }
                }
            };

    /**
     * Application registers {@link CarPropertyEventCallback} object to receive updates and changes
     * to subscribed Vehicle specific properties.
     */
    public interface CarPropertyEventCallback {
        /**
         * Called when a property is updated
         * @param value Property that has been updated.
         */
        @AddedInOrBefore(majorVersion = 33)
        void onChangeEvent(CarPropertyValue value);

        /**
         * Called when an error is detected when setting a property.
         *
         * @param propId Property ID which is detected an error.
         * @param zone Zone which is detected an error.
         *
         * @see CarPropertyEventCallback#onErrorEvent(int, int, int)
         */
        @AddedInOrBefore(majorVersion = 33)
        void onErrorEvent(int propId, int zone);

        /**
         * Called when an error is detected when setting a property.
         *
         * <p>Clients which changed the property value in the areaId most recently will receive
         * this callback. If multiple clients set a property for the same area id simultaneously,
         * which one takes precedence is undefined. Typically, the last set operation
         * (in the order that they are issued to car's ECU) overrides the previous set operations.
         * The delivered error reflects the error happened in the last set operation.
         *
         * @param propId Property ID which is detected an error.
         * @param areaId AreaId which is detected an error.
         * @param errorCode Error code is raised in the car.
         */
        @AddedInOrBefore(majorVersion = 33)
        default void onErrorEvent(int propId, int areaId, @CarSetPropertyErrorCode int errorCode) {
            if (DBG) {
                Log.d(TAG, "onErrorEvent propertyId: 0x" + toHexString(propId) + " areaId:0x"
                        + toHexString(areaId) + " ErrorCode: " + errorCode);
            }
            onErrorEvent(propId, areaId);
        }
    }

    /**
     * A callback {@link CarPropertyManager#getPropertiesAsync} when successful or failure.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    // TODO(b/243057322): Remove this if/once lint is fixed.
    @SuppressLint("CallbackInterface")
    public interface GetPropertyCallback {
        /**
         * Method called when {@link GetPropertyRequest} successfully gets a result.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onSuccess(@NonNull GetPropertyResult getPropertyResult);

        /**
         * Method called when {@link GetPropertyRequest} returns an error.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onFailure(@NonNull GetPropertyError getPropertyError);
    }

    /**
     * A request for {@link CarPropertyManager#getPropertiesAsync(List, long, CancellationSignal,
     * Executor, GetPropertyCallback)}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class GetPropertyRequest {
        /**
         * The requestId to uniquely identify the request.
         *
         * <p>Each request must have a unique request ID so the responses can be differentiated.
         */
        private final int mRequestId;
        /**
         * The ID for the property.
         *
         * <p>Must be one of the {@link VehiclePropertyIds} or vendor property IDs.
         */
        private final int mPropertyId;
        /**
         * Optional ID for the area, default to 0. Ignored for global property.
         */
        private final int mAreaId;

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        /**
         * Internal use only. Users should use {@link generateGetPropertyRequest} instead.
         */
        private GetPropertyRequest(int requestId, int propertyId, int areaId) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
        }
    }

    /**
     * A successful result for {@link GetPropertyCallback}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class GetPropertyResult {
        private final int mRequestId;
        private final CarPropertyValue mCarPropertyValue;

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public CarPropertyValue getCarPropertyValue() {
            return mCarPropertyValue;
        }

        public GetPropertyResult(int requestId, @NonNull CarPropertyValue carPropertyValue) {
            mRequestId = requestId;
            mCarPropertyValue = carPropertyValue;
        }
    }

    /**
     * A class for delivering {@link GetPropertyCallback} client callback when
     * {@link IGetAsyncPropertyResultCallback} returns a result.
     */
    private class GetAsyncPropertyResultCallback extends IGetAsyncPropertyResultCallback.Stub {

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onGetValueResult(List<GetValueResult> getValueResults) {
            for (int i = 0; i < getValueResults.size(); i++) {
                GetValueResult getValueResult = getValueResults.get(i);
                int requestId = getValueResult.getRequestId();
                GetAsyncPropertyClientInfo getAsyncPropertyClientInfo;
                synchronized (mLock) {
                    getAsyncPropertyClientInfo = mRequestIdToClientInfo.get(requestId);
                    mRequestIdToClientInfo.remove(requestId);
                }
                if (getAsyncPropertyClientInfo == null) {
                    Log.w(TAG, "onGetValueResult: Request ID: " + requestId
                            + " might have been completed or an exception might have been thrown");
                    continue;
                }
                Executor callbackExecutor = getAsyncPropertyClientInfo.getCallbackExecutor();
                GetPropertyCallback getPropertyCallback =
                        getAsyncPropertyClientInfo.getGetPropertyCallback();
                @CarPropertyAsyncErrorCode
                int errorCode = getValueResult.getErrorCode();
                if (errorCode == STATUS_OK) {
                    callbackExecutor.execute(() -> getPropertyCallback.onSuccess(
                            new GetPropertyResult(requestId,
                                    getValueResult.getCarPropertyValue())));
                } else {
                    callbackExecutor.execute(() -> getPropertyCallback.onFailure(
                            new GetPropertyError(requestId, errorCode)));
                }
            }
        }
    }

    /**
     * A class to store client info when {@link CarPropertyManager#getPropertiesAsync} is called.
     */
    private static final class GetAsyncPropertyClientInfo {
        private final GetPropertyRequest mGetPropertyRequest;
        private final Executor mCallbackExecutor;
        private final GetPropertyCallback mGetPropertyCallback;

        public GetPropertyRequest getGetPropertyRequest() {
            return mGetPropertyRequest;
        }

        public Executor getCallbackExecutor() {
            return mCallbackExecutor;
        }

        public GetPropertyCallback getGetPropertyCallback() {
            return mGetPropertyCallback;
        }

        /**
         * Get an instance of GetAsyncPropertyClientInfo.
         */
        private GetAsyncPropertyClientInfo(GetPropertyRequest getPropertyRequest,
                Executor callbackExecutor, GetPropertyCallback getPropertyCallback) {
            mGetPropertyRequest = getPropertyRequest;
            mCallbackExecutor = callbackExecutor;
            mGetPropertyCallback = getPropertyCallback;
        }
    }

    /**
     * An error result for {@link GetPropertyCallback}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class GetPropertyError {
        private final int mRequestId;
        private @CarPropertyAsyncErrorCode int mErrorCode;

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getErrorCode() {
            return mErrorCode;
        }

        public GetPropertyError(int requestId, @CarPropertyAsyncErrorCode int errorCode) {
            mRequestId = requestId;
            mErrorCode = errorCode;
        }
    }

    /** Read ONCHANGE sensors. */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_ONCHANGE = 0f;
    /** Read sensors at the rate of  1 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_NORMAL = 1f;
    /** Read sensors at the rate of 5 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_UI = 5f;
    /** Read sensors at the rate of 10 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_FAST = 10f;
    /** Read sensors at the rate of 100 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_FASTEST = 100f;



    /**
     * Status to indicate that set operation failed. Try it again.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN = 1;

    /**
     * Status to indicate that set operation failed because of an invalid argument.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG = 2;

    /**
     * Status to indicate that set operation failed because the property is not available.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_PROPERTY_NOT_AVAILABLE = 3;

    /**
     * Status to indicate that set operation failed because car denied access to the property.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_ACCESS_DENIED = 4;

    /**
     * Status to indicate that set operation failed because of a general error in cars.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN = 5;

    /** @hide */
    @IntDef(prefix = {"CAR_SET_PROPERTY_ERROR_CODE_"}, value = {
            CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN,
            CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG,
            CAR_SET_PROPERTY_ERROR_CODE_PROPERTY_NOT_AVAILABLE,
            CAR_SET_PROPERTY_ERROR_CODE_ACCESS_DENIED,
            CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarSetPropertyErrorCode {}

    /**
     * Status indicating no error.
     *
     * <p>This is not exposed to the client as this will be used only for deciding
     * {@link GetPropertyCallback#onSuccess} or {@link GetPropertyCallback#onFailure} is called.
     *
     * @hide
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int STATUS_OK = 0;
    /**
     * Error indicating that there is an error detected in cars.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int STATUS_ERROR_INTERNAL_ERROR = 1;
    /**
     * Error indicating that the property is temporarily not available.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int STATUS_ERROR_NOT_AVAILABLE = 2;
    /**
     * Error indicating the operation has timed-out.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int STATUS_ERROR_TIMEOUT = 3;

    /** @hide */
    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_OK,
            STATUS_ERROR_INTERNAL_ERROR,
            STATUS_ERROR_NOT_AVAILABLE,
            STATUS_ERROR_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarPropertyAsyncErrorCode {}

    /**
     * Get an instance of the CarPropertyManager.
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     * @param car Car instance
     * @param service ICarProperty instance
     * @hide
     */
    public CarPropertyManager(Car car, @NonNull ICarProperty service) {
        super(car);
        mService = service;
        mAppTargetSdk = getContext().getApplicationInfo().targetSdkVersion;

        Handler eventHandler = getEventHandler();
        if (eventHandler == null) {
            mHandler = null;
            return;
        }
        mHandler = new SingleMessageHandler<CarPropertyEvent>(eventHandler.getLooper(),
                MSG_GENERIC_EVENT) {
            @Override
            protected void handleEvent(CarPropertyEvent carPropertyEvent) {
                CarPropertyEventCallbackController carPropertyEventCallbackController;
                synchronized (mLock) {
                    carPropertyEventCallbackController =
                            mPropertyIdToCarPropertyEventCallbackController.get(
                                    carPropertyEvent.getCarPropertyValue().getPropertyId());
                }
                if (carPropertyEventCallbackController == null) {
                    return;
                }
                switch (carPropertyEvent.getEventType()) {
                    case CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE:
                        carPropertyEventCallbackController.forwardPropertyChanged(carPropertyEvent);
                        break;
                    case CarPropertyEvent.PROPERTY_EVENT_ERROR:
                        carPropertyEventCallbackController.forwardErrorEvent(carPropertyEvent);
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
            }
        };
    }

    /**
     * Register {@link CarPropertyEventCallback} to get property updates. Multiple callbacks
     * can be registered for a single property or the same callback can be used for different
     * properties. If the same callback is registered again for the same property, it will be
     * updated to new updateRateHz.
     * <p>Rate could be one of the following:
     * <ul>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_ONCHANGE}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_NORMAL}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_UI}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_FAST}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_FASTEST}</li>
     * </ul>
     * <p>
     * <b>Note:</b>Rate has no effect if the property has one of the following change modes:
     * <ul>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_STATIC}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE}</li>
     * </ul>
     * <b>Note:</b>If listener registers for updates for a
     * {@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE} property, it will receive the
     * property's current value upon registration.
     * See {@link CarPropertyConfig#getChangeMode()} for details.
     * If updateRateHz is higher than {@link CarPropertyConfig#getMaxSampleRate()}, it will be
     * registered with max sample updateRateHz.
     * If updateRateHz is lower than {@link CarPropertyConfig#getMinSampleRate()}, it will be
     * registered with min sample updateRateHz.
     *
     * @param carPropertyEventCallback CarPropertyEventCallback to be registered.
     * @param propertyId               PropertyId to subscribe
     * @param rate                     how fast the property events are delivered in Hz.
     * @return {@code true} if the listener is successfully registered.
     * @throws SecurityException if missing the appropriate permission.
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean registerCallback(@NonNull CarPropertyEventCallback carPropertyEventCallback,
            int propertyId, @FloatRange(from = 0.0, to = 100.0) float rate) {
        requireNonNull(carPropertyEventCallback);
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        float updateRateHz = rate;
        if (carPropertyConfig == null) {
            Log.e(TAG, "registerListener:  propId is not in carPropertyConfig list:  "
                    + VehiclePropertyIds.toString(propertyId));
            return false;
        }
        if (carPropertyConfig.getChangeMode()
                != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            updateRateHz = SENSOR_RATE_ONCHANGE;
        } else if (updateRateHz > carPropertyConfig.getMaxSampleRate()) {
            updateRateHz = carPropertyConfig.getMaxSampleRate();
        } else if (updateRateHz < carPropertyConfig.getMinSampleRate()) {
            updateRateHz = carPropertyConfig.getMinSampleRate();
        }
        CarPropertyEventCallbackController carPropertyEventCallbackController;
        synchronized (mLock) {
            carPropertyEventCallbackController =
                    mPropertyIdToCarPropertyEventCallbackController.get(propertyId);
            if (carPropertyEventCallbackController == null) {
                carPropertyEventCallbackController = new CarPropertyEventCallbackController(
                        propertyId, mLock, mRegistrationUpdateCallback);
                mPropertyIdToCarPropertyEventCallbackController.put(propertyId,
                        carPropertyEventCallbackController);
            }
        }
        return carPropertyEventCallbackController.add(carPropertyEventCallback, updateRateHz);
    }

    private static class CarPropertyEventListenerToService extends ICarPropertyEventListener.Stub {
        private final WeakReference<CarPropertyManager> mCarPropertyManager;

        CarPropertyEventListenerToService(CarPropertyManager carPropertyManager) {
            mCarPropertyManager = new WeakReference<>(carPropertyManager);
        }

        @Override
        public void onEvent(List<CarPropertyEvent> carPropertyEvents) throws RemoteException {
            CarPropertyManager carPropertyManager = mCarPropertyManager.get();
            if (carPropertyManager != null) {
                carPropertyManager.handleEvents(carPropertyEvents);
            }
        }
    }

    private void handleEvents(List<CarPropertyEvent> carPropertyEvents) {
        if (mHandler != null) {
            mHandler.sendEvents(carPropertyEvents);
        }
    }

    /**
     * Stop getting property updates for the given {@link CarPropertyEventCallback}. If there are
     * multiple registrations for this {@link CarPropertyEventCallback}, all listening will be
     * stopped.
     */
    @AddedInOrBefore(majorVersion = 33)
    public void unregisterCallback(@NonNull CarPropertyEventCallback carPropertyEventCallback) {
        requireNonNull(carPropertyEventCallback);
        int[] propertyIds;
        synchronized (mLock) {
            propertyIds = new int[mPropertyIdToCarPropertyEventCallbackController.size()];
            for (int i = 0; i < mPropertyIdToCarPropertyEventCallbackController.size(); i++) {
                propertyIds[i] = mPropertyIdToCarPropertyEventCallbackController.keyAt(i);
            }
        }
        for (int propertyId : propertyIds) {
            unregisterCallback(carPropertyEventCallback, propertyId);
        }
    }

    /**
     * Stop getting update for {@code propertyId} to the given {@link CarPropertyEventCallback}. If
     * the same {@link CarPropertyEventCallback} is used for other properties, those subscriptions
     * will not be affected.
     */
    @AddedInOrBefore(majorVersion = 33)
    public void unregisterCallback(@NonNull CarPropertyEventCallback carPropertyEventCallback,
            int propertyId) {
        requireNonNull(carPropertyEventCallback);
        CarPropertyEventCallbackController carPropertyEventCallbackController;
        synchronized (mLock) {
            carPropertyEventCallbackController =
                    mPropertyIdToCarPropertyEventCallbackController.get(propertyId);
        }
        if (carPropertyEventCallbackController == null) {
            return;
        }
        boolean allCallbacksRemoved = carPropertyEventCallbackController.remove(
                carPropertyEventCallback);
        if (allCallbacksRemoved) {
            synchronized (mLock) {
                mPropertyIdToCarPropertyEventCallbackController.remove(propertyId);
            }
        }
    }

    /**
     * @return List of properties implemented by this car that the application may access
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public List<CarPropertyConfig> getPropertyList() {
        List<CarPropertyConfig> configs;
        try {
            configs = mService.getPropertyList();
        } catch (RemoteException e) {
            Log.e(TAG, "getPropertyList exception ", e);
            return handleRemoteExceptionFromCarService(e, new ArrayList<>());
        }
        return configs;
    }

    /**
     * @param propertyIds property ID list
     * @return List of properties implemented by this car in given property ID list that application
     *          may access
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public List<CarPropertyConfig> getPropertyList(@NonNull ArraySet<Integer> propertyIds) {
        int[] propIds = new int[propertyIds.size()];
        int idx = 0;
        for (int propId : propertyIds) {
            propIds[idx++] = propId;
        }
        List<CarPropertyConfig> configs;
        try {
            configs = mService.getPropertyConfigList(propIds);
        } catch (RemoteException e) {
            Log.e(TAG, "getPropertyList exception ", e);
            return handleRemoteExceptionFromCarService(e, new ArrayList<>());
        }
        return configs;
    }

    /**
     * Get CarPropertyConfig by property ID.
     *
     * @param propId Property ID
     * @return {@link CarPropertyConfig} for the selected property, {@code null} if the property is
     * not available.
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public CarPropertyConfig<?> getCarPropertyConfig(int propId) {
        List<CarPropertyConfig> configs;
        try {
            configs = mService.getPropertyConfigList(new int[] {propId});
        } catch (RemoteException e) {
            Log.e(TAG, "getPropertyList exception ", e);
            return handleRemoteExceptionFromCarService(e, null);
        }
        return configs.size() == 0 ? null : configs.get(0);
    }

    /**
     * Returns areaId contains the selected area for the property.
     *
     * @param propId Property ID
     * @param area Area enum such as Enums in {@link android.car.VehicleAreaSeat}.
     * @throws IllegalArgumentException if the property is not available in the vehicle for
     * the selected area
     * @return {@link AreaId} containing the selected area for the property
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getAreaId(int propId, int area) {
        CarPropertyConfig<?> propConfig = getCarPropertyConfig(propId);
        if (propConfig == null) {
            throw new IllegalArgumentException("The property propId: 0x" + toHexString(propId)
                    + " is not available");
        }
        // For the global property, areaId is 0
        if (propConfig.isGlobalProperty()) {
            return VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL;
        }
        for (int areaId : propConfig.getAreaIds()) {
            if ((area & areaId) == area) {
                return areaId;
            }
        }

        throw new IllegalArgumentException("The property propId: 0x" + toHexString(propId)
                + " is not available at the area: 0x" + toHexString(area));
    }

    /**
     * Return read permission string for given property ID.
     *
     * @param propId Property ID to query
     * @return Permission needed to read this property, {@code null} if propId not available
     * @hide
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public String getReadPermission(int propId) {
        if (DBG) {
            Log.d(TAG, "getReadPermission, propId: 0x" + toHexString(propId));
        }
        try {
            return mService.getReadPermission(propId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, "");
        }
    }

    /**
     * Return write permission string for given property ID.
     *
     * @param propId Property ID to query
     * @return Permission needed to write this property, {@code null} if propId not available
     * @hide
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public String getWritePermission(int propId) {
        if (DBG) {
            Log.d(TAG, "getWritePermission, propId: 0x" + toHexString(propId));
        }
        try {
            return mService.getWritePermission(propId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, "");
        }
    }


    /**
     * Check whether a given property is available or disabled based on the car's current state.
     * @param propId Property ID
     * @param area AreaId of property
     * @return {@code true} if STATUS_AVAILABLE, {@code false} otherwise (eg STATUS_UNAVAILABLE)
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean isPropertyAvailable(int propId, int area) {
        try {
            CarPropertyValue propValue = mService.getProperty(propId, area);
            return (propValue != null)
                    && (propValue.getStatus() == CarPropertyValue.STATUS_AVAILABLE);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "unable to get property, error: " + e);
            return false;
        }
    }

    /**
     * Returns value of a bool property
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions if the call
     * fails.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars.
     *     <li>{@link IllegalArgumentException} when the property in the areaId is not supplied.
     * </ul>
     *
     * @param prop Property ID to get
     * @param area Area of the property to get
     *
     * @throws CarInternalErrorException when there is an error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is temporarily not available
     * @throws IllegalArgumentException when the property in the areaId is not supplied
     *
     * @return value of a bool property, {@code false} if unable to get value from car.
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean getBooleanProperty(int prop, int area) {
        CarPropertyValue<Boolean> carProp = getProperty(Boolean.class, prop, area);
        return handleNullAndPropertyStatus(carProp, area, false);
    }

    /**
     * Returns value of a float property
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p> This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param prop Property ID to get
     * @param area Area of the property to get
     *
     * @throws CarInternalErrorException when there is an error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is temporarily not available
     * @throws IllegalArgumentException when the property in the areaId is not supplied
     *
     * @return value of a float property, 0 if unable to get value from the car.
     */
    @AddedInOrBefore(majorVersion = 33)
    public float getFloatProperty(int prop, int area) {
        CarPropertyValue<Float> carProp = getProperty(Float.class, prop, area);
        return handleNullAndPropertyStatus(carProp, area, 0f);
    }

    /**
     * Returns value of an integer property
     *
     * <p> This method may take couple seconds to complete, so it needs to be called form a
     * non-main thread.
     *
     * <p> This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param prop Property ID to get
     * @param area Zone of the property to get
     *
     * @throws CarInternalErrorException when there is an error detected in cars
     * @throws PropertyAccessDeniedSecurityException} when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException} when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is temporarily not available
     * @throws IllegalArgumentException when the property in the areaId is not supplied
     *
     * @return value of an integer property, 0 if unable to get the value from car.
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getIntProperty(int prop, int area) {
        CarPropertyValue<Integer> carProp = getProperty(Integer.class, prop, area);
        return handleNullAndPropertyStatus(carProp, area, 0);
    }

    /**
     * Returns value of an integer array property
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p> This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param prop Property ID to get
     * @param area Zone of the property to get
     *
     * @throws CarInternalErrorException when there is an error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException} when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException} when the property is temporarily not available
     * @throws IllegalArgumentException} when the property in the areaId is not supplied
     *
     * @return value of an integer array property, an empty integer array if unable to get the value
     * from car
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public int[] getIntArrayProperty(int prop, int area) {
        CarPropertyValue<Integer[]> carProp = getProperty(Integer[].class, prop, area);
        Integer[] res = handleNullAndPropertyStatus(carProp, area, new Integer[0]);
        return toIntArray(res);
    }

    private static int[] toIntArray(Integer[] input) {
        int len = input.length;
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
            arr[i] = input[i];
        }
        return arr;
    }

    private <T> T handleNullAndPropertyStatus(CarPropertyValue<T> propertyValue, int areaId,
            T defaultValue) {

        if (propertyValue == null) {
            return defaultValue;
        }

        // Keeps the same behavior as android R.
        if (mAppTargetSdk < Build.VERSION_CODES.S) {
            return propertyValue.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                    ? propertyValue.getValue() : defaultValue;
        }

        // throws new exceptions in android S.
        switch (propertyValue.getStatus()) {
            case CarPropertyValue.STATUS_ERROR:
                throw new CarInternalErrorException(propertyValue.getPropertyId(), areaId);
            case CarPropertyValue.STATUS_UNAVAILABLE:
                throw new PropertyNotAvailableException(propertyValue.getPropertyId(), areaId);
            default:
                return propertyValue.getValue();
        }
    }

    /**
     * Return CarPropertyValue
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars.
     *     <li>{@link IllegalArgumentException} when the property in the areaId is not supplied.
     * </ul>
     *
     * @param clazz The class object for the CarPropertyValue
     * @param propId Property ID to get
     * @param areaId Zone of the property to get
     *
     * @throws CarInternalErrorException when there is an error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is temporarily not available
     * @throws IllegalArgumentException when the property in the areaId is not supplied
     *
     * @return {@link CarPropertyValue} or {@code null} if property's id is invalid
     */
    @SuppressWarnings("unchecked")
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public <E> CarPropertyValue<E> getProperty(@NonNull Class<E> clazz, int propId, int areaId) {
        CarPropertyValue<E> carPropertyValue = getProperty(propId, areaId);
        if (carPropertyValue != null && carPropertyValue.getValue() != null) {
            Class<?> actualClass = carPropertyValue.getValue().getClass();
            if (actualClass != clazz) {
                throw new IllegalArgumentException(
                        "Invalid property type. " + "Expected: " + clazz + ", but was: "
                                + actualClass);
            }
        }
        return carPropertyValue;
    }

    /**
     * Query CarPropertyValue with property id and areaId.
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars.
     *     <li>{@link IllegalArgumentException} when the property in the areaId is not supplied.
     * </ul>
     *
     * @param propId Property ID
     * @param areaId areaId
     * @param <E> Value type of the property
     *
     * @throws CarInternalErrorException when there is an error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is temporarily not available
     * @throws IllegalArgumentException when the property in the areaId is not supplied
     *
     * @return {@link CarPropertyValue} or {@code null} if property's id is invalid
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public <E> CarPropertyValue<E> getProperty(int propId, int areaId) {
        if (DBG) {
            Log.d(TAG, "getProperty, propId: " + VehiclePropertyIds.toString(propId)
                    + ", areaId: 0x" + toHexString(areaId));
        }

        try {
            return (CarPropertyValue<E>) mService.getProperty(propId, areaId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        } catch (ServiceSpecificException e) {
            if (mAppTargetSdk < Build.VERSION_CODES.R) {
                if (e.errorCode == VehicleHalStatusCode.STATUS_TRY_AGAIN) {
                    return null;
                } else {
                    throw new IllegalStateException(String.format("Failed to get property: 0x%x, "
                            + "areaId: 0x%x", propId, areaId), e);
                }
            }
            handleCarServiceSpecificException(e, propId, areaId);
            return null;
        }
    }

    /**
     * Set value of car property by areaId.
     *
     * <p>If multiple clients set a property for the same area id simultaneously, which one takes
     * precedence is undefined. Typically, the last set operation (in the order that they are issued
     * to the car's ECU) overrides the previous set operations.
     *
     * <p> This method may take couple seconds to complete, so it needs to be called form a
     * non-main thread.
     *
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     * <p> Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link RuntimeException} when the property is temporarily not available.
     *     <li>{@link IllegalStateException} when there is an error detected in cars.
     *     <li>{@link IllegalArgumentException} when the property in the areaId is not supplied
     * </ul>
     *
     * @param clazz The class object for the CarPropertyValue
     * @param propId Property ID
     * @param areaId areaId
     * @param val Value of CarPropertyValue
     * @param <E> data type of the given property, for example property that was
     * defined as {@code VEHICLE_VALUE_TYPE_INT32} in vehicle HAL could be accessed using
     * {@code Integer.class}.
     *
     * @throws CarInternalErrorException when there is an error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the property
     * @throws PropertyNotAvailableException when the property is temporarily not available
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily not available
     * and likely that retrying will be successful
     * @throws IllegalStateException when get an unexpected error code
     * @throws IllegalArgumentException when the property in the areaId is not supplied
     */
    @AddedInOrBefore(majorVersion = 33)
    public <E> void setProperty(@NonNull Class<E> clazz, int propId, int areaId, @NonNull E val) {
        if (DBG) {
            Log.d(TAG, "setProperty, propId: 0x" + toHexString(propId)
                    + ", areaId: 0x" + toHexString(areaId) + ", class: " + clazz + ", val: " + val);
        }
        try {
            mService.setProperty(new CarPropertyValue<>(propId, areaId, val),
                    mCarPropertyEventToService);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        } catch (ServiceSpecificException e) {
            if (mAppTargetSdk < Build.VERSION_CODES.R) {
                if (e.errorCode == VehicleHalStatusCode.STATUS_TRY_AGAIN) {
                    throw new RuntimeException(String.format("Failed to set property: 0x%x, "
                            + "areaId: 0x%x", propId, areaId), e);
                } else {
                    throw new IllegalStateException(String.format("Failed to set property: 0x%x, "
                            + "areaId: 0x%x", propId, areaId), e);
                }
            }
            handleCarServiceSpecificException(e, propId, areaId);
        }
    }

    /**
     * Modifies a property.  If the property modification doesn't occur, an error event shall be
     * generated and propagated back to the application.
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * @param prop Property ID to modify
     * @param areaId AreaId to apply the modification.
     * @param val Value to set
     */
    @AddedInOrBefore(majorVersion = 33)
    public void setBooleanProperty(int prop, int areaId, boolean val) {
        setProperty(Boolean.class, prop, areaId, val);
    }

    /**
     * Set float value of property
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * @param prop Property ID to modify
     * @param areaId AreaId to apply the modification
     * @param val Value to set
     */
    @AddedInOrBefore(majorVersion = 33)
    public void setFloatProperty(int prop, int areaId, float val) {
        setProperty(Float.class, prop, areaId, val);
    }

    /**
     * Set int value of property
     *
     * <p> This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * @param prop Property ID to modify
     * @param areaId AreaId to apply the modification
     * @param val Value to set
     */
    @AddedInOrBefore(majorVersion = 33)
    public void setIntProperty(int prop, int areaId, int val) {
        setProperty(Integer.class, prop, areaId, val);
    }

    // Handles ServiceSpecificException in CarService for R and later version.
    private void handleCarServiceSpecificException(
            ServiceSpecificException e, int propId, int areaId) {
        // We are not passing the error message down, so log it here.
        Log.w(TAG, "received ServiceSpecificException: " + e);
        int errorCode = e.errorCode;
        switch (errorCode) {
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE:
                throw new PropertyNotAvailableException(propId, areaId);
            case VehicleHalStatusCode.STATUS_TRY_AGAIN:
                throw new PropertyNotAvailableAndRetryException(propId, areaId);
            case VehicleHalStatusCode.STATUS_ACCESS_DENIED:
                throw new PropertyAccessDeniedSecurityException(propId, areaId);
            case VehicleHalStatusCode.STATUS_INTERNAL_ERROR:
                throw new CarInternalErrorException(propId, areaId);
            default:
                Log.e(TAG, "Invalid errorCode: " + errorCode + " in CarService");
        }
    }

    private void clearRequestIdToClientInfo(
            List<GetPropertyRequest> getPropertyRequests) {
        synchronized (mLock) {
            for (int i = 0; i < getPropertyRequests.size(); i++) {
                mRequestIdToClientInfo.remove(getPropertyRequests.get(i).getRequestId());
            }
        }
    }

    /** @hide */
    @Override
    @AddedInOrBefore(majorVersion = 33)
    public void onCarDisconnected() {
        synchronized (mLock) {
            mPropertyIdToCarPropertyEventCallbackController.clear();
        }
    }

    /**
     * Generate unique request ID and return to the client.
     *
     * @param propertyId Property ID
     * @param areaId area ID
     * @return GetPropertyRequest object
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    public GetPropertyRequest generateGetPropertyRequest(int propertyId, int areaId) {
        int requestIdCounter = mRequestIdCounter.getAndIncrement();
        return new GetPropertyRequest(requestIdCounter, propertyId, areaId);
    }

    private void checkGetAsyncRequirements(List<GetPropertyRequest> getPropertyRequests,
            GetPropertyCallback getPropertyCallback, long timeoutInMs) {
        Objects.requireNonNull(getPropertyRequests);
        Objects.requireNonNull(getPropertyCallback);
        if (timeoutInMs <= 0) {
            throw new IllegalArgumentException("timeoutInMs must be a positive number");
        }
    }

    private void updateRequestIdToClientInfo(
            SparseArray<GetAsyncPropertyClientInfo> currentRequestIdToClientInfo) {
        synchronized (mLock) {
            for (int i = 0; i < currentRequestIdToClientInfo.size(); i++) {
                mRequestIdToClientInfo.put(currentRequestIdToClientInfo.keyAt(i),
                        currentRequestIdToClientInfo.valueAt(i));
            }
        }
    }

    /**
     * Query a list of {@link CarPropertyValue} with property ID and area ID asynchronously.
     *
     * <p>This function would return immediately before the results are ready. For each request,
     * the corresponding result would either be delivered through one
     * {@code resultCallback.onSuccess} call if the request succeeded or through one
     * {@code errorCallback.onFailure} call if failed. It is guaranteed that the total times the
     * callback functions are called is equal to the number of requests if this function does not
     * throw an exception. It is guaranteed that none of the callback functions are called if an
     * exception is thrown. If the {@code callbackExecutor} is {@code null}, the callback will be
     * executed on the default event handler thread. If the callback is doing heavy work, it is
     * recommended that the {@code callbackExecutor} is provided.
     *
     * @param getPropertyRequests The property ID and the optional area ID for the property to get
     * @param timeoutInMs The timeout for the operation, in milliseconds
     * @param cancellationSignal A signal that could be used to cancel the on-going operation
     * @param callbackExecutor The executor to execute the callback with
     * @param getPropertyCallback The callback function to deliver the result
     * @throws SecurityException if missing permission to read the specific property
     * @throws IllegalArgumentException if the [property ID, area ID] is not supported
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void getPropertiesAsync(
            @NonNull List<GetPropertyRequest> getPropertyRequests,
            long timeoutInMs,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull GetPropertyCallback getPropertyCallback) {
        // TODO(b/238323816): implement cancellationSignal and timeoutInMs logic.
        checkGetAsyncRequirements(getPropertyRequests, getPropertyCallback, timeoutInMs);
        List<GetPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>(
                getPropertyRequests.size());
        SparseArray<GetAsyncPropertyClientInfo> currentRequestIdToClientInfo =
                new SparseArray<>();
        if (callbackExecutor == null) {
            callbackExecutor = new HandlerExecutor(getEventHandler());
        }
        for (int i = 0; i < getPropertyRequests.size(); i++) {
            GetPropertyRequest getPropertyRequest = getPropertyRequests.get(i);
            int propertyId = getPropertyRequest.getPropertyId();
            int areaId = getPropertyRequest.getAreaId();
            if (DBG) {
                Log.d(TAG, "getPropertiesAsync, propId: " + VehiclePropertyIds.toString(propertyId)
                        + ", areaId: 0x" + toHexString(areaId));
            }
            GetAsyncPropertyClientInfo getAsyncPropertyClientInfo = new GetAsyncPropertyClientInfo(
                    getPropertyRequest, callbackExecutor, getPropertyCallback);
            int requestId = getPropertyRequest.getRequestId();
            synchronized (mLock) {
                if (mRequestIdToClientInfo.contains(requestId)
                        || currentRequestIdToClientInfo.contains(requestId)) {
                    throw new IllegalArgumentException(
                            "Request ID: " + requestId + " already exists");
                }
                currentRequestIdToClientInfo.put(requestId, getAsyncPropertyClientInfo);
            }
            getPropertyServiceRequests.add(
                    new GetPropertyServiceRequest(requestId, propertyId, areaId));
        }
        updateRequestIdToClientInfo(currentRequestIdToClientInfo);
        try {
            mService.getPropertiesAsync(getPropertyServiceRequests,
                    mGetAsyncPropertyResultCallback);
        } catch (RemoteException e) {
            clearRequestIdToClientInfo(getPropertyRequests);
            handleRemoteExceptionFromCarService(e);
        } catch (IllegalArgumentException | SecurityException e) {
            clearRequestIdToClientInfo(getPropertyRequests);
            throw e;
        }
    }

    /**
     * Query a list of {@link CarPropertyValue} with property Id and area Id asynchronously.
     *
     * Same as {@link CarPropertyManager#getPropertiesAsync(List, long, CancellationSignal,
     * Executor, GetPropertyCallback)} with default timeout 10s.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void getPropertiesAsync(
            @NonNull List<GetPropertyRequest> getPropertyRequests,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull GetPropertyCallback getPropertyCallback) {
        getPropertiesAsync(getPropertyRequests, ASYNC_GET_DEFAULT_TIMEOUT_MS, cancellationSignal,
                callbackExecutor, getPropertyCallback);
    }
}
