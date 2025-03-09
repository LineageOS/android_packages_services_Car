/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.internal.common;

import android.car.test.AbstractExpectableTestCase;
import android.util.ArrayMap;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class DispatchListUnitTest extends AbstractExpectableTestCase {

    private static class MyDispatchList extends DispatchList<String, Integer> {
        Map<String, List<Integer>> mEventsByClient = new ArrayMap<>();

        @Override
        protected void dispatchToClient(String client, List<Integer> events) {
            if (mEventsByClient.get(client) == null) {
                mEventsByClient.put(client, new ArrayList<Integer>());
            }
            mEventsByClient.get(client).addAll(events);
        }
    }

    @Test
    public void testAddEvent() {
        MyDispatchList list = new MyDispatchList();
        list.addEvent("client1", 1);
        list.addEvent("client2", 2);
        list.addEvent("client1", 3);

        list.dispatchToClients();

        expectThat(list.mEventsByClient.get("client1")).containsExactly(1, 3);
        expectThat(list.mEventsByClient.get("client2")).containsExactly(2);
    }

    @Test
    public void testAddEvents() {
        MyDispatchList list = new MyDispatchList();
        list.addEvents("client1", Arrays.asList(1, 2));
        list.addEvents("client2", Arrays.asList(3, 4));
        list.addEvents("client1", Arrays.asList(5, 6));

        list.dispatchToClients();

        expectThat(list.mEventsByClient.get("client1")).containsExactly(1, 2, 5, 6);
        expectThat(list.mEventsByClient.get("client2")).containsExactly(3, 4);
    }

    @Test
    public void testEmptyDispatch() {
        MyDispatchList list = new MyDispatchList();

        list.dispatchToClients();

        expectThat(list.mEventsByClient).hasSize(0);
    }
}
