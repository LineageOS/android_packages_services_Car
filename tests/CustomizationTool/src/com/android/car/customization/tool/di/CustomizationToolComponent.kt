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

package com.android.car.customization.tool.di

import android.accessibilityservice.AccessibilityService
import com.android.car.customization.tool.CustomizationToolService
import com.android.car.customization.tool.features.FeaturesModule
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        ServiceModule::class,
        FeaturesModule::class,
        MenuModule::class
    ]
)
internal interface CustomizationToolComponent {

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance service: AccessibilityService): CustomizationToolComponent
    }

    fun inject(service: CustomizationToolService)
}
