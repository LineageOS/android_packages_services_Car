/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.internal.common;

import static com.google.common.truth.Truth.assertThat;

import android.os.UserHandle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class UserHelperLiteTest {

    @Test
    public void testSafeName_nullCheck() {
        assertThat(UserHelperLite.safeName(null)).isNull();
    }

    @Test
    public void testSafeName() {
        String safe = UserHelperLite.safeName("UnsafeIam");

        assertThat(safe).isNotNull();
        assertThat(safe).doesNotContain("UnsafeIAm");
    }

    @Test
    public void testIsHeadlessSystemUser_system_headlessMode() {
        assertThat(UserHelperLite.isHeadlessSystemUser(UserHandle.USER_SYSTEM, true)).isTrue();
    }

    @Test
    public void testIsHeadlessSystemUser_system_nonHeadlessMode() {
        assertThat(UserHelperLite.isHeadlessSystemUser(UserHandle.USER_SYSTEM, false)).isFalse();
    }

    @Test
    public void testIsHeadlessSystemUser_nonSystem_headlessMode() {
        assertThat(UserHelperLite.isHeadlessSystemUser(10, true)).isFalse();
    }

    @Test
    public void testIsHeadlessSystemUser_nonSystem_nonHeadlessMode() {
        assertThat(UserHelperLite.isHeadlessSystemUser(10, false)).isFalse();
    }
}
