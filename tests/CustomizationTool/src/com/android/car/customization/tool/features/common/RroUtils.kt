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

package com.android.car.customization.tool.features.common

import android.content.om.OverlayIdentifier
import android.content.om.OverlayInfo
import android.content.om.OverlayManager
import android.content.om.OverlayManagerTransaction
import android.os.UserHandle

fun OverlayInfo.isValid(): Boolean = state == 2 /*STATE_DISABLED*/ || state == 3 /*STATE_ENABLED*/

/**
 * Alternative to the standard [OverlayManager.setEnabled] that also works for fabricated RROs.
 */
fun OverlayManager.setEnableOverlay(
    identifier: OverlayIdentifier,
    newState: Boolean,
    userHandle: UserHandle
) {
    commit(
        OverlayManagerTransaction.Builder()
            .setEnabled(identifier, newState, userHandle.identifier)
            .build()
    )
}
