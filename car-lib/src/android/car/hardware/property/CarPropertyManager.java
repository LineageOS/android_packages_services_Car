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

import static com.android.car.internal.property.CarPropertyHelper.STATUS_OK;
import static com.android.car.internal.property.CarPropertyHelper.SYNC_OP_LIMIT_TRY_AGAIN;

import static java.lang.Integer.toHexString;
import static java.util.Objects.requireNonNull;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
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
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.internal.CarPropertyEventCallbackController;
import com.android.car.internal.SingleMessageHandler;
import com.android.car.internal.os.HandlerExecutor;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.property.GetSetValueResult;
import com.android.car.internal.property.IAsyncPropertyResultCallback;
import com.android.car.internal.property.InputSanitizationUtils;
import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
    private static final int SYSTEM_ERROR_CODE_MASK = 0Xffff;
    private static final int VENDOR_ERROR_CODE_SHIFT = 16;
    private static final int SYNC_OP_RETRY_SLEEP_IN_MS = 10;
    private static final int SYNC_OP_RETRY_MAX_COUNT = 10;
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
    private final SparseArray<AsyncPropertyRequestInfo<?, ?>> mRequestIdToAsyncRequestInfo =
            new SparseArray<>();
    private final AsyncPropertyResultCallback mAsyncPropertyResultCallback =
            new AsyncPropertyResultCallback();

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
         * this callback. If multiple clients set a property for the same area ID simultaneously,
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
     * A callback {@link CarPropertyManager#getPropertiesAsync} when succeeded or failed.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public interface GetPropertyCallback {
        /**
         * Method called when {@link GetPropertyRequest} successfully gets a result.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onSuccess(@NonNull GetPropertyResult<?> getPropertyResult);

        /**
         * Method called when {@link GetPropertyRequest} returns an error.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onFailure(@NonNull PropertyAsyncError propertyAsyncError);
    }

    /**
     * A callback {@link CarPropertyManager#setPropertiesAsync} when succeeded or failed.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public interface SetPropertyCallback {
        /**
         * Method called when the {@link SetPropertyRequest} successfully set the value.
         *
         * <p>This means: the set value request is successfully sent to vehicle
         *
         * <p>and
         *
         * <p>either the current property value is already the target value, or we have received a
         * property update event indicating the value is updated to the target value.
         *
         * <p>If multiple clients set a property for the same area ID simultaneously with different
         * values, the order is undefined. One possible case is that both requests are sent to the
         * vehicle bus, which causes two property update events. As a result, the success callback
         * would be called for both clients, but in an undefined order. This means that even if
         * the success callback is called, it doesn't necessarily mean getting the property would
         * return the same value you just set. Another client might have changed the value after you
         * set it.
         *
         * <p>If only one requests is successfully processed by the vehicle bus, overwriting the
         * other request, then only one success callback would be called for one client. The other
         * client would get the failure callback with
         * {@link CarPropertyManager#STATUS_ERROR_TIMEOUT) error code.
         *
         * <p>If multiple clients set a property for the same area ID simultaneously with the same
         * value. The success callback for both clients would be called in an undefined order.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onSuccess(@NonNull SetPropertyResult setPropertyResult);

        /**
         * Method called when {@link SetPropertyRequest} returns an error.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onFailure(@NonNull PropertyAsyncError propertyAsyncError);
    }

    /**
     * An async get/set property request.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public interface AsyncPropertyRequest {
        /**
         * Returns the unique ID for this request.
         *
         * <p>Each request must have a unique request ID so the responses can be differentiated.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        int getRequestId();

        /**
         * Returns the ID for the property of this request.
         *
         * <p>The ID must be one of the {@link VehiclePropertyIds} or vendor property IDs.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        int getPropertyId();

        /**
         * Returns the area ID for the property of this request.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        int getAreaId();
    }

    /**
     * A request for {@link CarPropertyManager#getPropertiesAsync(List, long, CancellationSignal,
     * Executor, GetPropertyCallback)}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class GetPropertyRequest implements AsyncPropertyRequest {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;

        /**
         * @see AsyncPropertyRequest#getRequestId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        /**
         * @see AsyncPropertyRequest#getPropertyId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        /**
         * @see AsyncPropertyRequest#getAreaId
         */
        @Override
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
     * A request for {@link CarPropertyManager#setPropertiesAsync(List, long, CancellationSignal,
     * Executor, SetPropertyCallback)}.
     *
     * @param <T> The type for the property value, must be one of Object, Boolean, Float, Integer,
     *      Long, Float[], Integer[], Long[], String, byte[], Object[].
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class SetPropertyRequest<T> implements AsyncPropertyRequest {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private float mUpdateRateHz = 0.f;

        /**
         * The value to set.
         */
        private final T mValue;

        /**
         * @see AsyncPropertyRequest#getRequestId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        /**
         * @see AsyncPropertyRequest#getPropertyId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        /**
         * @see AsyncPropertyRequest#getAreaId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        /**
         * Get the property value to set.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public T getValue() {
            return mValue;
        }

        /**
         * Sets the update rate for listening for property updates for continuous property.
         *
         * <p>For continuous property, if the property is set to a different value, the success
         * callback will be called when a property update event for the new value arrived. This
         * option controls how frequent the property update event should be reported. This is
         * similar to {@code updateRateHz} in {@link CarPropertyManager#registerCallback}.
         *
         * <p>This is ignored for non-continuous properties.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public void setUpdateRateHz(float updateRateHz) {
            mUpdateRateHz = updateRateHz;
        }

        /**
         * Gets the update rate for listening for property updates.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public float getUpdateRateHz() {
            return mUpdateRateHz;
        }

        /**
         * Internal use only. Users should use {@link generateSetPropertyRequest} instead.
         */
        private SetPropertyRequest(int requestId, int propertyId, int areaId, T value) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mValue = value;
        }
    }

    /**
     * An error result for {@link GetPropertyCallback} or {@link SetPropertyCallback}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class PropertyAsyncError {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private final @CarPropertyAsyncErrorCode int mErrorCode;
        private final int mVendorErrorCode;

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

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public @CarPropertyAsyncErrorCode int getErrorCode() {
            return mErrorCode;
        }

        /**
         * Gets the vendor error codes to allow for more detailed error codes.
         *
         * @return Vendor error code if it is set, otherwise 0. A vendor error code will have a
         * range from 0x0000 to 0xffff.
         *
         * @hide
         */
        @SystemApi
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
        public int getVendorErrorCode() {
            return mVendorErrorCode;
        }

        /**
         * Creates a new error result for async property request.
         *
         * @param requestId the request ID.
         * @param propertyId the property ID in the request.
         * @param areaId the area ID for the property in the request.
         * @param errorCode the code indicating the error.
         */
        PropertyAsyncError(int requestId, int propertyId, int areaId, int errorCode) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mErrorCode = errorCode & SYSTEM_ERROR_CODE_MASK;
            mVendorErrorCode = errorCode >>> VENDOR_ERROR_CODE_SHIFT;
        }
    }

    /**
     * A successful result for {@link GetPropertyCallback}.
     *
     * @param <T> The type for the property value, must be one of Object, Boolean, Float, Integer,
     *      Long, Float[], Integer[], Long[], String, byte[], Object[].
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class GetPropertyResult<T> {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private final long mTimestampNanos;
        private final T mValue;

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

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public T getValue() {
            return mValue;
        }

        /**
         * Returns the timestamp in nanoseconds at which the value for the vehicle property
         * happened. For a given vehicle property, each new timestamp should be monotonically
         * increasing using the same time base as {@link SystemClock#elapsedRealtimeNanos()}.
         *
         * <p>NOTE: Timestamp should be synchronized with other signals from the platform (e.g.
         * {@link Location} and {@link SensorEvent} instances). Ideally, timestamp synchronization
         * error should be below 1 millisecond.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public long getTimestampNanos() {
            return mTimestampNanos;
        }

        /**
         * Creates a new value result for async GetProperty request.
         *
         * @param requestId the request ID.
         * @param propertyId the property ID in the request.
         * @param areaId the area ID for the property in the request.
         * @param timestampNanos the timestamp in nanoseconds when this property is updated.
         * @param value the property's value.
         */
        GetPropertyResult(int requestId, int propertyId, int areaId, long timestampNanos,
                 @NonNull T value) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mTimestampNanos = timestampNanos;
            mValue = value;
        }
    }

    /**
     * A successful result for {@link SetPropertyCallback}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class SetPropertyResult {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private final long mUpdateTimestampNanos;

        /**
         * Gets the ID for the request this result is for.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        /**
         * Gets the property ID this result is for.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        /**
         * Gets the area ID this result is for.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        /**
         * Gets the timestamp in nanoseconds at which the property was updated to the desired value.
         *
         * <p>The timestamp will use the same time base as
         * {@link SystemClock#elapsedRealtimeNanos()}.
         *
         * <p>NOTE: Timestamp should be synchronized with other signals from the platform (e.g.
         * {@link Location} and {@link SensorEvent} instances). Ideally, timestamp synchronization
         * error should be below 1 millisecond.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public long getUpdateTimestampNanos() {
            return mUpdateTimestampNanos;
        }

        SetPropertyResult(int requestId, int propertyId, int areaId, int updateTimestampNanos) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mUpdateTimestampNanos = updateTimestampNanos;
        }
    }

    /**
     * A class for delivering {@link GetPropertyCallback} client callback when
     * {@link IAsyncPropertyResultCallback} returns a result.
     */
    private class AsyncPropertyResultCallback extends IAsyncPropertyResultCallback.Stub {

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onGetValueResults(List<GetSetValueResult> getValueResults) {
            for (int i = 0; i < getValueResults.size(); i++) {
                GetSetValueResult getValueResult = getValueResults.get(i);
                int requestId = getValueResult.getRequestId();
                AsyncPropertyRequestInfo<GetPropertyRequest, GetPropertyCallback> requestInfo;
                synchronized (mLock) {
                    requestInfo =
                            (AsyncPropertyRequestInfo<GetPropertyRequest, GetPropertyCallback>)
                            mRequestIdToAsyncRequestInfo.get(requestId);
                    mRequestIdToAsyncRequestInfo.remove(requestId);
                }
                if (requestInfo == null) {
                    Log.w(TAG, "onGetValueResult: Request ID: " + requestId
                            + " might have been completed, cancelled or an exception might have "
                            + "been thrown");
                    continue;
                }
                Executor callbackExecutor = requestInfo.getCallbackExecutor();
                GetPropertyCallback getPropertyCallback = requestInfo.getCallback();
                int errorCode = getValueResult.getErrorCode();
                @CarPropertyAsyncErrorCode
                int asyncErrorCode = errorCode & SYSTEM_ERROR_CODE_MASK;
                if (asyncErrorCode == STATUS_OK) {
                    CarPropertyValue<?> carPropertyValue = getValueResult.getCarPropertyValue();
                    int propertyId = carPropertyValue.getPropertyId();
                    int areaId = carPropertyValue.getAreaId();

                    long timestampNanos = carPropertyValue.getTimestamp();
                    callbackExecutor.execute(() -> getPropertyCallback.onSuccess(
                            new GetPropertyResult(requestId, propertyId, areaId,
                                    timestampNanos, carPropertyValue.getValue())));
                } else {
                    // We are not receiving property Id and areaId from the result, so we use
                    // the ones from the request.
                    int propertyId = requestInfo.getRequest()
                            .getPropertyId();
                    int areaId = requestInfo.getRequest().getAreaId();
                    callbackExecutor.execute(() -> getPropertyCallback.onFailure(
                            new PropertyAsyncError(requestId, propertyId, areaId, errorCode)));
                }
            }
        }

        @Override
        public void onSetValueResults(List<GetSetValueResult> getValueResults) {
            // TODO(b/264719384): Implement this.
        }
    }

    /**
     * A class to store async get/set property request info.
     */
    private static final class AsyncPropertyRequestInfo<RequestType, CallbackType> {
        private final RequestType mRequest;
        private final Executor mCallbackExecutor;
        private final CallbackType mCallback;

        public RequestType getRequest() {
            return mRequest;
        }

        public Executor getCallbackExecutor() {
            return mCallbackExecutor;
        }

        public CallbackType getCallback() {
            return mCallback;
        }

        private AsyncPropertyRequestInfo(RequestType request, Executor callbackExecutor,
                CallbackType callback) {
            mRequest = request;
            mCallbackExecutor = callbackExecutor;
            mCallback = callback;
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
     *
     * <p>Rate could be one of the following:
     * <ul>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_ONCHANGE}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_NORMAL}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_UI}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_FAST}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_FASTEST}</li>
     * </ul>
     *
     * <p>
     * <b>Note:</b>Rate has no effect if the property has one of the following change modes:
     * <ul>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_STATIC}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE}</li>
     * </ul>
     *
     * <p>
     * <b>Note:</b>If listener registers a callback for updates for a property for the first time,
     * it will receive the property's current value via a change event upon registration if the
     * property is currently available for reading.
     *
     * <p>For properties that might be unavailable for reading because their power state is off,
     * property change events containing the property's initial value will be generated once their
     * power state is on.
     *
     * <p>If updateRateHz is higher than {@link CarPropertyConfig#getMaxSampleRate()}, it will be
     * registered with max sample updateRateHz.
     *
     * <p>If updateRateHz is lower than {@link CarPropertyConfig#getMinSampleRate()}, it will be
     * registered with min sample updateRateHz.
     *
     * <p>
     * <b>Note:</b>A property change event will only happen when the property is available. Caller
     * must never depend on the change event to check property's availability. For properties that
     * might be unavailable because they depend on certain power state, caller should subscribe
     * to the power state property (e.g. HVAC_POWER_ON for hvac dependant properties) to decide
     * this property's availability.
     *
     * @param carPropertyEventCallback CarPropertyEventCallback to be registered.
     * @param propertyId               PropertyId to subscribe
     * @param updateRateHz             how fast the property events are delivered in Hz.
     * @return {@code true} if the listener is successfully registered.
     * @throws SecurityException if missing the appropriate permission.
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean registerCallback(@NonNull CarPropertyEventCallback carPropertyEventCallback,
            int propertyId, @FloatRange(from = 0.0, to = 100.0) float updateRateHz) {
        requireNonNull(carPropertyEventCallback);
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        if (carPropertyConfig == null) {
            Log.e(TAG, "registerListener:  propId is not in carPropertyConfig list:  "
                    + VehiclePropertyIds.toString(propertyId));
            return false;
        }

        float sanitizedUpdateRateHz = InputSanitizationUtils.sanitizeUpdateRateHz(carPropertyConfig,
                updateRateHz);

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
        return carPropertyEventCallbackController.add(carPropertyEventCallback,
                sanitizedUpdateRateHz);
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
        if (!CarPropertyHelper.isSupported(propertyId)) {
            Log.e(TAG, "unregisterCallback: propertyId: "
                    + VehiclePropertyIds.toString(propertyId) + " is not supported");
            return;
        }
        CarPropertyEventCallbackController carPropertyEventCallbackController;
        synchronized (mLock) {
            carPropertyEventCallbackController =
                    mPropertyIdToCarPropertyEventCallbackController.get(propertyId);
        }
        if (carPropertyEventCallbackController == null) {
            return;
        }
        synchronized (mLock) {
            boolean allCallbacksRemoved = carPropertyEventCallbackController.remove(
                    carPropertyEventCallback);
            if (allCallbacksRemoved) {
                mPropertyIdToCarPropertyEventCallbackController.remove(propertyId);
            }
        }
    }

    /**
     * @return List of properties supported by this car that the application may access
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
     * Checks the given property IDs and returns a list of property configs supported by the car.
     *
     * If some of the properties in the given ID list are not supported, they will not be returned.
     *
     * @param propertyIds property ID list
     * @return List of property configs.
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public List<CarPropertyConfig> getPropertyList(@NonNull ArraySet<Integer> propertyIds) {
        List<Integer> filteredPropertyIds = new ArrayList<>();
        for (int propId : propertyIds) {
            if (!CarPropertyHelper.isSupported(propId)) {
                continue;
            }
            filteredPropertyIds.add(propId);
        }
        int[] filteredPropertyIdsArray = new int[filteredPropertyIds.size()];
        for (int i = 0; i < filteredPropertyIds.size(); i++) {
            filteredPropertyIdsArray[i] = filteredPropertyIds.get(i);
        }
        try {
            return mService.getPropertyConfigList(filteredPropertyIdsArray);
        } catch (RemoteException e) {
            Log.e(TAG, "getPropertyList exception ", e);
            return handleRemoteExceptionFromCarService(e, new ArrayList<>());
        }
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
        if (!CarPropertyHelper.isSupported(propId)) {
            return null;
        }
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
        if (!CarPropertyHelper.isSupported(propId)) {
            return false;
        }

        try {
            CarPropertyValue propValue = runSyncOperation(() -> {
                return mService.getProperty(propId, area);
            });
            return propValue != null;
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
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions if the call
     * fails.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [prop, area] is not supported.
     * </ul>
     *
     * <p>For pre-R client, the returned value is {@code false} if the property is temporarily not
     * available.
     *
     * @param prop Property ID to get
     * @param area Area of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [prop, area] is not supported.
     *
     * @return value of a bool property, or {@code false} for pre-R client if the property is
     *         temporarily not available.
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean getBooleanProperty(int prop, int area) {
        CarPropertyValue<Boolean> carProp = getProperty(Boolean.class, prop, area);
        return carProp != null ? carProp.getValue() : false;
    }

    /**
     * Returns value of a float property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param prop Property ID to get
     * @param area Area of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [prop, area] is not supported.
     *
     * @return value of a float property, or 0 if client is pre-R and the property is temporarily
     *         not available.
     */
    @AddedInOrBefore(majorVersion = 33)
    public float getFloatProperty(int prop, int area) {
        CarPropertyValue<Float> carProp = getProperty(Float.class, prop, area);
        return carProp != null ? carProp.getValue() : 0f;
    }

    /**
     * Returns value of an integer property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called form a
     * non-main thread.
     *
     * <p>This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param prop Property ID to get
     * @param area Zone of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException} when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException} when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [prop, area] is not supported.
     *
     * @return value of a integer property, or 0 if client is pre-R and the property is temporarily
     *         not available.
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getIntProperty(int prop, int area) {
        CarPropertyValue<Integer> carProp = getProperty(Integer.class, prop, area);
        return carProp != null ? carProp.getValue() : 0;
    }

    /**
     * Returns value of an integer array property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param prop Property ID to get
     * @param area Zone of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException} when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException} when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException} when the [prop, area] is not supported.
     *
     * @return value of a integer array property, or an empty integer array if client is pre-R and
     *         the property is temporarily not available.
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public int[] getIntArrayProperty(int prop, int area) {
        CarPropertyValue<Integer[]> carProp = getProperty(Integer[].class, prop, area);
        return carProp != null ? toIntArray(carProp.getValue()) : new int[0];
    }

    private static int[] toIntArray(Integer[] input) {
        int len = input.length;
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
            arr[i] = input[i];
        }
        return arr;
    }

    private static <V> V runSyncOperation(Callable<V> c)
            throws RemoteException, ServiceSpecificException {
        int retryCount = 0;
        while (retryCount < SYNC_OP_RETRY_MAX_COUNT) {
            retryCount++;
            try {
                return c.call();
            } catch (ServiceSpecificException e) {
                if (e.errorCode != SYNC_OP_LIMIT_TRY_AGAIN) {
                    throw e;
                }
                // If car service don't have enough binder thread to handle this request. Sleep for
                // 10ms and try again.
                Log.d(TAG, "too many sync request, sleeping for " + SYNC_OP_RETRY_SLEEP_IN_MS
                        + " ms before retry");
                SystemClock.sleep(SYNC_OP_RETRY_SLEEP_IN_MS);
                continue;
            } catch (RuntimeException | RemoteException e) {
                throw e;
            } catch (Exception e) {
                Log.e(TAG, "catching unexpected exception for getProperty/setProperty", e);
                return null;
            }
        }
        throw new ServiceSpecificException(VehicleHalStatusCode.STATUS_INTERNAL_ERROR,
                "failed to call car service sync operations after " + retryCount + " retries");
    }

    /**
     * Return CarPropertyValue
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     *
     * <p>For R or later version client, the returned value will never be null.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [propId, areaId] is not supported.
     * </ul>
     *
     * <p>For pre-R client, the returned value might be null if the property is temporarily not
     * available. The client should try again in this case.
     *
     * @param clazz The class object for the CarPropertyValue
     * @param propId Property ID to get
     * @param areaId Zone of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [propId, areaId] is not supported.
     *
     * @return {@link CarPropertyValue}.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public <E> CarPropertyValue<E> getProperty(@NonNull Class<E> clazz, int propId, int areaId) {
        CarPropertyValue<E> carPropertyValue = getProperty(propId, areaId);
        if (carPropertyValue == null) {
            return null;
        }
        Class<?> actualClass = carPropertyValue.getValue().getClass();
        if (actualClass != clazz) {
            throw new IllegalArgumentException(
                    "Invalid property type. " + "Expected: " + clazz + ", but was: "
                            + actualClass);
        }
        return carPropertyValue;
    }

    /**
     * Query CarPropertyValue with property id and areaId.
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     *
     * <p>For R or later version client, the returned value will never be null.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [propId, areaId] is not supported.
     * </ul>
     *
     * <p>For pre-R client, the returned value might be null if the property is temporarily not
     * available. The client should try again in this case.
     *
     * @param propId Property ID
     * @param areaId areaId
     * @param <E> Value type of the property
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars.
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property.
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful.
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [propId, areaId] is not supported.
     *
     * @return {@link CarPropertyValue}
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public <E> CarPropertyValue<E> getProperty(int propId, int areaId) {
        if (DBG) {
            Log.d(TAG, "getProperty, propId: " + VehiclePropertyIds.toString(propId)
                    + ", areaId: 0x" + toHexString(areaId));
        }

        assertPropertyIdIsSupported(propId);

        try {
            return (CarPropertyValue<E>) (runSyncOperation(() -> {
                return mService.getProperty(propId, areaId);
            }));
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        } catch (ServiceSpecificException e) {
            if (mAppTargetSdk < Build.VERSION_CODES.R) {
                if (e.errorCode == VehicleHalStatusCode.STATUS_TRY_AGAIN) {
                    return null;
                } else {
                    throw new IllegalStateException(String.format(
                            "Failed to get property: 0x%x, "
                            + "areaId: 0x%x", propId, areaId), e);
                }
            }
            handleCarServiceSpecificException(e, propId, areaId);

            // Never reaches here.
            return null;
        }
    }

    /**
     * Set value of car property by areaId.
     *
     * <p>If multiple clients set a property for the same area ID simultaneously, which one takes
     * precedence is undefined. Typically, the last set operation (in the order that they are issued
     * to the car's ECU) overrides the previous set operations.
     *
     * <p>This method may take couple seconds to complete, so it needs to be called form a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link RuntimeException} when the property is temporarily not available.
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [propId, areaId] is not supported.
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
     * @throws CarInternalErrorException when there is an unexpected error detected in cars.
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the property.
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily not available
     * and likely that retrying will be successful.
     * @throws IllegalArgumentException when the [propId, areaId] is not supported.
     */
    @AddedInOrBefore(majorVersion = 33)
    public <E> void setProperty(@NonNull Class<E> clazz, int propId, int areaId, @NonNull E val) {
        if (DBG) {
            Log.d(TAG, "setProperty, propId: 0x" + toHexString(propId)
                    + ", areaId: 0x" + toHexString(areaId) + ", class: " + clazz + ", val: " + val);
        }

        assertPropertyIdIsSupported(propId);

        try {
            runSyncOperation(() -> {
                mService.setProperty(new CarPropertyValue<>(propId, areaId, val),
                        mCarPropertyEventToService);
                return null;
            });
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
            return;
        } catch (ServiceSpecificException e) {
            if (mAppTargetSdk < Build.VERSION_CODES.R) {
                if (e.errorCode == VehicleHalStatusCode.STATUS_TRY_AGAIN) {
                    throw new RuntimeException(String.format("Failed to set property: 0x%x, "
                            + "areaId: 0x%x", propId, areaId), e);
                } else {
                    throw new IllegalStateException(String.format(
                            "Failed to set property: 0x%x, " + "areaId: 0x%x", propId, areaId),
                            e);
                }
            }
            handleCarServiceSpecificException(e, propId, areaId);
            return;
        }
    }

    /**
     * Modifies a property.  If the property modification doesn't occur, an error event shall be
     * generated and propagated back to the application.
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
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
     * <p>This method may take couple seconds to complete, so it needs to be called from a
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
     * <p>This method may take couple seconds to complete, so it needs to be called from a
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

    /**
     *  Handles ServiceSpecificException in CarService for R and later version.
     */
    private void handleCarServiceSpecificException(
            ServiceSpecificException e, int propId, int areaId) {
        // We are not passing the error message down, so log it here.
        Log.w(TAG, "received ServiceSpecificException: " + e);
        int errorCode = e.errorCode & SYSTEM_ERROR_CODE_MASK;
        int vendorErrorCode = e.errorCode >>> VENDOR_ERROR_CODE_SHIFT;

        switch (errorCode) {
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE:
                throw new PropertyNotAvailableException(propId, areaId, vendorErrorCode);
            case VehicleHalStatusCode.STATUS_TRY_AGAIN:
                // Vendor error code is ignored for STATUS_TRY_AGAIN error
                throw new PropertyNotAvailableAndRetryException(propId, areaId);
            case VehicleHalStatusCode.STATUS_ACCESS_DENIED:
                // Vendor error code is ignored for STATUS_ACCESS_DENIED error
                throw new PropertyAccessDeniedSecurityException(propId, areaId);
            case VehicleHalStatusCode.STATUS_INTERNAL_ERROR:
                throw new CarInternalErrorException(propId, areaId, vendorErrorCode);
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY:
                throw new PropertyNotAvailableException(propId, areaId,
                        getPropertyNotAvailableErrorCodeFromStatusCode(errorCode), vendorErrorCode);
            default:
                Log.e(TAG, "Invalid errorCode: " + errorCode + " in CarService");
                throw new CarInternalErrorException(propId, areaId);
        }
    }

    /**
     * Convert {@link VehicleHalStatusCode} into public {@link PropertyNotAvailableErrorCode}
     * equivalents.
     *
     * @throws IllegalArgumentException if an invalid status code is passed in.
     * @hide
     */
    private static int getPropertyNotAvailableErrorCodeFromStatusCode(int statusCode) {
        switch (statusCode) {
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_DISABLED;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_LOW;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_HIGH;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_POOR_VISIBILITY;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_SAFETY;
            default:
                throw new IllegalArgumentException("Invalid status code: " + statusCode);
        }
    }

    private void clearRequestIdToAsyncRequestInfo(
            List<? extends AsyncPropertyRequest> asyncPropertyRequests) {
        synchronized (mLock) {
            for (int i = 0; i < asyncPropertyRequests.size(); i++) {
                mRequestIdToAsyncRequestInfo.remove(asyncPropertyRequests.get(i).getRequestId());
            }
        }
    }

    /**
     * Set an onCancelListener for the cancellation signal.
     *
     * <p>When the signal is cancelled, car service will remove the stored state for the specified
     * pending request IDs and ignore all the future results.
     */
    private void setOnCancelListener(CancellationSignal cancellationSignal,
            List<Integer> requestIds) {
        cancellationSignal.setOnCancelListener(() -> {
            int[] requestIdsArray = new int[requestIds.size()];
            synchronized (mLock) {
                for (int i = 0; i < requestIds.size(); i++) {
                    int requestId = requestIds.get(i);
                    requestIdsArray[i] = requestId;
                    mRequestIdToAsyncRequestInfo.remove(requestId);
                }
            }
            try {
                mService.cancelRequests(requestIdsArray);
            } catch (RemoteException e) {
                handleRemoteExceptionFromCarService(e);
            }
        });
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
     * Generate unique get request ID and return to the client.
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

    /**
     * Generate unique set request ID and return to the client.
     *
     * @param <T> The type for the property value, must be one of Object, Boolean, Float, Integer,
     *      Long, Float[], Integer[], Long[], String, byte[], Object[].
     * @param propertyId Property ID
     * @param areaId area ID
     * @param value the value to set.
     * @return SetPropertyRequest object
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    public <T> SetPropertyRequest<T> generateSetPropertyRequest(int propertyId, int areaId,
            @NonNull T value) {
        requireNonNull(value);
        int requestIdCounter = mRequestIdCounter.getAndIncrement();
        return new SetPropertyRequest(requestIdCounter, propertyId, areaId, value);
    }


    private void checkAsyncArguments(Object requests, Object callback, long timeoutInMs) {
        requireNonNull(requests);
        requireNonNull(callback);
        if (timeoutInMs <= 0) {
            throw new IllegalArgumentException("timeoutInMs must be a positive number");
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
     * <p>If the operation is cancelled, it is guaranteed that no more callbacks will be called.
     *
     * <p>For one request, if the property's status is not available,
     * {@code errorCallback.onFailure} will be called once with {@link STATUS_ERROR_NOT_AVAILABLE}.
     *
     * <p>For one request, if the property's status is error,
     * {@code errorCallback.onFailure} will be called once with {@link STATUS_ERROR_INTERNAL_ERROR}.
     *
     * @param getPropertyRequests The property ID and the optional area ID for the property to get
     * @param timeoutInMs The timeout for the operation, in milliseconds
     * @param cancellationSignal A signal that could be used to cancel the on-going operation
     * @param callbackExecutor The executor to execute the callback with
     * @param getPropertyCallback The callback function to deliver the result
     * @throws SecurityException if missing permission to read one of the specific properties.
     * @throws IllegalArgumentException if one of the properties to read is not supported.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void getPropertiesAsync(
            @NonNull List<GetPropertyRequest> getPropertyRequests,
            long timeoutInMs,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull GetPropertyCallback getPropertyCallback) {
        checkAsyncArguments(getPropertyRequests, getPropertyCallback, timeoutInMs);
        if (callbackExecutor == null) {
            callbackExecutor = new HandlerExecutor(getEventHandler());
        }

        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>(
                getPropertyRequests.size());
        for (int i = 0; i < getPropertyRequests.size(); i++) {
            GetPropertyRequest getPropertyRequest = getPropertyRequests.get(i);
            int propertyId = getPropertyRequest.getPropertyId();
            int areaId = getPropertyRequest.getAreaId();
            if (DBG) {
                Log.d(TAG, "getPropertiesAsync, propId: " + VehiclePropertyIds.toString(propertyId)
                        + ", areaId: 0x" + toHexString(areaId));
            }
            assertPropertyIdIsSupported(propertyId);

            getPropertyServiceRequests.add(new AsyncPropertyServiceRequest(
                    getPropertyRequest.getRequestId(), propertyId, areaId));
        }

        List<Integer> requestIds = storePendingRequestInfo(getPropertyRequests, callbackExecutor,
                getPropertyCallback);

        try {
            mService.getPropertiesAsync(getPropertyServiceRequests, mAsyncPropertyResultCallback,
                    timeoutInMs);
        } catch (RemoteException e) {
            clearRequestIdToAsyncRequestInfo(getPropertyRequests);
            handleRemoteExceptionFromCarService(e);
        } catch (IllegalArgumentException | SecurityException e) {
            clearRequestIdToAsyncRequestInfo(getPropertyRequests);
            throw e;
        }
        if (cancellationSignal != null) {
            setOnCancelListener(cancellationSignal, requestIds);
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

    /**
     * Sets a list of car property values asynchronously.
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
     * <p>If the operation is cancelled, it is guaranteed that no more callbacks will be called.
     *
     * <p>If multiple clients set a property for the same area ID simultaneously, which one takes
     * precedence is undefined. Typically, the last set operation (in the order that they are issued
     * to the car's ECU) overrides the previous set operations.
     *
     * @param setPropertyRequests The property values to set.
     * @param timeoutInMs The timeout for the operation, in milliseconds.
     * @param cancellationSignal A signal that could be used to cancel the on-going operation.
     * @param callbackExecutor The executor to execute the callback with.
     * @param setPropertyCallback The callback function to deliver the result.
     * @throws SecurityException if missing permission to write one of the specific properties.
     * @throws IllegalArgumentException if one of the properties to set is not supported.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void setPropertiesAsync(
            @NonNull List<SetPropertyRequest<?>> setPropertyRequests,
            long timeoutInMs,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull SetPropertyCallback setPropertyCallback) {
        checkAsyncArguments(setPropertyRequests, setPropertyCallback, timeoutInMs);
        if (callbackExecutor == null) {
            callbackExecutor = new HandlerExecutor(getEventHandler());
        }

        List<AsyncPropertyServiceRequest> setPropertyServiceRequests = new ArrayList<>(
                setPropertyRequests.size());
        for (int i = 0; i < setPropertyRequests.size(); i++) {
            SetPropertyRequest setPropertyRequest = setPropertyRequests.get(i);
            int propertyId = setPropertyRequest.getPropertyId();
            int areaId = setPropertyRequest.getAreaId();
            requireNonNull(setPropertyRequest.getValue());
            if (DBG) {
                Log.d(TAG, "setPropertiesAsync, propId: " + VehiclePropertyIds.toString(propertyId)
                        + ", areaId: 0x" + toHexString(areaId));
            }
            assertPropertyIdIsSupported(propertyId);

            setPropertyServiceRequests.add(new AsyncPropertyServiceRequest(
                    setPropertyRequest.getRequestId(), propertyId, areaId,
                    new CarPropertyValue(propertyId, areaId, setPropertyRequest.getValue())));
        }

        List<Integer> requestIds = storePendingRequestInfo(setPropertyRequests, callbackExecutor,
                setPropertyCallback);

        try {
            mService.setPropertiesAsync(setPropertyServiceRequests, mAsyncPropertyResultCallback,
                    timeoutInMs);
        } catch (RemoteException e) {
            clearRequestIdToAsyncRequestInfo(setPropertyRequests);
            handleRemoteExceptionFromCarService(e);
        } catch (IllegalArgumentException | SecurityException e) {
            clearRequestIdToAsyncRequestInfo(setPropertyRequests);
            throw e;
        }
        if (cancellationSignal != null) {
            setOnCancelListener(cancellationSignal, requestIds);
        }
    }

    /**
     * Sets a list of car property values asynchronously.
     *
     * Same as {@link CarPropertyManager#setPropertiesAsync(List, long, CancellationSignal,
     * Executor, SetPropertyCallback)} with default timeout 10s.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void setPropertiesAsync(
            @NonNull List<SetPropertyRequest<?>> setPropertyRequests,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull SetPropertyCallback setPropertyCallback) {
        setPropertiesAsync(setPropertyRequests, ASYNC_GET_DEFAULT_TIMEOUT_MS, cancellationSignal,
                callbackExecutor, setPropertyCallback);
    }

    private void assertPropertyIdIsSupported(int propId) {
        if (!CarPropertyHelper.isSupported(propId)) {
            throw new IllegalArgumentException("The property: "
                    + VehiclePropertyIds.toString(propId) + " is unsupported");
        }
    }

    private <RequestType extends AsyncPropertyRequest, CallbackType> List<Integer>
            storePendingRequestInfo(
                    List<RequestType> requests, Executor callbackExecutor, CallbackType callback) {
        List<Integer> requestIds = new ArrayList<>();
        SparseArray<AsyncPropertyRequestInfo<?, ?>> requestInfoToAdd = new SparseArray<>();
        synchronized (mLock) {
            for (int i = 0; i < requests.size(); i++) {
                RequestType request = requests.get(i);
                AsyncPropertyRequestInfo<RequestType, CallbackType> requestInfo =
                        new AsyncPropertyRequestInfo(request, callbackExecutor, callback);
                int requestId = request.getRequestId();
                requestIds.add(requestId);
                if (mRequestIdToAsyncRequestInfo.contains(requestId)
                        || requestInfoToAdd.contains(requestId)) {
                    throw new IllegalArgumentException(
                            "Request ID: " + requestId + " already exists");
                }
                requestInfoToAdd.put(requestId, requestInfo);
            }
            for (int i = 0; i < requestInfoToAdd.size(); i++) {
                mRequestIdToAsyncRequestInfo.put(requestInfoToAdd.keyAt(i),
                        requestInfoToAdd.valueAt(i));
            }
        }
        return requestIds;
    }
}
