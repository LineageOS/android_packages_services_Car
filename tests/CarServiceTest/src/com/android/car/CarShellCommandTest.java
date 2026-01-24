/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.car;

import static android.car.feature.Flags.FLAG_DISPLAY_COMPATIBILITY_V2;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.content.Context;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.hal.VehicleHal;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.pm.CarPackageManagerService;
import com.android.car.systeminterface.SystemInterface;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class CarShellCommandTest extends MockedCarTestBase {
    private static final int CURRENT_USER_ID = 15;
    private static final int RESULT_OK = 0;
    private static final int RESULT_ERROR = -1;
    private static final String COMMAND_GET_DENSITY_SCALE_FACTOR = "get-density-scale-factor";
    private static final String COMMAND_SET_DENSITY_SCALE_FACTOR = "set-density-scale-factor";
    private static final String PARAM_USER = "--user";
    private static final String PARAM_DISPLAY = "--display";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Mock
    private Context mContext;
    @Mock
    private VehicleHal mVehicleHal;
    @Mock
    private CarFeatureController mCarFeatureController;
    @Mock
    private SystemInterface mSystemInterface;
    @Mock
    private IndentingPrintWriter mIndentingPrintWriter;
    @Mock
    private CarPackageManagerService mCarPackageManagerService;
    private CarShellCommand mCarShellCommand;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        Map<Class, CarSystemService> allServicesByClazz = new ArrayMap<>();
        allServicesByClazz.put(CarPackageManagerService.class, mCarPackageManagerService);
        mCarShellCommand =
                new CarShellCommand(mContext, mVehicleHal, mCarFeatureController, mSystemInterface,
                        allServicesByClazz) {
                    @Override
                    @UserIdInt
                    int getActivityManagerCurrentUser() {
                        return CURRENT_USER_ID;
                    }
                };
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_validPkg_defaultUserDisplay() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = Display.DEFAULT_DISPLAY;
        float densityScaleFactor = 0.5f;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg),
                eq(userId), eq(displayId))).thenReturn(densityScaleFactor);

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR, testPkg},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_OK);
        verify(mIndentingPrintWriter).println(eq(densityScaleFactor));
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_invalidPkg_defaultUserDisplay() {
        String testPkg = "test.displaycompat.invalid";
        int userId = CURRENT_USER_ID;
        int displayId = Display.DEFAULT_DISPLAY;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg),
                eq(userId), eq(displayId))).thenThrow(new IllegalArgumentException());

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR, testPkg},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_validPkgUser_defaultDisplay() {
        String testPkg = "test.displaycompat";
        int userId = 20;
        int displayId = Display.DEFAULT_DISPLAY;
        float densityScaleFactor = 0.5f;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg),
                eq(userId), eq(displayId))).thenReturn(densityScaleFactor);

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR, testPkg,
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_OK);
        verify(mIndentingPrintWriter).println(eq(densityScaleFactor));
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_validPkg_inValidUser_defaultDisplay() {
        String testPkg = "test.displaycompat";
        int userId = 100;
        int displayId = Display.DEFAULT_DISPLAY;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg),
                eq(userId), eq(displayId))).thenThrow(new IllegalArgumentException());

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR, testPkg,
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_validPkgDisplay_defaultUser() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 10;
        float densityScaleFactor = 0.5f;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg),
                eq(userId), eq(displayId))).thenReturn(densityScaleFactor);

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR, testPkg,
                        PARAM_DISPLAY, String.valueOf(displayId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_OK);
        verify(mIndentingPrintWriter).println(eq(densityScaleFactor));
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_validPkg_invalidDisplay_defaultUser() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg),
                eq(userId), eq(displayId))).thenThrow(new IllegalArgumentException());

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR, testPkg,
                        PARAM_DISPLAY, String.valueOf(displayId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_nullPkg_validUserDisplay() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        float densityScaleFactor = 0.5f;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg),
                eq(userId), eq(displayId))).thenReturn(densityScaleFactor);

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR,
                        PARAM_DISPLAY, String.valueOf(displayId),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void getDensityScaleFactor_multiplePkg_validUserDisplay() {
        String testPkg1 = "test.displaycompat.1";
        String testPkg2 = "test.displaycompat.2";
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        float densityScaleFactor = 0.5f;
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg1),
                eq(userId), eq(displayId))).thenReturn(densityScaleFactor);
        when(mCarPackageManagerService.getDensityScaleFactor(eq(testPkg2),
                eq(userId), eq(displayId))).thenReturn(densityScaleFactor);

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_GET_DENSITY_SCALE_FACTOR,
                        testPkg1, testPkg2,
                        PARAM_DISPLAY, String.valueOf(displayId),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_validPkg_defaultUserDisplay() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = Display.DEFAULT_DISPLAY;
        float densityScaleFactor = 0.5f;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg, String.valueOf(densityScaleFactor)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_OK);
        verify(mCarPackageManagerService).setDensityScaleFactor(eq(testPkg), eq(userId),
                eq(displayId), eq(densityScaleFactor));
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_invalidPkg_defaultUserDisplay() {
        String testPkg = "test.displaycompat.invalid";
        int userId = CURRENT_USER_ID;
        int displayId = Display.DEFAULT_DISPLAY;
        float densityScaleFactor = 0.5f;
        doThrow(new IllegalArgumentException()).when(mCarPackageManagerService)
                .setDensityScaleFactor(eq(testPkg), eq(userId), eq(displayId),
                        eq(densityScaleFactor));

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg, String.valueOf(densityScaleFactor)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_validPkgUser_defaultDisplay() {
        String testPkg = "test.displaycompat";
        int userId = 20;
        int displayId = Display.DEFAULT_DISPLAY;
        float densityScaleFactor = 0.5f;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg, String.valueOf(densityScaleFactor),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_OK);
        verify(mCarPackageManagerService).setDensityScaleFactor(eq(testPkg), eq(userId),
                eq(displayId), eq(densityScaleFactor));
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_validPkg_inValidUser_defaultDisplay() {
        String testPkg = "test.displaycompat";
        int userId = 100;
        int displayId = Display.DEFAULT_DISPLAY;
        float densityScaleFactor = 0.5f;
        doThrow(new IllegalArgumentException()).when(mCarPackageManagerService)
                .setDensityScaleFactor(eq(testPkg), eq(userId), eq(displayId),
                        eq(densityScaleFactor));

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg, String.valueOf(densityScaleFactor),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_validPkgDisplay_defaultUser() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 10;
        float densityScaleFactor = 0.5f;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg, String.valueOf(densityScaleFactor),
                        PARAM_DISPLAY, String.valueOf(displayId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_OK);
        verify(mCarPackageManagerService).setDensityScaleFactor(eq(testPkg), eq(userId),
                eq(displayId), eq(densityScaleFactor));
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_validPkg_invalidDisplay_defaultUser() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        float densityScaleFactor = 0.5f;
        doThrow(new IllegalArgumentException()).when(mCarPackageManagerService)
                .setDensityScaleFactor(eq(testPkg), eq(userId), eq(displayId),
                        eq(densityScaleFactor));

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg, String.valueOf(densityScaleFactor),
                        PARAM_DISPLAY, String.valueOf(displayId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_nullPkg_validUserDisplay() {
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        float densityScaleFactor = 0.5f;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        String.valueOf(densityScaleFactor),
                        PARAM_DISPLAY, String.valueOf(displayId),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_multiplePkg_validUserDisplay() {
        String testPkg1 = "test.displaycompat.1";
        String testPkg2 = "test.displaycompat.2";
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        float densityScaleFactor = 0.5f;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg1, testPkg2, String.valueOf(densityScaleFactor),
                        PARAM_DISPLAY, String.valueOf(displayId),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_nullScaleFactor_validPkgUserDisplay() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 100;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg,
                        PARAM_DISPLAY, String.valueOf(displayId),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_invalidScaleFactor_validPkgUserDisplay() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        float densityScaleFactor = -5f;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg, String.valueOf(densityScaleFactor),
                        PARAM_DISPLAY, String.valueOf(displayId),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }

    @Test
    @EnableFlags({FLAG_DISPLAY_COMPATIBILITY_V2})
    public void setDensityScaleFactor_multipleScaleFactor_validPkgUserDisplay() {
        String testPkg = "test.displaycompat";
        int userId = CURRENT_USER_ID;
        int displayId = 100;
        float densityScaleFactor1 = 0.4f;
        float densityScaleFactor2 = 0.5f;

        int resultCode = mCarShellCommand.exec(
                new String[]{COMMAND_SET_DENSITY_SCALE_FACTOR,
                        testPkg,
                        String.valueOf(densityScaleFactor1), String.valueOf(densityScaleFactor2),
                        PARAM_DISPLAY, String.valueOf(displayId),
                        PARAM_USER, String.valueOf(userId)},
                mIndentingPrintWriter);

        assertThat(resultCode).isEqualTo(RESULT_ERROR);
    }
}
