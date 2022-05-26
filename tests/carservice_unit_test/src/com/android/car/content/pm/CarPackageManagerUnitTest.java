/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.car.content.pm;

import static android.car.content.pm.CarPackageManager.CAR_TARGET_VERSION_UNDEFINED;
import static android.car.testapi.CarMockitoHelper.mockHandleRemoteExceptionFromCarServiceWithDefaultValue;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.car.content.pm.ICarPackageManager;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class CarPackageManagerUnitTest extends AbstractExtendedMockitoTestCase {

    @Mock
    private Car mCar;
    @Mock
    private ICarPackageManager mService;

    private CarPackageManager mMgr;

    @Before
    public void setFixtures() {
        mMgr = new CarPackageManager(mCar, mService);
    }

    @Test
    public void testgetTargetCarMajorVersion_ok() throws Exception {
        when(mService.getTargetCarMajorVersion("bond.james.bond")).thenReturn(0x07);

        assertThat(mMgr.getTargetCarMajorVersion("bond.james.bond")).isEqualTo(0x07);
    }

    @Test
    public void testgetTargetCarMajorVersion_remoteException() throws Exception {
        mockHandleRemoteExceptionFromCarServiceWithDefaultValue(mCar);
        when(mService.getTargetCarMajorVersion("the.meaning.of.life"))
                .thenThrow(new RemoteException("D'OH!"));

        assertThat(mMgr.getTargetCarMajorVersion("the.meaning.of.life"))
                .isEqualTo(CAR_TARGET_VERSION_UNDEFINED);
    }

    @Test
    public void testgetTargetCarMinorVersion_ok() throws Exception {
        when(mService.getTargetCarMinorVersion("bond.james.bond")).thenReturn(0x07);

        assertThat(mMgr.getTargetCarMinorVersion("bond.james.bond")).isEqualTo(0x07);
    }

    @Test
    public void testgetTargetCarMinorVersion_remoteException() throws Exception {
        mockHandleRemoteExceptionFromCarServiceWithDefaultValue(mCar);
        when(mService.getTargetCarMinorVersion("the.meaning.of.life"))
                .thenThrow(new RemoteException("D'OH!"));

        assertThat(mMgr.getTargetCarMinorVersion("the.meaning.of.life"))
                .isEqualTo(CAR_TARGET_VERSION_UNDEFINED);
    }
}
