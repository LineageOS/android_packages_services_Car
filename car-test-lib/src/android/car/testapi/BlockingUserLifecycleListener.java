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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleEventType;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.os.UserHandle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * UserLifecycleListener that blocks until an event is received.
 */
public final class BlockingUserLifecycleListener implements UserLifecycleListener {

    private static final String TAG = BlockingUserLifecycleListener.class.getSimpleName();

    private static final long DEFAULT_TIMEOUT_MS = 2_000;

    private final CountDownLatch mLatch = new CountDownLatch(1);
    private final List<UserLifecycleEvent> mAllReceivedEvents = new ArrayList<>();
    private final List<UserLifecycleEvent> mExpectedEventsReceived = new ArrayList<>();

    @UserLifecycleEventType
    private final List<Integer> mExpectedEventTypes;

    @UserIdInt
    private final int mForUserId;

    private final long mTimeoutMs;

    private BlockingUserLifecycleListener(Builder builder) {
        mExpectedEventTypes = builder.mExpectedEventTypes;
        mTimeoutMs = builder.mTimeoutMs;
        mForUserId = builder.mForUserId;
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
        private List<Integer> mExpectedEventTypes = new ArrayList<>();

        @UserIdInt
        private int mForUserId = UserHandle.USER_NULL;

        /**
         * Sets the timeout.
         */
        public Builder setTimeout(long timeoutMs) {
            mTimeoutMs = timeoutMs;
            return this;
        }

        /**
         * Sets the expected type - once the given event is received, the listener will unblock.
         */
        public Builder addExpectedEvent(@UserLifecycleEventType int eventType) {
            mExpectedEventTypes.add(eventType);
            return this;
        }

        /**
         * Filters received events just for the given user.
         */
        public Builder forUser(@UserIdInt int userId) {
            mForUserId = userId;
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
        Log.d(TAG, "onEvent(): expecting=" + expectedEventsToString()
                + ", received=" + event);
        mAllReceivedEvents.add(event);

        if (expectingSpecificUser() && event.getUserId() != mForUserId) {
            Log.w(TAG, "ignoring event for different user");
            return;
        }

        Integer actualType = event.getEventType();
        boolean removed = mExpectedEventTypes.remove(actualType);
        if (removed) {
            Log.v(TAG, "event removed; still expecting for " + expectedEventsToString());
            mExpectedEventsReceived.add(event);
        } else {
            Log.v(TAG, "event not removed");
        }

        if (mExpectedEventTypes.isEmpty()) {
            Log.d(TAG, "all expected events received, counting down " + mLatch);
            mLatch.countDown();
        }
    }

    /**
     * Helper method for {@link #waitForEvents()} when caller is expecting just one event.
     */
    @Nullable
    public UserLifecycleEvent waitForEvent() throws InterruptedException {
        List<UserLifecycleEvent> receivedEvents = waitForEvents();
        UserLifecycleEvent event = receivedEvents.isEmpty() ? null : receivedEvents.get(0);
        Log.v(TAG, "waitForEvent(): returning " + event);
        return event;
    }

    /**
     * Blocks until all expected {@link #onEvent(UserLifecycleEvent)} are received.
     *
     * @throws IllegalStateException if it times out before all events are received.
     * @throws InterruptedException if interrupted before all events are received.
     */
    @NonNull
    public List<UserLifecycleEvent> waitForEvents() throws InterruptedException {
        if (!mLatch.await(mTimeoutMs, TimeUnit.MILLISECONDS)) {
            String errorMessage = "did not receive all events in " + mTimeoutMs + " seconds; "
                    + "received only " + allReceivedEventsToString() + ", still waiting for "
                    + expectedEventsToString();
            Log.e(TAG, errorMessage);
            throw new IllegalStateException(errorMessage);
        }
        Log.v(TAG, "waitForEvents(): returning " + mAllReceivedEvents);
        return getAllReceivedEvents();
    }

    /**
     * Gets a list with all received events.
     */
    @NonNull
    public List<UserLifecycleEvent> getAllReceivedEvents() {
        return mAllReceivedEvents;
    }

    /**
     * Gets a list with just the received events set by the builder.
     */
    @NonNull
    public List<UserLifecycleEvent> getExpectedEventsReceived() {
        return mExpectedEventsReceived;
    }

    @Override
    public String toString() {
        return "[" + getClass().getSimpleName() + ": "
                + "timeout=" + mTimeoutMs + "ms, "
                + (expectingSpecificUser() ? "forUser=" + mForUserId : "")
                + ",received=" + allReceivedEventsToString()
                + ", waiting=" + expectedEventsToString()
                + "]";
    }

    private String allReceivedEventsToString() {
        String receivedEvents = mAllReceivedEvents
                .stream()
                .map((e) -> CarUserManager.lifecycleEventTypeToString(e.getEventType()))
                .collect(Collectors.toList())
                .toString();
        return expectingSpecificUser()
                ? "{user=" + mForUserId + ", events=" + receivedEvents + "}"
                : receivedEvents;
    }

    private String expectedEventsToString() {
        String expectedTypes = mExpectedEventTypes
                .stream()
                .map((type) -> CarUserManager.lifecycleEventTypeToString(type))
                .collect(Collectors.toList())
                .toString();
        return expectingSpecificUser()
                ? "{user=" + mForUserId + ", types=" + expectedTypes + "}"
                : expectedTypes;
    }

    private boolean expectingSpecificUser() {
        return mForUserId != UserHandle.USER_NULL;
    }

}
