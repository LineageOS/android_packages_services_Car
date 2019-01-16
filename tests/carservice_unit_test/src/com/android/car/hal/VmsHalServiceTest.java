/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.car.vms.VmsAssociatedLayer;
import android.car.vms.VmsAvailableLayers;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.os.Binder;
import android.os.IBinder;

import androidx.test.runner.AndroidJUnit4;

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class VmsHalServiceTest {
    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private VehicleHal mMockVehicleHal;
    @Mock private VmsHalService.VmsHalSubscriberListener mMockHalSusbcriber;
    private IBinder mToken;
    private VmsHalService mHalService;

    @Before
    public void setUp() throws Exception {
        mToken = new Binder();
        mHalService = new VmsHalService(mMockVehicleHal);
        mHalService.addSubscriberListener(mMockHalSusbcriber);
    }

    @Test
    public void testSetPublisherLayersOffering() {
        VmsLayer layer = new VmsLayer(1, 2, 3);
        VmsLayersOffering offering = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 12345);
        mHalService.setPublisherLayersOffering(mToken, offering);

        VmsAssociatedLayer associatedLayer = new VmsAssociatedLayer(layer, Sets.newHashSet(12345));
        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(associatedLayer),
                1)));
    }

    @Test
    public void testSetPublisherLayersOffering_Repeated() {
        VmsLayer layer = new VmsLayer(1, 2, 3);
        VmsLayersOffering offering = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 12345);
        mHalService.setPublisherLayersOffering(mToken, offering);
        mHalService.setPublisherLayersOffering(mToken, offering);

        VmsAssociatedLayer associatedLayer = new VmsAssociatedLayer(layer, Sets.newHashSet(12345));
        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(associatedLayer),
                1)));
        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(associatedLayer),
                2)));

    }

    @Test
    public void testSetPublisherLayersOffering_MultiplePublishers() {
        VmsLayer layer = new VmsLayer(1, 2, 3);
        VmsLayersOffering offering = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 12345);
        VmsLayersOffering offering2 = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 54321);
        mHalService.setPublisherLayersOffering(mToken, offering);
        mHalService.setPublisherLayersOffering(new Binder(), offering2);

        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(new VmsAssociatedLayer(layer, Sets.newHashSet(12345))),
                1)));
        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(new VmsAssociatedLayer(layer, Sets.newHashSet(12345, 54321))),
                2)));

    }

    @Test
    public void testSetPublisherLayersOffering_MultiplePublishers_SharedToken() {
        VmsLayer layer = new VmsLayer(1, 2, 3);
        VmsLayersOffering offering = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 12345);
        VmsLayersOffering offering2 = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 54321);
        mHalService.setPublisherLayersOffering(mToken, offering);
        mHalService.setPublisherLayersOffering(mToken, offering2);

        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(new VmsAssociatedLayer(layer, Sets.newHashSet(12345))),
                1)));
        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(new VmsAssociatedLayer(layer, Sets.newHashSet(12345, 54321))),
                2)));
    }

    @Test
    public void testSetPublisherLayersOffering_MultiplePublishers_MultipleLayers() {
        VmsLayer layer = new VmsLayer(1, 2, 3);
        VmsLayer layer2 = new VmsLayer(2, 2, 3);
        VmsLayersOffering offering = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 12345);
        VmsLayersOffering offering2 = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer2)), 54321);
        mHalService.setPublisherLayersOffering(mToken, offering);
        mHalService.setPublisherLayersOffering(new Binder(), offering2);

        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(new VmsAssociatedLayer(layer, Sets.newHashSet(12345))),
                1)));
        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(
                        new VmsAssociatedLayer(layer, Sets.newHashSet(12345)),
                        new VmsAssociatedLayer(layer2, Sets.newHashSet(54321))),
                2)));

    }

    @Test
    public void testSetPublisherLayersOffering_MultiplePublishers_MultipleLayers_SharedToken() {
        VmsLayer layer = new VmsLayer(1, 2, 3);
        VmsLayer layer2 = new VmsLayer(2, 2, 3);
        VmsLayersOffering offering = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer)), 12345);
        VmsLayersOffering offering2 = new VmsLayersOffering(
                Sets.newHashSet(new VmsLayerDependency(layer2)), 54321);
        mHalService.setPublisherLayersOffering(mToken, offering);
        mHalService.setPublisherLayersOffering(mToken, offering2);

        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(new VmsAssociatedLayer(layer, Sets.newHashSet(12345))),
                1)));
        verify(mMockHalSusbcriber).onLayersAvaiabilityChange(eq(new VmsAvailableLayers(
                Sets.newHashSet(
                        new VmsAssociatedLayer(layer, Sets.newHashSet(12345)),
                        new VmsAssociatedLayer(layer2, Sets.newHashSet(54321))),
                2)));

    }
}
