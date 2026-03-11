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

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <vndk/hardware_buffer.h>

#include <fuzzer/FuzzedDataProvider.h>
#include "EventGenerator.h"
#include "InputFrame.h"
#include "MockEngine.h"
#include "OutputConfig.pb.h"
#include "PixelFormatUtils.h"
#include "PixelStreamManager.h"
#include "RunnerComponent.h"
#include "StreamEngineInterface.h"
#include "StreamManager.h"
#include "src/libfuzzer/libfuzzer_macro.h"
#include "types/Status.h"

using ::android::automotive::computepipe::runner::generator::DefaultEvent;
using ::testing::Return;

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {
namespace stream_manager {
namespace {

enum State {
    RESET = 0,
    RUN,
    STOP_WITH_FLUSH,
    STOP_IMMEDIATE,
    QUEUE_PACKET,
    FREE_PACKET,
    CLONE_PACKET
};

std::pair<std::shared_ptr<MockEngine>, std::unique_ptr<StreamManager>> CreateStreamManagerAndEngine(
        int maxInFlightPackets) {
    StreamManagerFactory factory;
    proto::OutputConfig outputConfig;
    outputConfig.set_type(proto::PacketType::PIXEL_DATA);
    outputConfig.set_stream_name("pixel_stream");
    std::shared_ptr<MockEngine> mockEngine = std::make_shared<MockEngine>();
    std::unique_ptr<StreamManager> manager =
            factory.getStreamManager(outputConfig, mockEngine, maxInFlightPackets);

    return std::pair(mockEngine, std::move(manager));
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size < sizeof(uint32_t)) {
        return 0;
    }

    FuzzedDataProvider fdp(data, size);
    // Cap maxInFlightPackets to prevent resource exhaustion and huge allocations
    uint32_t maxInFlightPackets = fdp.ConsumeIntegralInRange<uint32_t>(1, 100);
    auto [mockEngine, manager] = CreateStreamManagerAndEngine(maxInFlightPackets);
    std::vector<uint8_t> pixelData(16 * 16 * 3, 100);
    InputFrame frame(16, 16, PixelFormat::RGB, 16 * 3, &pixelData[0]);

    // Use a shared pointer to store the handle to avoid UAF in detached threads
    auto lastHandle = std::make_shared<std::shared_ptr<MemHandle>>(nullptr);
    auto handleMutex = std::make_shared<std::mutex>();

    EXPECT_CALL((*mockEngine), dispatchPacket)
            .Times(testing::AnyNumber())
            .WillRepeatedly([lastHandle, handleMutex](std::shared_ptr<MemHandle> handle) {
                std::lock_guard<std::mutex> lock(*handleMutex);
                *lastHandle = handle;
                return Status::SUCCESS;
            });

    while (fdp.remaining_bytes() > 0) {
        uint8_t state = fdp.ConsumeIntegralInRange<uint8_t>(RESET, CLONE_PACKET);
        DefaultEvent e = DefaultEvent::generateEntryEvent(static_cast<DefaultEvent::Phase>(state));

        switch (state) {
            case RESET:
            case RUN: {
                manager->handleExecutionPhase(e);
                break;
            }
            case STOP_WITH_FLUSH: {
                EXPECT_CALL((*mockEngine), notifyEndOfStream).Times(testing::AtMost(1));
                manager->handleStopWithFlushPhase(e);
                break;
            }
            case STOP_IMMEDIATE: {
                EXPECT_CALL((*mockEngine), notifyEndOfStream).Times(testing::AtMost(1));
                manager->handleStopImmediatePhase(e);
                break;
            }
            case QUEUE_PACKET: {
                manager->queuePacket(frame, rand());
                break;
            }
            case FREE_PACKET: {
                std::shared_ptr<MemHandle> handle;
                {
                    std::lock_guard<std::mutex> lock(*handleMutex);
                    handle = *lastHandle;
                }
                if (handle != nullptr) {
                    manager->freePacket(handle->getBufferId());
                }
                break;
            }
            case CLONE_PACKET: {
                std::shared_ptr<MemHandle> handle;
                {
                    std::lock_guard<std::mutex> lock(*handleMutex);
                    handle = *lastHandle;
                }
                if (handle != nullptr) {
                    manager->clonePacket(handle);
                }
                break;
            }
        }
    }

    return 0;
}

}  // namespace
}  // namespace stream_manager
}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
