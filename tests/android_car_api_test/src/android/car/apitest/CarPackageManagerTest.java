/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.car.apitest;

import android.car.Car;
import android.car.CarApiVersion;
import android.car.content.pm.CarPackageManager;
import android.test.suitebuilder.annotation.MediumTest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

@MediumTest
public class CarPackageManagerTest extends CarApiTestBase {

    private CarPackageManager mCarPackageManager;

    @Before
    public void setFixtures() {
        mCarPackageManager = (CarPackageManager) getCar().getCarManager(Car.PACKAGE_SERVICE);
    }

    @Test
    public void testCreate() throws Exception {
        assertThat(mCarPackageManager).isNotNull();
    }

    @Test
    public void testgetTargetCarApiVersion_noPackage() throws Exception {
        String pkg = "I can't believe a package with this name exist. If so, well, too bad!";

        CarApiVersion apiVersion = mCarPackageManager.getTargetCarApiVersion(pkg);

        assertWithMessage("getTargetCarApiVersion(%s)", pkg).that(apiVersion).isNull();
    }

    @Ignore("TODO(b/228506662): need to update Car.PLATFORM_API_VERSION on master")
    @Test
    public void testGetTargetCarMajorAndMinorVersion_notSet() throws Exception {
        String pkg = "com.android.car";
        // TODO(b/228506662): it would be better add another app that explicitly sets sdkTarget
        // version instead, so it doesn't depend on com.android.car's version (which this test
        // doesn't control)
        int targetSdk = Car.getPlatformApiVersion().getMajorVersion();

        CarApiVersion apiVersion = mCarPackageManager.getTargetCarApiVersion(pkg);

        assertWithMessage("getTargetCarApiVersion(%s)", pkg).that(apiVersion).isNotNull();
        assertWithMessage("major version for %s", pkg)
                .that(apiVersion.getMajorVersion())
                .isEqualTo(targetSdk);
        assertWithMessage("minor version for %s", pkg)
                .that(apiVersion.getMinorVersion())
                .isEqualTo(0);
    }

    @Test
    public void testGetTargetCarMajorAndMinorVersion_set() throws Exception {
        String pkg = sContext.getPackageName();

        CarApiVersion apiVersion = mCarPackageManager.getTargetCarApiVersion(pkg);

        assertWithMessage("getTargetCarApiVersion(%s)", pkg).that(apiVersion).isNotNull();
        assertWithMessage("major version for %s", pkg)
                .that(apiVersion.getMajorVersion())
                .isEqualTo(108);
        assertWithMessage("minor version for %s", pkg)
                .that(apiVersion.getMinorVersion())
                .isEqualTo(42);
    }
}
