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
package com.android.car.admin;

import static org.mockito.Mockito.verify;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;

import com.android.car.user.CarUserService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class CarDevicePolicyServiceTest extends AbstractExtendedMockitoTestCase {

    @Mock
    private CarUserService mCarUserService;

    private CarDevicePolicyService mService;

    @Before
    public void setFixtures() {
        mService = new CarDevicePolicyService(mCarUserService);
    }

    @Test
    public void testRemoveUser() {
        mService.removeUser(42);

        verify(mCarUserService).removeUser(42, /* hasCallerRestrictions= */ true);
    }
}
