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

package com.android.car.hal;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public final class HalServiceBaseTest {

    private static final int ONE_SUPPORTED_PROPERTY = 1;

    private final HalServiceBaseImpl mHalServiceBase = new HalServiceBaseImpl();

    @Test
    public void testGetDispatchList() {
        int[] propList = {1, 2};
        int[] areaIdList = {1, 2};
        List<HalPropValue> expectedList = new ArrayList<HalPropValue>();
        expectedList.add(mHalServiceBase.addAidlHalPropValue(propList[0], areaIdList[0]));
        expectedList.add(mHalServiceBase.addHidlHalPropValue(propList[1], areaIdList[1]));

        assertThat(mHalServiceBase.getDispatchList()).containsExactlyElementsIn(expectedList);
    }

    @Test
    public void testIsSupportedProperty_withReturnTrue() {
        boolean isSupportedProperty = mHalServiceBase.isSupportedProperty(ONE_SUPPORTED_PROPERTY);

        assertThat(isSupportedProperty).isTrue();
    }

    @Test
    public void testIsSupportedProperty_withReturnFalse() {
        boolean isSupportedProperty = mHalServiceBase
                .isSupportedProperty(HalServiceBase.NOT_SUPPORTED_PROPERTY);

        assertThat(isSupportedProperty).isFalse();
    }

    private static final class HalServiceBaseImpl extends HalServiceBase {
        private HalPropValueBuilder mAidlHalPropValueBuilder =
                new HalPropValueBuilder(/* isAidl= */ true);
        private HalPropValueBuilder mHidlHalPropValueBuilder =
                new HalPropValueBuilder(/* isAidl= */ false);

        @Override
        public void init() { }

        @Override
        public void release() { }

        @Override
        public int[] getAllSupportedProperties() {
            return new int[] {1, 2, 3};
        }

        @Override
        public void dump(PrintWriter writer) { }

        private HalPropValue addAidlHalPropValue(int prop, int areaId) {
            HalPropValue val = mAidlHalPropValueBuilder.build(prop, areaId);
            getDispatchList().add(val);
            return val;
        }

        private HalPropValue addHidlHalPropValue(int prop, int areaId) {
            HalPropValue val = mHidlHalPropValueBuilder.build(prop, areaId);
            getDispatchList().add(val);
            return val;
        }
    }
}
