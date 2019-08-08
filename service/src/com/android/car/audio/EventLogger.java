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
package com.android.car.audio;

import android.util.Log;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Class to log events, the event are added to a queue of defined size.
 */
public class EventLogger {

    /**
     * Event class to write a message event for logging with EventLogger
     */
    private static class Event {

        // formatter for timestamps
        private static final SimpleDateFormat sFormat = new SimpleDateFormat("MM-dd HH:mm:ss:SSS");

        private long mTimestamp;


        private String mMsg;

        Event(String msg) {
            mTimestamp = System.currentTimeMillis();
            mMsg = msg;
        }

        /**
         * Set new message
         * @param msg message to set
         * @Note will replace the old timestamp on the message
         */
        void setMessage(String msg) {
            mTimestamp = System.currentTimeMillis();
            mMsg = msg;
        }

        /**
         * Converts event message to string
         * @return the event converted to a string
         */
        String eventToString() {
            return mMsg;
        }

        /**
         * Convert event to string
         * @return returns the event string.
         * @note toString includes the event timestamp
         */
        public String toString() {
            return (new StringBuilder(sFormat.format(new Date(mTimestamp))))
                    .append(" ").append(eventToString()).toString();
        }
    }

    // ring buffer of events to log.
    private final ArrayList<Event> mEvents;

    private final String mTitle;

    // the maximum number of events to keep in log
    private final int mMemSize;

    private int mTopOfQueue;

    /**
     * Constructor for logger.
     * @param size the maximum number of events to keep in log
     * @param title the string displayed before the recorded log
     */
    public EventLogger(int size, String title) {
        mEvents = new ArrayList<>();
        mMemSize = size;
        mTitle = title;
        mTopOfQueue = 0;
    }

    /**
     * log event into the logger
     * @param message message to log
     */
    public synchronized void log(String tag, String message) {
        if (mTopOfQueue >= mMemSize) {
            mTopOfQueue = 0;
        }

        if (mTopOfQueue < mEvents.size()) {
            mEvents.get(mTopOfQueue).setMessage(message);
        } else {
            mEvents.add(new Event(message));
        }

        Log.i(tag, message);
        ++mTopOfQueue;
    }

    /**
     * print the current logger state into the writer
     * @param indent indent to append to each event message
     * @param pw print writer to write into
     */
    public synchronized void dump(String indent, PrintWriter pw) {
        pw.println(String.format("%sEvent log: %s", indent, mTitle));

        for (int index = mTopOfQueue; index < mEvents.size(); index++) {
            pw.println(String.format("%s\t %s ", indent, mEvents.get(index).toString()));
        }

        for (int index = 0; index < mTopOfQueue; index++) {
            pw.println(String.format("%s\t %s ", indent, mEvents.get(index).toString()));
        }
    }
}
