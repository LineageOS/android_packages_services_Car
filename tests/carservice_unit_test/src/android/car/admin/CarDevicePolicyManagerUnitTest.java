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
package android.car.admin;

import static android.car.testapi.CarMockitoHelper.mockHandleRemoteExceptionFromCarServiceWithDefaultValue;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.car.Car;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.util.UserTestingHelper.UserInfoBuilder;
import android.car.user.UserCreationResult;
import android.car.user.UserRemovalResult;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class CarDevicePolicyManagerUnitTest extends AbstractExtendedMockitoTestCase {

    @Mock
    private Car mCar;

    @Mock
    private ICarDevicePolicyService mService;

    private CarDevicePolicyManager mMgr;

    @Before
    public void setFixtures() {
        mMgr = new CarDevicePolicyManager(mCar, mService);
    }

    @Test
    public void testRemoveUser_success() throws Exception {
        int status = UserRemovalResult.STATUS_SUCCESSFUL;
        when(mService.removeUser(100)).thenReturn(new UserRemovalResult(status));

        RemoveUserResult result = mMgr.removeUser(UserHandle.of(100));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo(RemoveUserResult.STATUS_SUCCESS);
    }

    @Test
    public void testRemoveUser_remoteException() throws Exception {
        doThrow(new RemoteException("D'OH!")).when(mService).removeUser(100);
        mockHandleRemoteExceptionFromCarServiceWithDefaultValue(mCar);

        RemoveUserResult result = mMgr.removeUser(UserHandle.of(100));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(RemoveUserResult.STATUS_FAILURE_GENERIC);
    }

    @Test
    public void testRemoveUser_nullUser() {
        assertThrows(NullPointerException.class, () -> mMgr.removeUser(null));
    }

    @Test
    public void testCreateUser_success() throws Exception {
        UserInfo user = new UserInfoBuilder(100).build();
        int status = UserCreationResult.STATUS_SUCCESSFUL;
        when(mService.createUser("TheDude", 100))
                .thenReturn(new UserCreationResult(status, user, /* errorMessage= */ null));

        CreateUserResult result = mMgr.createUser("TheDude", 100);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo(CreateUserResult.STATUS_SUCCESS);
        assertThat(result.getUserHandle().getIdentifier()).isEqualTo(100);
    }

    @Test
    public void testCreateUser_remoteException() throws Exception {
        doThrow(new RemoteException("D'OH!")).when(mService).createUser("TheDude", 100);
        mockHandleRemoteExceptionFromCarServiceWithDefaultValue(mCar);

        CreateUserResult result = mMgr.createUser("TheDude", 100);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(CreateUserResult.STATUS_FAILURE_GENERIC);
        assertThat(result.getUserHandle()).isNull();
    }
}
