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

package com.android.wm.shell.automotive

/**
 * Interface for components that need to be initialized after the main WM Shell components are ready.
 *
 * The [initialize] method is invoked by [AutoShellInitializer] after the main [ShellInit] has
 * completed its initialization. Classes that implement this interface should be bound into a
 * Dagger multibinding Set, which is then injected into [AutoShellInitializer].
 *
 * Note: Classes implementing this interface should **not** inject
 * [com.android.wm.shell.sysui.ShellInit] and add their own callbacks. Doing so would be redundant
 * and could lead to an unpredictable initialization order. Instead, rely on this interface for all
 * post-shell-init logic.
 */
interface AutoShellInitializable {
    /**
     * Called when the shell is initialized.
     */
    fun initialize()
}
