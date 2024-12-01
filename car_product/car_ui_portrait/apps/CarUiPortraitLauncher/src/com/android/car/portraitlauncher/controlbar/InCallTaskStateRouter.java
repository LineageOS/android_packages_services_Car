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
package com.android.car.portraitlauncher.controlbar;

/**
 * Routes InCall task status to {@link InCallTaskStateListener}.
 */
public class InCallTaskStateRouter {
    private static InCallTaskStateRouter sInstance;

    /**
     * Interface to provide information regarding in call activity task state changes.
     */
    public interface InCallTaskStateListener {


        /** Notifies if an in-call task is on top. */
        void onTaskStateChanged(boolean isTaskOnTop);
    }
    private InCallTaskStateListener mInCallTaskStateHandler;

    /**
     * @return an instance of {@link InCallTaskStateRouter}.
     */
    public static InCallTaskStateRouter getInstance() {
        if (sInstance == null) {
            sInstance = new InCallTaskStateRouter();
        }
        return sInstance;
    }

    /**
     * Register a {@link InCallTaskStateListener}.
     */
    public void registerInCallTaskStateListener(InCallTaskStateListener inCallTaskStateListener) {
        mInCallTaskStateHandler = inCallTaskStateListener;
    }

    /**
     * Dispatches InCall Task state to {@link InCallTaskStateListener}
     */
    public void handleInCallTaskState(boolean isInCallTaskOnTop) {
        if (mInCallTaskStateHandler != null) {
            mInCallTaskStateHandler.onTaskStateChanged(isInCallTaskOnTop);
        }
    }
}
