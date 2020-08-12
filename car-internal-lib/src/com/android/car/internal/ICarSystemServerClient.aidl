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

package com.android.car.internal;

/**
 * API to communicate from CarServiceHelperService to car service.
 */
interface ICarSystemServerClient {
    /**
     * Notify of user lifecycle events.
     *
     * @param eventType - type as defined by CarUserManager.UserLifecycleEventType
     * @param timestampMs - when the event happened
     * @param fromUserId - user id of previous user when type is SWITCHING (or UserHandle.USER_NULL)
     * @param toUserId - user id of new user.
     */
    oneway void onUserLifecycleEvent(int eventType, long timestampMs, int fromUserId,
            int toUserId);

    /**
     * Notify to init boot user.
     */
    oneway void initBootUser();

    /**
     * Notify to pre-create users.
     */
    oneway void preCreateUsers();
}
