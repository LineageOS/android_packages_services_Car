/*
 * Copyright 2020 The Android Open Source Project
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

#include "emul/VideoCapture.h"

#include <assert.h>
#include <errno.h>
#include <error.h>
#include <fcntl.h>
#include <memory.h>
#include <processgroup/sched_policy.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <fstream>
#include <iomanip>

#include <android-base/logging.h>

using namespace std;

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

VideoCapture::~VideoCapture() {
    // Stop active stream
    stopStream();

    // Close the device
    close();
}


bool VideoCapture::open(const std::string& path,
                        const int width,
                        const int height,
                        const std::chrono::nanoseconds interval) {
    // Report device properties
    LOG(INFO) << "Open a virtual video stream with data from " << path;
    LOG(INFO) << "\tResolution: " << width << ", " << height;

    // Store the source location
    if (!filesystem::exists(path) || !filesystem::is_directory(path)) {
        LOG(INFO) << path << " does not exist or is not a directory.";
        return false;
    }

    // Sets a directory iterator
    LOG(INFO) << "directory_iterator is set to " << path;
    mSrcIter = filesystem::directory_iterator(path);
    mSourceDir = path;

    // Sets a target resolution
    mWidth = width, mHeight = height;

    // Only support YUYV format that chroma is subsampled 1/2 horizontally
    // TODO(b/162946784): modify below line when adding more formats to support
    mFormat = V4L2_PIX_FMT_YUYV;
    mStride = mWidth * 2;

    // Set a frame rate
    mDesiredFrameInterval = interval;

    // Allocate a buffer to copy the contents
    mPixelBuffer = static_cast<char*>(malloc(mStride * mHeight * sizeof(char)));
    if (!mPixelBuffer) {
        LOG(ERROR) << "Failed to allocate a buffer";
        return false;
    }

    // Make sure we're initialized to the STOPPED state
    mRunMode = STOPPED;
    mFrameReady = false;

    // Ready to go!
    return true;
}


void VideoCapture::close() {
    LOG(DEBUG) << __FUNCTION__;

    // Stream must be stopped first!
    assert(mRunMode == STOPPED);

    // Free allocated resources
    free(mPixelBuffer);
}


bool VideoCapture::startStream(
        std::function<void(VideoCapture*, imageBuffer*, void*)> callback) {
    // Set the state of our background thread
    int prevRunMode = mRunMode.fetch_or(RUN);
    if (prevRunMode & RUN) {
        // The background thread is already running, so we can't start a new stream
        LOG(ERROR) << "Already in RUN state, so we can't start a new streaming thread";
        return false;
    }

    // Remembers who to tell about new frames as they arrive
    mCallback = callback;

    // Fires up a thread to generate and dispatch the video frames
    mCaptureThread = std::thread([&](){
        if (mCurrentStreamEvent != StreamEvent::INIT) {
            LOG(ERROR) << "Not in the right state to start a video stream.  Current state is "
                       << mCurrentStreamEvent;
            return;
        }

        // We'll periodically send a new frame
        mCurrentStreamEvent = StreamEvent::PERIODIC;

        // Sets a background priority
        if (set_sched_policy(0, SP_BACKGROUND) != 0) {
            PLOG(WARNING) << "Failed to set background scheduling priority";
        }

        // Sets a looper for the communication
        if (android::Looper::getForThread() != nullptr) {
            LOG(DEBUG) << "Use existing looper thread";
        }

        mLooper = android::Looper::prepare(/*opts=*/0);
        if (mLooper == nullptr) {
            LOG(ERROR) << "Failed to initialize the looper.  Exiting the thread.";
            return;
        }

        // Requests to start generating frames periodically
        mLooper->sendMessage(this, StreamEvent::PERIODIC);

        // Polling the messages until the stream stops
        while (mRunMode == RUN) {
            mLooper->pollAll(/*timeoutMillis=*/-1);
        }

        LOG(INFO) << "Capture thread is exiting!!!";
    });

    LOG(DEBUG) << "Stream started.";
    return true;
}


void VideoCapture::stopStream() {
    // Tell the background thread to stop
    int prevRunMode = mRunMode.fetch_or(STOPPING);
    if (prevRunMode == STOPPED) {
        // The background thread wasn't running, so set the flag back to STOPPED
        mRunMode = STOPPED;
    } else if (prevRunMode & STOPPING) {
        LOG(ERROR) << "stopStream called while stream is already stopping.  "
                   << "Reentrancy is not supported!";
        return;
    } else {
        // Block until the background thread is stopped
        if (mCaptureThread.joinable()) {
            // Removes all pending messages and awake the looper
            mLooper->removeMessages(this, StreamEvent::PERIODIC);
            mLooper->wake();
            mCaptureThread.join();
        } else {
            LOG(ERROR) << "Capture thread is not joinable";
        }

        mRunMode = STOPPED;
        LOG(DEBUG) << "Capture thread stopped.";
    }

    // Drop our reference to the frame delivery callback interface
    mCallback = nullptr;
}


void VideoCapture::markFrameReady() {
    mFrameReady = true;
};


bool VideoCapture::returnFrame() {
    // We're using a single buffer synchronousely so just need to set
    // mFrameReady as false.
    mFrameReady = false;

    return true;
}


// This runs on a background thread to receive and dispatch video frames
void VideoCapture::collectFrames() {
    const size_t kBufferSize = mStride * mHeight;
    const std::filesystem::directory_iterator end_iter;
    bool created = false;
    while (!created && mSrcIter != end_iter) {
        if (mSrcIter->path().extension() != ".bin") {
            LOG(DEBUG) << "Unsupported file extension.  Ignores "
                       << mSrcIter->path().filename();
            ++mSrcIter;
            continue;
        }

        LOG(INFO) << "Synthesizing a frame from " << mSrcIter->path();
        std::ifstream fin(mSrcIter->path(), ios::in | ios::binary);
        if (fin.is_open()) {
            fin.read(mPixelBuffer, kBufferSize);
            if (fin.gcount() != kBufferSize) {
                LOG(WARNING) << mSrcIter->path() << " contains less than expected.";
            }
            fin.close();

            created = true;
        } else {
            PLOG(ERROR) << "Failed to open " << mSrcIter->path();
        }

        // Moves to next file
        ++mSrcIter;
    }

    // This metadata is currently ignored in the callback.
    mBufferInfo.index  = 0;
    mBufferInfo.type   = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    mBufferInfo.memory = V4L2_MEMORY_MMAP;
    mBufferInfo.length = kBufferSize;
    mBufferInfo.m.offset = 0;

    int64_t now = nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));
    mBufferInfo.timestamp.tv_sec = (time_t)(now / 1000LL);
    mBufferInfo.timestamp.tv_usec = (suseconds_t)((now % 1000LL) * 1000LL);

    if (mCallback != nullptr) {
        mCallback(this, &mBufferInfo, mPixelBuffer);
    }

    // If the last file is processed, reset the iterator to the first file.
    if (mSrcIter == end_iter) {
        LOG(DEBUG) << "Rewinds the iterator to the beginning.";
        mSrcIter = filesystem::directory_iterator(mSourceDir);
    }
}


int VideoCapture::setParameter(v4l2_control& /*control*/) {
    // Not implemented yet.
    return -ENOSYS;
}


int VideoCapture::getParameter(v4l2_control& /*control*/) {
    // Not implemented yet.
    return -ENOSYS;
}


void VideoCapture::handleMessage(const android::Message& message) {
    const auto received = static_cast<StreamEvent>(message.what);
    switch (received) {
        case StreamEvent::PERIODIC: {
            // Generates a new frame and send
            collectFrames();

            // Updates a timestamp and arms a message for next frame
            mLastTimeFrameSent = systemTime(SYSTEM_TIME_MONOTONIC);
            const auto next = mLastTimeFrameSent + mDesiredFrameInterval.count();
            mLooper->sendMessageAtTime(next, this, received);
            break;
        }

        case StreamEvent::STOP: {
            // Stopping a frame generation
            LOG(INFO) << "Stop generating frames";
            break;
        }

        default:
            LOG(WARNING) << "Unknown event is received: " << received;
            break;
    }
}

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android
