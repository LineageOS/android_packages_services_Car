/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * StaticBinderInterface provides static methods from {@code android.os.Binder} that are used
 * internally by car service or car manager.
 *
 * This interface allows faking the implementation in unit tests.
 *
 * @hide
 */
public interface StaticBinderInterface {
    /**
     * Return the ID of the process that sent you the current transaction
     * that is being processed. This PID can be used with higher-level
     * system services to determine its identity and check permissions.
     * If the current thread is not currently executing an incoming transaction,
     * then its own PID is returned.
     *
     * Warning: oneway transactions do not receive PID. Even if you expect
     * a transaction to be synchronous, a misbehaving client could send it
     * as a asynchronous call and result in a 0 PID here. Additionally, if
     * there is a race and the calling process dies, the PID may still be
     * 0 for a synchronous call.
     */
    int getCallingUid();
    /**
     * Return the Linux UID assigned to the process that sent you the
     * current transaction that is being processed. This UID can be used with
     * higher-level system services to determine its identity and check
     * permissions. If the current thread is not currently executing an
     * incoming transaction, then its own UID is returned.
     */
    int getCallingPid();
}
