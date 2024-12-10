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

import android.car.builtin.util.Slogf;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * A dispatch list that stores events for each client and then dispatch them to each client.
 *
 * This class is not thread-safe.
 *
 * Subclass must override {@code dispatchToClient}.
 *
 * @param <ClientType> The type for the client.
 * @param <EventType> The type for the event.
 */
public abstract class DispatchList<ClientType, EventType> {
    private static final String TAG = "DispatchList";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private ArrayMap<ClientType, List<EventType>> mEventsByClient = new ArrayMap<>();

    /**
     * Dispatch events to one client.
     *
     * Must be implemented by subclass.
     */
    protected abstract void dispatchToClient(ClientType client, List<EventType> events);

    /**
     * Adds an event to be dispatched to a client.
     */
    public void addEvent(ClientType client, EventType event) {
        if (DBG) {
            Slogf.d(TAG, "addEvent with client: %s, event: %s", client, event);
        }
        var eventsToNotify = mEventsByClient.get(client);
        if (eventsToNotify == null) {
            eventsToNotify = new ArrayList<EventType>();
            mEventsByClient.put(client, eventsToNotify);
        }
        eventsToNotify.add(event);
    }

    /**
     * Adds events to be dispatched to a client.
     */
    public void addEvents(ClientType client, List<EventType> events) {
        if (DBG) {
            Slogf.d(TAG, "addEvent with client: %s, events: %s", client, events);
        }
        var eventsToNotify = mEventsByClient.get(client);
        if (eventsToNotify == null) {
            eventsToNotify = new ArrayList<EventType>();
            mEventsByClient.put(client, eventsToNotify);
        }
        eventsToNotify.addAll(events);
    }

    /**
     * Dispatches all the added events to clients.
     *
     * This clears all the added events after dispatching.
     */
    public void dispatchToClients() {
        for (int i = 0; i < mEventsByClient.size(); i++) {
            if (DBG) {
                Slogf.d(TAG, "dispatch to client: %s, events: %s",
                        mEventsByClient.valueAt(i), mEventsByClient.valueAt(i));
            }

            dispatchToClient(mEventsByClient.keyAt(i), mEventsByClient.valueAt(i));
        }
        mEventsByClient.clear();
    }
}
