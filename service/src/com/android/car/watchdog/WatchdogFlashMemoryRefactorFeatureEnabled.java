/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car.watchdog;

/**
 * Class to control flash memory refactoring feature according to build system flag
 * RELEASE_CAR_FRAMEWORK_WATCHDOG_FLASHMEMORY_REFACTOR.
 *
 * <p>This class is necessary to enable watchdog flash memory refactoring feature. When
 * RELEASE_CAR_FRAMEWORK_WATCHDOG_FLASHMEMORY_REFACTOR is set, this file is included.
 *
 * @hide
 */
class WatchdogFlashMemoryRefactorFeatureFlag {
    public static boolean isFeatureSupported() {
        return true;
    }
}
