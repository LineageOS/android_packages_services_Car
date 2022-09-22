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

package android.car.apitest;

import static com.google.common.truth.Truth.assertThat;

import android.car.AoapService;
import android.car.test.ApiCheckerRule.Builder;
import android.hardware.usb.UsbDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class AoapServiceTest extends CarLessApiTestBase {

    private AoapService mAoapService;

    @Mock
    private UsbDevice mDevice;

    // TODO(b/242350638): add missing annotations, remove (on child bug of 242350638)
    @Override
    protected void configApiCheckerRule(Builder builder) {
        builder.disableAnnotationsCheck();
    }

    @Before
    public void setUp() {
        mAoapService = new AoapService() {

            @Override
            public int isDeviceSupported(UsbDevice device) {
                return 0;
            }
        };
    }

    @Test
    public void testCanSwitchToAoap_byDefaultReturns_RESULT_OK() {
        assertThat(mAoapService.canSwitchToAoap(mDevice)).isEqualTo(AoapService.RESULT_OK);
    }
}
