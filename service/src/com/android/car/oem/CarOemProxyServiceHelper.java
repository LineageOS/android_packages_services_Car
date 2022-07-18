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
import android.car.builtin.util.Slogf;
import android.content.Context;
import android.content.res.Resources;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import com.android.car.CarLog;
import com.android.car.R;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// TODO(b/239698894):Enhance logging to keep track of timeout calls, and dump last 10 timeout calls.
// TODO(b/239607117):'Future' Object is used to wait for the result. It is usually slower. Improve
// this class to be more memory efficient using a monitor thread.
/**
 * Handles binder call to OEM service and exposed multiple APIs to call OEM Service. Also handled
 * OEM service crash.
 */
public final class CarOemProxyServiceHelper {

    private static final String TAG = CarLog.tagFor(CarOemProxyServiceHelper.class);

    private static final int EXIT_FLAG = 10;

    // TODO(b/239607518):Resize threadpool using system property or dynamically if too many calls
    // are getting timed out.
    private static final int THREAD_POOL_SIZE = 5;

    private final int mRegularCallTimeoutMs;
    private final int mCrashCallTimeoutMs;

    private final ExecutorService mThreadPool;

    public CarOemProxyServiceHelper(Context context) {
        Slogf.i(TAG, "Creating thread pool of size %d", THREAD_POOL_SIZE);
        mThreadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        Resources res = context.getResources();
        mRegularCallTimeoutMs = res
                .getInteger(R.integer.config_oemCarService_regularCall_timeout_ms);
        mCrashCallTimeoutMs = res
                .getInteger(R.integer.config_oemCarService_crashCall_timeout_ms);
    }

    /**
     * Does timed call to the OEM service and returns default value if OEM service timed out or
     * throws any Exception.
     *
     * <p>Caller would not know if the call to OEM service timed out or returned a valid value which
     * could be same as defaultValue. It is preferred way to call OEM service if the defaultValue is
     * an acceptable result.
     *
     * @param <T> Type of the result.
     * @param callable containing binder call.
     * @param defaultValue to be returned if call timeout or any other exception is thrown.
     *
     * @return Result of the binder call. Callable result can be null.
     */
    @Nullable
    public <T> T doBinderTimedCall(Callable<T> callable, T defaultValue) {
        Future<T> result = mThreadPool.submit(callable);
        try {
            return result.get(mRegularCallTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slogf.w(TAG, "Binder call threw an exception. Return default value %s", defaultValue);
            return defaultValue;
        }
    }

    /**
     * Does timed call to the OEM service and throws timeout exception.
     *
     * <p>Throws timeout exception if OEM service timed out. If OEM service throw RemoteException it
     * would crash the CarService. If OemService throws InterruptedException or ExecutionException
     * (except RemoteException), and elapsed time is less than timeout, callable would be retried;
     * if elapsed time is more than timeout then timeout exception will be thrown.
     *
     * @param <T> Type of the result.
     * @param callable containing binder call.
     * @param timeoutMs in milliseconds.
     *
     * @return result of the binder call. Callable result can be null.
     *
     * @throws TimeoutException if call timed out.
     */
    @Nullable
    public <T> T doBinderTimedCall(Callable<T> callable, long timeoutMs)
            throws TimeoutException {
        long startTime = SystemClock.uptimeMillis();
        long remainingTime = timeoutMs;
        Future<T> result;
        while (remainingTime > 0) {
            result = mThreadPool.submit(callable);
            try {
                return result.get(remainingTime, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                Slogf.w(TAG, "Binder call received InterruptedException", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RemoteException) {
                    Slogf.e(TAG,
                            "Binder call received RemoteException, calling to crash CarService");
                    crashCarService("Remote Exception");
                }
                Slogf.w(TAG, "Binder call received ExecutionException", e);
            }
            remainingTime = timeoutMs - (SystemClock.uptimeMillis() - startTime);

            if (remainingTime > 0) {
                Slogf.w(TAG, "Binder call threw exception. Call would be retried with "
                        + "remainingTime: %s", remainingTime);
            }
        }
        Slogf.w(TAG, "Binder called timeout. throwing timeout exception");
        throw new TimeoutException("Binder called timeout. Timeout: " + timeoutMs + "ms");
    }

    /**
     * Does timed call to the OEM service and crashes the OEM and Car Service if call is not served
     * within time.
     *
     * <p>If OEM service throw RemoteException, it would crash the CarService. If OemService throws
     * InterruptedException or ExecutionException (except RemoteException), and elapsed time is less
     * than mCrashTimeout, callable would be retried; if elapsed time is more than timeout then
     * crashes the OEM and Car Service.
     *
     * @param <T> Type of the result.
     * @param callable containing binder call.
     *
     * @return result of the binder call. Callable result can be null.
     */
    @Nullable
    public <T> T doBinderCallWithTimeoutCrash(Callable<T> callable) {
        long startTime = SystemClock.uptimeMillis();
        long remainingTime = mCrashCallTimeoutMs;
        Future<T> result;

        while (remainingTime > 0) {
            result = mThreadPool.submit(callable);
            try {
                return result.get(remainingTime, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                Slogf.e(TAG, "Binder call timeout, calling to crash CarService");
                crashCarService("Timeout Exception");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                Slogf.w(TAG, "Binder call received InterruptedException", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RemoteException) {
                    Slogf.e(TAG,
                            "Binder call received RemoteException, calling to crash CarService", e);
                    crashCarService("Remote Exception");
                }
                Slogf.w(TAG, "Binder call received ExecutionException", e);
            }
            remainingTime = mCrashCallTimeoutMs - (SystemClock.uptimeMillis() - startTime);
            if (remainingTime > 0) {
                Slogf.w(TAG, "Binder call threw exception. Call would be retried with "
                        + "remainingTime:%s", remainingTime);
            }
        }

        Slogf.e(TAG, "Binder called timeout. calling to crash CarService");
        crashCarService("Timeout Exception");
        throw new AssertionError("Should not return from crashCarService");
    }

    /**
     * Does one way call to OEM Service. Runnable will be queued to threadpool and not waited for
     * completion.
     *
     * <p>It is recommended to use callable with some result if waiting for call to complete is
     * required.
     */
    public void doBinderOneWayCall(Runnable runnable) {
        mThreadPool.execute(runnable);
    }

    /**
     * Crashes CarService and OEM Service.
     */
    public void crashCarService(String reason) {
        // TODO(b/239607309):Dump call stack and crash OEM service first. To crash OEM service
        // get OEM Service PID and crash the service. Follow similar dump and crash in
        // CarServiceHelperService for the requests received from CarWatchDog
        int processId = Process.myPid();
        Slogf.e(TAG, "****Crashing CarService because %s. PID: %s****", reason, processId);
        Process.killProcess(processId);
        System.exit(EXIT_FLAG);
    }
}
