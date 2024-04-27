/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.customization.tool

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.android.car.customization.tool.di.DaggerCustomizationToolComponent
import com.android.car.customization.tool.domain.CustomizationToolStateMachine
import com.android.car.customization.tool.domain.ReloadStateAction
import com.android.car.customization.tool.ui.CustomizationToolUI
import javax.inject.Inject

/**
 * Entry point of the Customization Tool.
 */
internal class CustomizationToolService : AccessibilityService() {

    @Inject
    lateinit var customizationToolStateMachine: CustomizationToolStateMachine

    @Inject
    lateinit var customizationToolUI: CustomizationToolUI

    override fun onServiceConnected() {
        super.onServiceConnected()

        DaggerCustomizationToolComponent.factory().create(this).inject(this)

        customizationToolStateMachine.register(customizationToolUI)
        customizationToolUI.handleAction = customizationToolStateMachine::handleAction

        val handler = Handler(Looper.getMainLooper())
        val delay = 5000L

        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    customizationToolStateMachine.handleAction(ReloadStateAction)
                    handler.postDelayed(this, delay)
                }
            },
            delay
        )
    }

    override fun onDestroy() {
        customizationToolStateMachine.unregister()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }
}
