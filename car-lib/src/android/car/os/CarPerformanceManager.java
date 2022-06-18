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

package android.car.os;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.annotation.AddedIn;
import android.car.annotation.AddedInOrBefore;
import android.car.annotation.ExperimentalFeature;
import android.car.annotation.MinimumPlatformSdkVersion;
import android.os.IBinder;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * CarPerformanceManager allows applications to tweak performance settings for their
 * processes/threads and listen for CPU available change notifications.
 *
 * <p>This feature is still under development and will not be available for user builds.
 *
 * @hide
 */
@ExperimentalFeature
@SystemApi
@AddedIn(majorVersion = 33, minorVersion = 1)
public final class CarPerformanceManager extends CarManagerBase {

    private final ICarPerformanceService mService;

    /**
     * An exception type thrown when {@link setThreadPriority} failed.
     *
     * @hide
     */
    @SystemApi
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static final class SetSchedulerFailedException extends Exception {
        SetSchedulerFailedException(Throwable cause) {
            super(cause);
        }
    }

    /** @hide */
    public CarPerformanceManager(Car car, IBinder service) {
        super(car);
        mService = ICarPerformanceService.Stub.asInterface(service);
    }

    /** @hide */
    @Override
    @AddedInOrBefore(majorVersion = 33)
    public void onCarDisconnected() {
        // nothing to do
    }

    /**
     * Listener to get CPU availability change notifications.
     *
     * <p>
     * Applications implement the listener method to perform one of the following actions:
     * <ul>
     * <li>Execute CPU intensive tasks when the CPU availability percent is above the specified
     * upper bound percent.
     * <li>Stop executing CPU intensive tasks when the CPU availability percent is below
     * the specified lower bound percent.
     * <li>Handle the CPU availability timeout.
     * </ul>
     * </p>
     *
     * @hide
     */
    public interface CpuAvailabilityChangeListener {
        /**
         * Called on one of the following events:
         * 1. When the CPU availability percent has reached or decreased below the lower bound
         *    percent specified at {@link CpuAvailabilityMonitoringConfig#getLowerBoundPercent()}.
         * 2. When the CPU availability percent has reached or increased above the upper bound
         *    percent specified at {@link CpuAvailabilityMonitoringConfig#getUpperBoundPercent()}.
         * 3. When the CPU availability monitoring has reached the timeout specified at
         *    {@link CpuAvailabilityMonitoringConfig#getTimeoutInSeconds()}.
         *
         * <p>The listener is called at the executor which is specified in
         * {@link CarPerformanceManager#addCpuAvailabilityChangeListener(Executor,
         * CpuAvailabilityMonitoringConfig, CpuAvailabilityChangeListener)}.
         *
         * @param info CPU availability information.
         */
        @AddedInOrBefore(majorVersion = 33)
        void onCpuAvailabilityChange(@NonNull CpuAvailabilityInfo info);
    }

    /**
     * Adds the {@link CpuAvailabilityChangeListener} for the calling package.
     *
     * @param config CPU availability monitoring config.
     * @param listener Listener implementing {@link CpuAvailabilityChangeListener}
     * interface.
     *
     * @throws IllegalStateException if {@code listener} is already added.
     *
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_COLLECT_CAR_CPU_INFO)
    @AddedInOrBefore(majorVersion = 33)
    public void addCpuAvailabilityChangeListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CpuAvailabilityMonitoringConfig config,
            @NonNull CpuAvailabilityChangeListener listener) {
        Objects.requireNonNull(executor, "Executor must be non-null");
        Objects.requireNonNull(config, "Config must be non-null");
        Objects.requireNonNull(listener, "Listener must be non-null");

        // TODO(b/217422127): Implement the API.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Removes the {@link CpuAvailabilityChangeListener} for the calling package.
     *
     * @param listener Listener implementing {@link CpuAvailabilityChangeListener}
     * interface.
     *
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_COLLECT_CAR_CPU_INFO)
    @AddedInOrBefore(majorVersion = 33)
    public void removeCpuAvailabilityChangeListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CpuAvailabilityChangeListener listener) {
        Objects.requireNonNull(executor, "Executor must be non-null");
        Objects.requireNonNull(listener, "Listener must be non-null");

        // TODO(b/217422127): Implement the API.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Sets the thread scheduling policy with priority for the current thread.
     *
     * @param policyWithPriority A thread scheduling policy with priority.
     * @throws IllegalArgumentException If the policy is not supported or the priority is not within
     *         {@link ThreadPolicyWithPriority#PRIORITY_MIN} and
     *         {@link ThreadPolicyWithPriority#PRIORITY_MAX}.
     * @throws SetSchedulerFailedException If failed to set the scheduling policy and priority.
     * @throws SecurityException If permission check failed.
     * @throws UnsupportedOperationException If the current android release doesn't support the API.
     *
     * @hide
     */
    @SystemApi
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @MinimumPlatformSdkVersion(majorVersion = 33, minorVersion = 1)
    @RequiresPermission(Car.PERMISSION_MANAGE_THREAD_PRIORITY)
    public void setThreadPriority(@NonNull ThreadPolicyWithPriority policyWithPriority)
            throws SetSchedulerFailedException {
        // TODO(b/156400843): Implement this.
        throw new UnsupportedOperationException("Unimplemented");
    }

    /**
     * Gets the thread scheduling policy with priority for the current thread.
     *
     * This function only works for policy {@link ThreadPolicyWithPriority#SCHED_FIFO} or
     * {@link ThreadPolicyWithPriority#SCHED_RR} because we only support adjusting thread priority
     * for these two real-time scheduling policies.
     *
     * @throws IllegalStateException if the current thread policy is not FIFO or RR or failed to
     *         get policy or priority.
     * @throws SecurityException If permission check failed.
     * @throws UnsupportedOperationException If the current android release doesn't support the API.
     *
     * @hide
     */
    @SystemApi
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @MinimumPlatformSdkVersion(majorVersion = 33, minorVersion = 1)
    @RequiresPermission(Car.PERMISSION_MANAGE_THREAD_PRIORITY)
    public @NonNull ThreadPolicyWithPriority getThreadPriority() {
        // TODO(b/156400843): Implement this.
        throw new UnsupportedOperationException("Unimplemented");
    }
}

