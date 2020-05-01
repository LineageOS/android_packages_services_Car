/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.car.testapi;

import android.annotation.Nullable;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * UserLifecycleListener that blocks until the event is received.
 *
 * <p>It should be used in cases where just one event is expected.
 */
public final class OneEventUserLifecycleListener implements UserLifecycleListener {

    private static final String TAG = OneEventUserLifecycleListener.class.getSimpleName();

    private static final long USER_LIFECYCLE_EVENT_TIMEOUT_MS = 2_000;

    private final CountDownLatch mLatch = new CountDownLatch(1);

    @Nullable
    private UserLifecycleEvent mReceivedEvent;

    private final long mTimeoutMs;

    /**
     * Constructor with default timeout of {@value #USER_LIFECYCLE_EVENT_TIMEOUT_MS}.
     */
    public OneEventUserLifecycleListener() {
        this(USER_LIFECYCLE_EVENT_TIMEOUT_MS);
    }

    /**
     * Constructor with custom timeout.
     *
     * @param timeoutMs how long to wait (in milliseconds) when calling {@link #waitForEvent()}.
     */
    public OneEventUserLifecycleListener(long timeoutMs) {
        this.mTimeoutMs = timeoutMs;
    }

    @Override
    public void onEvent(UserLifecycleEvent event) {
        this.mReceivedEvent = event;
        mLatch.countDown();
    }

    /**
     * Blocks until {@link #onEvent(UserLifecycleEvent)} is invoked.
     *
     * @throws IllegalStateException if it times out before {@code onEvent()} is called.
     * @throws InterruptedException if interrupted before {@code onEvent()} is called.
     */
    @Nullable
    public UserLifecycleEvent waitForEvent() throws InterruptedException {
        if (!mLatch.await(mTimeoutMs, TimeUnit.MILLISECONDS)) {
            String errorMessage = "onEvent() not called in " + mTimeoutMs + " seconds";
            Log.e(TAG, errorMessage);
            throw new IllegalStateException(errorMessage);
        }
        return mReceivedEvent;
    }
}
