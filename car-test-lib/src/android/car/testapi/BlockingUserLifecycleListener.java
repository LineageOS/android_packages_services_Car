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

import static android.car.user.CarUserManager.INVALID_USER_LIFECYCLE_EVENT_TYPE;
import static android.car.user.CarUserManager.lifecycleEventTypeToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleEventType;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * UserLifecycleListener that blocks until an event is received.
 */
public final class BlockingUserLifecycleListener implements UserLifecycleListener {

    private static final String TAG = BlockingUserLifecycleListener.class.getSimpleName();

    private static final long DEFAULT_TIMEOUT_MS = 2_000;

    private final CountDownLatch mLatch = new CountDownLatch(1);

    @Nullable
    private UserLifecycleEvent mReceivedEvent;

    @UserLifecycleEventType
    private final int mExpectedEventType;

    private final long mTimeoutMs;

    private BlockingUserLifecycleListener(Builder builder) {
        mExpectedEventType = builder.mExpectedEventType;
        mTimeoutMs = builder.mTimeoutMs;
    }

    /**
     * Builds a new instance with default timeout of {@value #DEFAULT_TIMEOUT_MS} and
     * which waits for one - and any one - event.
     */
    @NonNull
    public static BlockingUserLifecycleListener newDefaultListener() {
        return new Builder().build();
    }

    /**
     * Builder for a customized {@link BlockingUserLifecycleListener} instance.
     */
    public static final class Builder {
        private long mTimeoutMs = DEFAULT_TIMEOUT_MS;

        @UserLifecycleEventType
        private int mExpectedEventType = INVALID_USER_LIFECYCLE_EVENT_TYPE;

        /**
         * Sets the timeout.
         */
        public Builder setTimeout(long timeoutMs) {
            mTimeoutMs = timeoutMs;
            return this;
        }

        // TODO: add support to multiple events
        /**
         * Sets the expected type - once the given event is received, the listener will unblock.
         */
        public Builder addExpectedEvent(@UserLifecycleEventType int eventType) {
            mExpectedEventType = eventType;
            return this;
        }

        /**
         * Builds a new instance.
         */
        @NonNull
        public BlockingUserLifecycleListener build() {
            return new BlockingUserLifecycleListener(Builder.this);
        }
    }

    @Override
    public void onEvent(UserLifecycleEvent event) {
        if (mExpectedEventType != INVALID_USER_LIFECYCLE_EVENT_TYPE) {
            int actualEventType = event.getEventType();
            if (actualEventType != mExpectedEventType) {
                Log.d(TAG, "onEvent(): ignoring " + event + " as it's expecting "
                        + lifecycleEventTypeToString(mExpectedEventType));
                return;
            }
        }
        this.mReceivedEvent = event;
        mLatch.countDown();
    }

    /**
     * Blocks until {@link #onEvent(UserLifecycleEvent)} is invoked with the proper event.
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
