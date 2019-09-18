/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.trust

/**
 * View model representing a connected device.
 *
 * @param deviceId Id of the connected device.
 * @param deviceName Name of the connected device. [null] if not known.
 * @param belongsToActiveUser User associated with this device is currently in the foreground.
 * @param hasSecureChannel `true` if a secure channel is available for this device.
 */
data class ConnectedDevice internal constructor(
    val deviceId: String,
    val deviceName: String?,
    val belongsToActiveUser: Boolean,
    val hasSecureChannel: Boolean
)
