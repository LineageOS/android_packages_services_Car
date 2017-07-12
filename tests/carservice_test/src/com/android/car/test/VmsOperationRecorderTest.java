/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car.test;

import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.car.vms.VmsOperationRecorder;
import android.test.suitebuilder.annotation.MediumTest;

import junit.framework.TestCase;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

@MediumTest
public class VmsOperationRecorderTest extends TestCase {

    /** Capture messages that VmsOperationRecorder.Writer would normally pass to Log.d(...). */
    class TestWriter extends VmsOperationRecorder.Writer {
        public String mMsg;

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void write(String msg) {
            super.write(msg);
            mMsg = msg;
        }
    }

    private TestWriter mWriter;
    private VmsOperationRecorder mRecorder;

    private static final VmsLayer layer1 = new VmsLayer(1, 2, 3);
    private static final VmsLayer layer2 = new VmsLayer(2, 3, 4);
    private static final VmsLayer layer3 = new VmsLayer(3, 4, 5);

    private static final VmsLayerDependency layerDependency1 = new VmsLayerDependency(layer3);
    private static final VmsLayerDependency layerDependency2 = new VmsLayerDependency(layer1,
            new HashSet<VmsLayer>(Arrays.asList(layer2, layer3)));

    private static final VmsLayersOffering layersOffering0 = new VmsLayersOffering(
            new ArrayList<VmsLayerDependency>(), 66);
    private static final VmsLayersOffering layersOffering1 = new VmsLayersOffering(
            Arrays.asList(layerDependency1), 66);
    private static final VmsLayersOffering layersOffering2 = new VmsLayersOffering(
            Arrays.asList(layerDependency1, layerDependency2), 66);

    public void setUp() {
        mWriter = new TestWriter();
        mRecorder = new VmsOperationRecorder(mWriter);
    }

    public void testSubscribe() throws Exception {
        mRecorder.subscribe(layer1);
        assertJsonMsgEquals("{'subscribe':{'layer':{'id':1,'version':2,'subtype':3}}}");
    }

    public void testUnsubscribe() throws Exception {
        mRecorder.unsubscribe(layer1);
        assertJsonMsgEquals("{'unsubscribe':{'layer':{'id':1,'version':2,'subtype':3}}}");
    }

    public void testSubscribeAll() throws Exception {
        mRecorder.subscribeAll();
        assertJsonMsgEquals("{'subscribeAll':{}}");
    }

    public void testUnsubscribeAll() throws Exception {
        mRecorder.unsubscribeAll();
        assertJsonMsgEquals("{'unsubscribeAll':{}}");
    }

    public void testSetLayersOffering0() throws Exception {
        mRecorder.setLayersOffering(layersOffering0);
        assertJsonMsgEquals("{'setLayersOffering':{}}");
    }

    public void testSetLayersOffering2() throws Exception {
        mRecorder.setLayersOffering(layersOffering2);
        assertJsonMsgEquals("{'setLayersOffering':{'layerDependency':["
                + "{'layer':{'id':3,'version':4,'subtype':5}},"
                + "{'layer':{'id':1,'version':2,'subtype':3},'dependency':["
                + "{'id':2,'version':3,'subtype':4},{'id':3,'version':4,'subtype':5}]}]}}");
    }

    public void testGetPublisherStaticId() throws Exception {
        mRecorder.getPublisherStaticId(9);
        assertJsonMsgEquals("{'getPublisherStaticId':{'publisherStaticId':9}}");
    }

    public void testAddSubscription() throws Exception {
        mRecorder.addSubscription(42, layer1);
        assertJsonMsgEquals(
                "{'addSubscription':{'sequenceNumber':42,'layer':{'id':1,'version':2,'subtype':3}}}"
        );
    }

    public void testRemoveSubscription() throws Exception {
        mRecorder.removeSubscription(42, layer1);
        assertJsonMsgEquals("{'removeSubscription':"
                + "{'sequenceNumber':42,'layer':{'id':1,'version':2,'subtype':3}}}");
    }

    public void testAddPromiscuousSubscription() throws Exception {
        mRecorder.addPromiscuousSubscription(42);
        assertJsonMsgEquals("{'addPromiscuousSubscription':{'sequenceNumber':42}}");
    }

    public void testRemovePromiscuousSubscription() throws Exception {
        mRecorder.removePromiscuousSubscription(42);
        assertJsonMsgEquals("{'removePromiscuousSubscription':{'sequenceNumber':42}}");
    }

    public void testAddHalSubscription() throws Exception {
        mRecorder.addHalSubscription(42, layer1);
        assertJsonMsgEquals("{'addHalSubscription':"
                + "{'sequenceNumber':42,'layer':{'id':1,'version':2,'subtype':3}}}");
    }

    public void testRemoveHalSubscription() throws Exception {
        mRecorder.removeHalSubscription(42, layer1);
        assertJsonMsgEquals("{'removeHalSubscription':"
                + "{'sequenceNumber':42,'layer':{'id':1,'version':2,'subtype':3}}}");
    }

    public void testSetPublisherLayersOffering() throws Exception {
        mRecorder.setPublisherLayersOffering(layersOffering1);
        assertJsonMsgEquals("{'setPublisherLayersOffering':{'layerDependency':["
                + "{'layer':{'id':3,'version':4,'subtype':5}}]}}");
    }

    public void testSetHalPublisherLayersOffering() throws Exception {
        mRecorder.setHalPublisherLayersOffering(layersOffering1);
        assertJsonMsgEquals("{'setHalPublisherLayersOffering':{'layerDependency':["
                + "{'layer':{'id':3,'version':4,'subtype':5}}]}}");
    }

    private void assertJsonMsgEquals(String expectJson) throws Exception {
        // Escaping double quotes in a JSON string is really noisy. The test data uses single
        // quotes instead, which gets replaced here.
        JSONObject expect = new JSONObject(expectJson.replace("'", "\""));
        JSONObject got = new JSONObject(mWriter.mMsg);
        // Comparing the JSON strings works and is not flakey only because the JSON library
        // generates a consistent string representation when object construction is consistent.
        assertEquals(expect.toString(), got.toString());
    }
}
