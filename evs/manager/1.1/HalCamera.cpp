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

#include "HalCamera.h"
#include "VirtualCamera.h"
#include "Enumerator.h"

#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>


namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {


// TODO(changyeon):
// We need to hook up death monitoring to detect stream death so we can attempt a reconnect


sp<VirtualCamera> HalCamera::makeVirtualCamera() {

    // Create the client camera interface object
    sp<VirtualCamera> client = new VirtualCamera(this);
    if (client == nullptr) {
        ALOGE("Failed to create client camera object");
        return nullptr;
    }

    // Make sure we have enough buffers available for all our clients
    if (!changeFramesInFlight(client->getAllowedBuffers())) {
        // Gah!  We couldn't get enough buffers, so we can't support this client
        // Null the pointer, dropping our reference, thus destroying the client object
        client = nullptr;
        return nullptr;
    }

    // Add this client to our ownership list via weak pointer
    mClients.push_back(client);

    // Return the strong pointer to the client
    return client;
}


void HalCamera::disownVirtualCamera(sp<VirtualCamera> virtualCamera) {
    // Ignore calls with null pointers
    if (virtualCamera.get() == nullptr) {
        ALOGW("Ignoring disownVirtualCamera call with null pointer");
        return;
    }

    // Make sure the virtual camera's stream is stopped
    virtualCamera->stopVideoStream();

    // Remove the virtual camera from our client list
    unsigned clientCount = mClients.size();
    mClients.remove(virtualCamera);
    if (clientCount != mClients.size() + 1) {
        ALOGE("Couldn't find camera in our client list to remove it");
    }
    virtualCamera->shutdown();

    // Recompute the number of buffers required with the target camera removed from the list
    if (!changeFramesInFlight(0)) {
        ALOGE("Error when trying to reduce the in flight buffer count");
    }
}


bool HalCamera::changeFramesInFlight(int delta) {
    // Walk all our clients and count their currently required frames
    unsigned bufferCount = 0;
    for (auto&& client :  mClients) {
        sp<VirtualCamera> virtCam = client.promote();
        if (virtCam != nullptr) {
            bufferCount += virtCam->getAllowedBuffers();
        }
    }

    // Add the requested delta
    bufferCount += delta;

    // Never drop below 1 buffer -- even if all client cameras get closed
    if (bufferCount < 1) {
        bufferCount = 1;
    }

    // Ask the hardware for the resulting buffer count
    Return<EvsResult> result = mHwCamera->setMaxFramesInFlight(bufferCount);
    bool success = (result.isOk() && result == EvsResult::OK);

    // Update the size of our array of outstanding frame records
    if (success) {
        std::vector<FrameRecord> newRecords;
        newRecords.reserve(bufferCount);

        // Copy and compact the old records that are still active
        for (const auto& rec : mFrames) {
            if (rec.refCount > 0) {
                newRecords.emplace_back(rec);
            }
        }
        if (newRecords.size() > (unsigned)bufferCount) {
            ALOGW("We found more frames in use than requested.");
        }

        mFrames.swap(newRecords);
    }

    return success;
}


Return<EvsResult> HalCamera::clientStreamStarting() {
    Return<EvsResult> result = EvsResult::OK;

    if (mStreamState == STOPPED) {
        mStreamState = RUNNING;
        result = mHwCamera->startVideoStream(this);
    }

    return result;
}


void HalCamera::clientStreamEnding() {
    // Do we still have a running client?
    bool stillRunning = false;
    for (auto&& client : mClients) {
        sp<VirtualCamera> virtCam = client.promote();
        if (virtCam != nullptr) {
            stillRunning |= virtCam->isStreaming();
        }
    }

    // If not, then stop the hardware stream
    if (!stillRunning) {
        mStreamState = STOPPED;
        mHwCamera->stopVideoStream();
    }
}


Return<void> HalCamera::doneWithFrame(const BufferDesc_1_0& buffer) {
    // Find this frame in our list of outstanding frames
    unsigned i;
    for (i = 0; i < mFrames.size(); i++) {
        if (mFrames[i].frameId == buffer.bufferId) {
            break;
        }
    }
    if (i == mFrames.size()) {
        ALOGE("We got a frame back with an ID we don't recognize!");
    } else {
        // Are there still clients using this buffer?
        mFrames[i].refCount--;
        if (mFrames[i].refCount <= 0) {
            // Since all our clients are done with this buffer, return it to the device layer
            mHwCamera->doneWithFrame(buffer);
        }
    }

    return Void();
}


Return<void> HalCamera::doneWithFrame(const BufferDesc_1_1& buffer) {
    // Find this frame in our list of outstanding frames
    unsigned i;
    for (i = 0; i < mFrames.size(); i++) {
        if (mFrames[i].frameId == buffer.bufferId) {
            break;
        }
    }
    if (i == mFrames.size()) {
        ALOGE("We got a frame back with an ID we don't recognize!");
    } else {
        // Are there still clients using this buffer?
        mFrames[i].refCount--;
        if (mFrames[i].refCount <= 0) {
            // Since all our clients are done with this buffer, return it to the device layer
            mHwCamera->doneWithFrame_1_1(buffer);
        }
    }

    return Void();
}


Return<void> HalCamera::deliverFrame(const BufferDesc_1_0& buffer) {
    /* Frames are delivered via notifyEvent callback for clients that implement
     * IEvsCameraStream v1.1 interfaces and therefore this method must not be
     * used.
     */
    ALOGI("A delivered frame from EVS v1.0 HW module is rejected.");
    mHwCamera->doneWithFrame(buffer);

    return Void();
}


Return<void> HalCamera::notifyEvent(const EvsEvent& event) {
    auto type = event.getDiscriminator();
    if (type == EvsEvent::hidl_discriminator::info) {
        ALOGD("Received an event id: %u", event.info());
        if(event.info() == EvsEventType::STREAM_STOPPED) {
            // This event happens only when there is no more active client.
            if (mStreamState != STOPPING) {
                ALOGW("Stream stopped unexpectedly");
            }

            mStreamState = STOPPED;
        }

        // Forward all other events to the clients
        for (auto&& client : mClients) {
            sp<VirtualCamera> vCam = client.promote();
            if (vCam != nullptr) {
                if (!vCam->notifyEvent(event)) {
                    ALOGI("Failed to forward an event");
                }
            }
        }
    } else {
        ALOGD("Received a frame");
        unsigned frameDeliveries = 0;
        for (auto&& client : mClients) {
            sp<VirtualCamera> vCam = client.promote();
            if (vCam != nullptr) {
                if (vCam->notifyEvent(event)) {
                    ++frameDeliveries;
                }
            }
        }

        if (frameDeliveries < 1) {
            // If none of our clients could accept the frame, then return it
            // right away.
            ALOGI("Trivially rejecting frame with no acceptance");
            mHwCamera->doneWithFrame_1_1(event.buffer());
        } else {
            // Add an entry for this frame in our tracking list.
            unsigned i;
            for (i = 0; i < mFrames.size(); ++i) {
                if (mFrames[i].refCount == 0) {
                    break;
                }
            }

            if (i == mFrames.size()) {
                mFrames.emplace_back(event.buffer().bufferId);
            } else {
                mFrames[i].frameId = event.buffer().bufferId;
            }
            mFrames[i].refCount = frameDeliveries;
        }
    }

    return Void();
}


} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android
