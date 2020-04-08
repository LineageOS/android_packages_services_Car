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

package android.car.userlib;

import static android.car.userlib.InitialUserSetterTest.setHeadlessSystemUserMode;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import android.os.UserHandle;
import android.os.UserManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public final class UserHelperTest {

    private MockitoSession mSession;

    @Before
    public void setSession() {
        mSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(UserManager.class)
                .initMocks(this)
                .startMocking();
    }

    @After
    public void finishSession() throws Exception {
        mSession.finishMocking();
    }

    @Test
    public void testSafeName() {
        assertThat(UserHelper.safeName(null)).isNull();

        String safe = UserHelper.safeName("UnsafeIam");
        assertThat(safe).isNotNull();
        assertThat(safe).doesNotContain("UnsafeIAm");
    }

    @Test
    public void testIsHeadlessSystemUser_system_headlessMode() {
        setHeadlessSystemUserMode(true);
        assertThat(UserHelper.isHeadlessSystemUser(UserHandle.USER_SYSTEM)).isTrue();
    }

    @Test
    public void testIsHeadlessSystemUser_system_nonHeadlessMode() {
        setHeadlessSystemUserMode(false);
        assertThat(UserHelper.isHeadlessSystemUser(UserHandle.USER_SYSTEM)).isFalse();
    }

    @Test
    public void testIsHeadlessSystemUser_nonSystem_headlessMode() {
        setHeadlessSystemUserMode(true);
        assertThat(UserHelper.isHeadlessSystemUser(10)).isFalse();
    }

    @Test
    public void testIsHeadlessSystemUser_nonSystem_nonHeadlessMode() {
        setHeadlessSystemUserMode(false);
        assertThat(UserHelper.isHeadlessSystemUser(10)).isFalse();
    }
}
