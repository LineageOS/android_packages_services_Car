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

package com.android.car.garagemode;

import android.util.Slog;

class Slogger {
    private final String mTag;
    private final String mPrefix;

    Slogger(String prefix) {
        mTag = "GarageMode";
        mPrefix = prefix;
    }

    /** Passing message further to Slog.v() */
    public void v(String msg) {
        Slog.v(mTag, buildMessage(msg));
    }

    /** Passing message further to Slog.v() */
    public void v(String msg, Exception ex) {
        Slog.v(mTag, buildMessage(msg), ex);
    }

    /** Passing message further to Slog.i() */
    public void i(String msg) {
        Slog.i(mTag, buildMessage(msg));
    }

    /** Passing message further to Slog.i() */
    public void i(String msg, Exception ex) {
        Slog.i(mTag, buildMessage(msg), ex);
    }

    /** Passing message further to Slog.d() */
    public void d(String msg) {
        Slog.d(mTag, buildMessage(msg));
    }

    /** Passing message further to Slog.d() */
    public void d(String msg, Exception ex) {
        Slog.d(mTag, buildMessage(msg), ex);
    }

    /** Passing message further to Slog.w() */
    public void w(String msg, Exception ex) {
        Slog.w(mTag, buildMessage(msg), ex);
    }

    /** Passing message further to Slog.w() */
    public void w(String msg) {
        Slog.w(mTag, buildMessage(msg));
    }

    /** Passing message further to Slog.e() */
    public void e(String msg) {
        Slog.e(mTag, buildMessage(msg));
    }

    /** Passing message further to Slog.e() */
    public void e(String msg, Exception ex) {
        Slog.e(mTag, buildMessage(msg), ex);
    }

    /** Passing message further to Slog.e() */
    public void e(String msg, Throwable ex) {
        Slog.e(mTag, buildMessage(msg), ex);
    }

    private String buildMessage(String msg) {
        return String.format("[%s]: %s", mPrefix, msg);
    }
}
