/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.pm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.testng.Assert.assertThrows;

import android.app.ActivityManager;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.SystemActivityMonitoringService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tests for {@link CarPackageManagerService}.
 */
@RunWith(AndroidJUnit4.class)
public class CarPackageManagerServiceTest extends AbstractExtendedMockitoTestCase{
    CarPackageManagerService mService;

    private Context mContext;
    @Mock
    private CarUxRestrictionsManagerService mMockUxrService;
    @Mock
    private SystemActivityMonitoringService mMockSamService;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ActivityManager.class);
    }

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mService = new CarPackageManagerService(mContext, mMockUxrService, mMockSamService);
    }

    @Test
    public void testParseConfigList_SingleActivity() {
        String config = "com.android.test/.TestActivity";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertTrue(map.get("com.android.test").size() == 1);
        assertEquals(".TestActivity", map.get("com.android.test").iterator().next());
    }

    @Test
    public void testParseConfigList_Package() {
        String config = "com.android.test";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertTrue(map.get("com.android.test").size() == 0);
    }

    @Test
    public void testParseConfigList_MultipleActivities() {
        String config = "com.android.test/.TestActivity0,com.android.test/.TestActivity1";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertTrue(map.get("com.android.test").size() == 2);
        assertTrue(map.get("com.android.test").contains(".TestActivity0"));
        assertTrue(map.get("com.android.test").contains(".TestActivity1"));
    }

    @Test
    public void testParseConfigList_PackageAndActivity() {
        String config = "com.android.test/.TestActivity0,com.android.test";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertTrue(map.get("com.android.test").size() == 0);
    }

    @Test
    public void test_checkQueryPermission_noPermission() {
        mockQueryPermission(false);

        assertThrows(SecurityException.class,
                () -> mService.checkQueryPermission("blah"));
    }

    @Test
    public void test_checkQueryPermission_correctPermission() {
        mockQueryPermission(true);

        // call should complete without exception
        mService.checkQueryPermission("blah");
    }

    @Test
    public void test_checkQueryPermission_samePackage() {
        mockQueryPermission(false);

        // call should complete without exception
        mService.checkQueryPermission("com.android.car.test");
    }

    private void mockQueryPermission(boolean granted) {
        int result = android.content.pm.PackageManager.PERMISSION_DENIED;
        if (granted) {
            result = android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        doReturn(result).when(() -> ActivityManager.checkComponentPermission(any(), anyInt(),
                anyInt(), anyBoolean()));
    }

}
