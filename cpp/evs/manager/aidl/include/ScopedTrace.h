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

#pragma once

#define ATRACE_TAG ATRACE_TAG_CAMERA

#include <utils/Trace.h>

#include <ctime>
#include <string>

class ScopedTrace final {
public:
    explicit ScopedTrace(const std::string& name) : ScopedTrace(name, generateRandomInteger()) {}
    explicit ScopedTrace(const std::string& name, int cookie) : mName(name), mCookie(cookie) {
        beginTrace(mName, mCookie);
    }

    explicit ScopedTrace(const std::string& track, const std::string& name) :
          ScopedTrace(track, name, generateRandomInteger()) {}
    explicit ScopedTrace(const std::string& track, const std::string& name, int cookie) :
          mTrack(track), mName(name), mCookie(cookie), mHasTrack(true) {
        beginTrace(mTrack, mName, mCookie);
    }

    ~ScopedTrace() {
        if (mHasTrack) {
            endTrace(mTrack, mName, mCookie);
        } else {
            endTrace(mName, mCookie);
        }
    }

    // This should not be a copyable.
    ScopedTrace(const ScopedTrace&) = delete;
    ScopedTrace& operator=(const ScopedTrace&) = delete;

private:
    static void beginTrace(const std::string& name, int cookie) {
        ATRACE_ASYNC_BEGIN(name.c_str(), cookie);
    }

    static void beginTrace(const std::string& track, const std::string& name, int cookie) {
        ATRACE_ASYNC_FOR_TRACK_BEGIN(track.c_str(), name.c_str(), cookie);
    }

    static void endTrace(const std::string& name, int cookie) {
        ATRACE_ASYNC_END(name.c_str(), cookie);
    }

    static void endTrace(const std::string& track, [[maybe_unused]] const std::string& name,
                         int cookie) {
        ATRACE_ASYNC_FOR_TRACK_END(track.c_str(), cookie);
    }

    static int generateRandomInteger() {
        srand(time(nullptr));
        return rand();
    }

    std::string mTrack;
    std::string mName;
    int mCookie;
    bool mHasTrack = false;
};
