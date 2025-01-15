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

package android.car.drivingstate;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.testapi.CarUxRestrictionsController;
import android.car.testapi.FakeCar;
import android.content.Context;
import android.os.RemoteException;
import android.os.UserManager;
import android.view.Display;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

@RunWith(MockitoJUnitRunner.class)
public class CarUxRestrictionsManagerUnitTest {
    @Rule
    public final MockitoRule rule = MockitoJUnit.rule();

    @Spy
    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Mock
    private UserManager mUserManager;
    @Mock
    private CarUxRestrictionsManager.OnUxRestrictionsChangedListener mListener;

    private CarUxRestrictionsManager mCarUxRestrictionsManager;
    private CarUxRestrictionsController mCarUxRestrictionsController;


    @Before
    public void setUp() {
        FakeCar fakeCar = FakeCar.createFakeCar(mContext);
        Car carApi = fakeCar.getCar();

        mCarUxRestrictionsManager =
                (CarUxRestrictionsManager) carApi.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
        mCarUxRestrictionsController = fakeCar.getCarUxRestrictionController();

        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mUserManager.getMainDisplayIdAssignedToUser()).thenReturn(Display.DEFAULT_DISPLAY);
    }

    @Test
    public void getRestrictions_noRestrictionsSet_noRestrictionsPresent() {
        assertThat(mCarUxRestrictionsManager.getCurrentCarUxRestrictions().getActiveRestrictions())
                .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
    }

    @Test
    public void setUxRestrictions_restrictionsRegistered() throws RemoteException {
        mCarUxRestrictionsController.setUxRestrictions(CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO);

        assertThat(mCarUxRestrictionsManager.getCurrentCarUxRestrictions().getActiveRestrictions())
                .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO);
    }

    @Test
    public void clearUxRestrictions_restrictionsCleared() throws RemoteException {
        mCarUxRestrictionsController
                .setUxRestrictions(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
        mCarUxRestrictionsController.clearUxRestrictions();

        assertThat(mCarUxRestrictionsManager.getCurrentCarUxRestrictions().getActiveRestrictions())
                .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
    }

    @Test
    public void isListenerRegistered_noListenerSet_returnsFalse() {
        assertThat(mCarUxRestrictionsController.isListenerRegistered()).isFalse();
    }

    @Test
    public void isListenerRegistered_listenerSet_returnsTrue() {
        mCarUxRestrictionsManager.registerListener(mListener);

        assertThat(mCarUxRestrictionsController.isListenerRegistered()).isTrue();
    }

    @Test
    public void setUxRestrictions_listenerRegistered_listenerTriggered() throws Exception {
        mCarUxRestrictionsManager.registerListener(mListener);
        mCarUxRestrictionsController
                .setUxRestrictions(CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE);

        verify(mListener, timeout(2000)).onUxRestrictionsChanged(any());
    }
}

