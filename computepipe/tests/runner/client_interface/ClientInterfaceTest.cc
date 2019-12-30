// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <aidl/android/automotive/computepipe/registry/BnClientInfo.h>
#include <aidl/android/automotive/computepipe/registry/IPipeQuery.h>
#include <aidl/android/automotive/computepipe/registry/IPipeRegistration.h>
#include <aidl/android/automotive/computepipe/runner/BnPipeStateCallback.h>
#include <aidl/android/automotive/computepipe/runner/BnPipeStream.h>
#include <aidl/android/automotive/computepipe/runner/PipeState.h>

#include <android/binder_manager.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <utility>

#include "ConfigurationCommand.pb.h"
#include "ControlCommand.pb.h"
#include "MemHandle.h"
#include "MockMemHandle.h"
#include "Options.pb.h"
#include "runner/utils/RunnerInterface.h"
#include "runner/utils/RunnerInterfaceCallbacks.h"
#include "types/Status.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace {

using ::aidl::android::automotive::computepipe::registry::BnClientInfo;
using ::aidl::android::automotive::computepipe::registry::IPipeQuery;
using ::aidl::android::automotive::computepipe::runner::BnPipeStateCallback;
using ::aidl::android::automotive::computepipe::runner::BnPipeStream;
using ::aidl::android::automotive::computepipe::runner::IPipeRunner;
using ::aidl::android::automotive::computepipe::runner::IPipeStateCallback;
using ::aidl::android::automotive::computepipe::runner::PacketDescriptor;
using ::aidl::android::automotive::computepipe::runner::PipeState;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;
using ::testing::AtLeast;
using ::testing::Return;

const char kRegistryInterfaceName[] = "router";

class RunnerCallbacks {
  public:
    Status ControlCommandCallback(const proto::ControlCommand& command) {
        mLastControlCommand = command;
        return mStatus;
    }

    Status ConfigurationCommandCallback(const proto::ConfigurationCommand& command) {
        mLastConfigurationCommand = command;
        return mStatus;
    }

    Status ReleasePacketNotification(const std::shared_ptr<MemHandle>& packet) {
        mLastPacket = packet;
        return mStatus;
    }

    runner_utils::RunnerInterfaceCallbacks GetCallbackObject() {
        using std::placeholders::_1;
        std::function<Status(const proto::ControlCommand&)> controlCb =
            std::bind(&RunnerCallbacks::ControlCommandCallback, this, _1);
        std::function<Status(const proto::ConfigurationCommand&)> configCb =
            std::bind(&RunnerCallbacks::ConfigurationCommandCallback, this, _1);
        std::function<Status(const std::shared_ptr<MemHandle>&)> packetCb =
            std::bind(&RunnerCallbacks::ReleasePacketNotification, this, _1);
        return runner_utils::RunnerInterfaceCallbacks(controlCb, configCb, packetCb);
    }

    void SetReturnStatus(Status status) {
        mStatus = status;
    }

    proto::ControlCommand mLastControlCommand;
    proto::ConfigurationCommand mLastConfigurationCommand;
    std::shared_ptr<MemHandle> mLastPacket = nullptr;
    Status mStatus = Status::SUCCESS;
};

class StateChangeCallback : public BnPipeStateCallback {
  public:
    ScopedAStatus handleState(PipeState state) {
        mState = state;
        return ScopedAStatus::ok();
    }
    PipeState mState = PipeState::RESET;
};

class StreamCallback : public BnPipeStream {
  public:
    ScopedAStatus deliverPacket(const PacketDescriptor& in_packet) override {
        data = in_packet.data;
        timestamp = in_packet.sourceTimeStampMillis;
        return ScopedAStatus::ok();
    }
    std::string data;
    uint64_t timestamp;
};

class ClientInfo : public BnClientInfo {
  public:
    ScopedAStatus getClientId(int32_t* _aidl_return) {
        if (_aidl_return) {
            *_aidl_return = 0;
            return ScopedAStatus::ok();
        }
        return ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED);
    }
};

class ClientInterface : public ::testing::Test {
  protected:
    void SetUp() override {
        const std::string graphName = "graph1";
        proto::Options options;
        options.set_graph_name(graphName);
        mRunnerInterface = std::make_unique<runner_utils::RunnerInterface>(
            options, mCallbacks.GetCallbackObject());

        // Register the instance with router.
        EXPECT_EQ(mRunnerInterface->init(), Status::SUCCESS);

        // Init is not a blocking call, so sleep for 3 seconds to allow the runner to register with
        // router.
        sleep(3);

        // Retrieve router query instance from service manager.
        std::string instanceName =
            std::string() + IPipeQuery::descriptor + "/" + kRegistryInterfaceName;
        ndk::SpAIBinder binder(AServiceManager_getService(instanceName.c_str()));
        ASSERT_TRUE(binder.get() != nullptr);
        std::shared_ptr<IPipeQuery> queryService = IPipeQuery::fromBinder(binder);

        // Retrieve pipe runner instance from the router.
        std::shared_ptr<ClientInfo> clientInfo = ndk::SharedRefBase::make<ClientInfo>();
        ASSERT_TRUE(queryService->getPipeRunner(graphName, clientInfo, &mPipeRunner).isOk());
    }

    RunnerCallbacks mCallbacks;
    std::shared_ptr<runner_utils::RunnerInterface> mRunnerInterface = nullptr;
    std::shared_ptr<IPipeRunner> mPipeRunner = nullptr;
};

TEST_F(ClientInterface, TestSetConfiguration) {
    // Configure runner to return success.
    mCallbacks.SetReturnStatus(Status::SUCCESS);

    // Initialize pipe runner.
    std::shared_ptr<StateChangeCallback> stateCallback =
        ndk::SharedRefBase::make<StateChangeCallback>();
    EXPECT_TRUE(mPipeRunner->init(stateCallback).isOk());

    // Test that set input source returns ok status.
    EXPECT_TRUE(mPipeRunner->setPipeInputSource(1).isOk());
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_input_source(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_input_source().source_id(), 1);

    // Test that set offload option returns ok status.
    EXPECT_TRUE(mPipeRunner->setPipeOffloadOptions(5).isOk());
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_offload_offload(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_offload_offload().offload_option_id(), 5);

    // Test that set termination option returns ok status.
    EXPECT_TRUE(mPipeRunner->setPipeTermination(3).isOk());
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_termination_option(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_termination_option().termination_option_id(),
              3);

    // Test that set output callback returns ok status.
    std::shared_ptr<StreamCallback> streamCb = ndk::SharedRefBase::make<StreamCallback>();
    EXPECT_TRUE(mPipeRunner->setPipeOutputConfig(0, 10, streamCb).isOk());
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_output_stream(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_output_stream().stream_id(), 0);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_output_stream().max_inflight_packets_count(),
              10);

    // Release runner here. This should remove registry entry from router registry.
    mRunnerInterface.reset();
}

TEST_F(ClientInterface, TestSetConfigurationError) {
    ScopedAStatus status;

    // Configure runner to return error.
    mCallbacks.SetReturnStatus(Status::INTERNAL_ERROR);

    // Initialize pipe runner.
    std::shared_ptr<StateChangeCallback> stateCallback =
        ndk::SharedRefBase::make<StateChangeCallback>();
    EXPECT_TRUE(mPipeRunner->init(stateCallback).isOk());

    // Test that set input source returns error status.
    status = mPipeRunner->setPipeInputSource(1);
    EXPECT_EQ(status.getExceptionCode(), EX_TRANSACTION_FAILED);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_input_source(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_input_source().source_id(), 1);

    // Test that set offload option returns error status.
    status = mPipeRunner->setPipeOffloadOptions(5);
    EXPECT_EQ(status.getExceptionCode(), EX_TRANSACTION_FAILED);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_offload_offload(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_offload_offload().offload_option_id(), 5);

    // Test that set termination option returns error status.
    status = mPipeRunner->setPipeTermination(3);
    EXPECT_EQ(status.getExceptionCode(), EX_TRANSACTION_FAILED);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_termination_option(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_termination_option().termination_option_id(),
              3);

    // Test that set output callback returns error status.
    std::shared_ptr<StreamCallback> streamCb = ndk::SharedRefBase::make<StreamCallback>();
    status = mPipeRunner->setPipeOutputConfig(0, 10, streamCb);
    EXPECT_EQ(status.getExceptionCode(), EX_TRANSACTION_FAILED);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_output_stream(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_output_stream().stream_id(), 0);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_output_stream().max_inflight_packets_count(),
              10);

    // Release runner here. This should remove registry entry from router registry.
    mRunnerInterface.reset();
}

TEST_F(ClientInterface, TestControlCommands) {
    // Configure runner to return success.
    mCallbacks.SetReturnStatus(Status::SUCCESS);

    // Initialize pipe runner.
    std::shared_ptr<StateChangeCallback> stateCallback =
        ndk::SharedRefBase::make<StateChangeCallback>();
    EXPECT_TRUE(mPipeRunner->init(stateCallback).isOk());

    // Test that apply-configs api returns ok status.
    EXPECT_TRUE(mPipeRunner->applyPipeConfigs().isOk());
    EXPECT_EQ(mCallbacks.mLastControlCommand.has_apply_configs(), true);

    // Test that set start graph api returns ok status.
    EXPECT_TRUE(mPipeRunner->startPipe().isOk());
    EXPECT_EQ(mCallbacks.mLastControlCommand.has_start_graph(), true);

    // Test that set stop graph api returns ok status.
    EXPECT_TRUE(mPipeRunner->stopPipe().isOk());
    EXPECT_EQ(mCallbacks.mLastControlCommand.has_stop_graph(), true);

    // Release runner here. This should remove registry entry from router registry.
    mRunnerInterface.reset();
}

TEST_F(ClientInterface, TestControlCommandsFailure) {
    ScopedAStatus status;

    // Configure runner to return error status.
    mCallbacks.SetReturnStatus(Status::INTERNAL_ERROR);

    // Initialize pipe runner.
    std::shared_ptr<StateChangeCallback> stateCallback =
        ndk::SharedRefBase::make<StateChangeCallback>();
    EXPECT_TRUE(mPipeRunner->init(stateCallback).isOk());

    // Test that apply-configs api returns error status.
    status = mPipeRunner->applyPipeConfigs();
    EXPECT_EQ(status.getExceptionCode(), EX_TRANSACTION_FAILED);
    EXPECT_EQ(mCallbacks.mLastControlCommand.has_apply_configs(), true);

    // Test that start graph api returns error status.
    status = mPipeRunner->startPipe();
    EXPECT_EQ(status.getExceptionCode(), EX_TRANSACTION_FAILED);
    EXPECT_EQ(mCallbacks.mLastControlCommand.has_start_graph(), true);

    // Test that stop graph api returns error status.
    status = mPipeRunner->stopPipe();
    EXPECT_EQ(status.getExceptionCode(), EX_TRANSACTION_FAILED);
    EXPECT_EQ(mCallbacks.mLastControlCommand.has_stop_graph(), true);

    // Release runner here. This should remove registry entry from router registry.
    mRunnerInterface.reset();
}

TEST_F(ClientInterface, TestFailureWithoutInit) {
    mCallbacks.SetReturnStatus(Status::SUCCESS);

    // Pipe runner is not initalized here, test that a configuration command returns error status.
    ScopedAStatus status;
    status = mPipeRunner->setPipeInputSource(1);
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_STATE);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_input_source(), false);

    // Test that a control command returns error status.
    status = mPipeRunner->applyPipeConfigs();
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_STATE);
    EXPECT_EQ(mCallbacks.mLastControlCommand.has_apply_configs(), false);
}

TEST_F(ClientInterface, TestStateChangeNotification) {
    // Create RunnerInterface instance.
    mCallbacks.SetReturnStatus(Status::SUCCESS);

    // Initialize pipe runner.
    std::shared_ptr<StateChangeCallback> stateCallback =
        ndk::SharedRefBase::make<StateChangeCallback>();
    EXPECT_TRUE(mPipeRunner->init(stateCallback).isOk());

    // Test that when runner interface is notified of a new state, client callback is invoked with
    // correct state.
    GraphState state = RUNNING;
    EXPECT_EQ(mRunnerInterface->stateUpdateNotification(state), Status::SUCCESS);
    EXPECT_EQ(stateCallback->mState, PipeState::RUNNING);
}

TEST_F(ClientInterface, TestPacketDelivery) {
    // Configure runner to return success state.
    mCallbacks.SetReturnStatus(Status::SUCCESS);

    // Initialize pipe runner.
    std::shared_ptr<StateChangeCallback> stateCallback =
        ndk::SharedRefBase::make<StateChangeCallback>();
    EXPECT_TRUE(mPipeRunner->init(stateCallback).isOk());

    // Set callback for stream id 0.
    std::shared_ptr<StreamCallback> streamCb = ndk::SharedRefBase::make<StreamCallback>();
    EXPECT_TRUE(mPipeRunner->setPipeOutputConfig(0, 10, streamCb).isOk());
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.has_set_output_stream(), true);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_output_stream().stream_id(), 0);
    EXPECT_EQ(mCallbacks.mLastConfigurationCommand.set_output_stream().max_inflight_packets_count(),
              10);

    // Send a packet to client and verify the packet.
    std::shared_ptr<tests::MockMemHandle> packet = std::make_unique<tests::MockMemHandle>();
    uint64_t timestamp = 100;
    const std::string testData = "Test String.";
    EXPECT_CALL(*packet, getType()).Times(AtLeast(1))
        .WillRepeatedly(Return(proto::PacketType::SEMANTIC_DATA));
    EXPECT_CALL(*packet, getTimeStamp()).Times(AtLeast(1))
        .WillRepeatedly(Return(timestamp));
    EXPECT_CALL(*packet, getSize()).Times(AtLeast(1))
        .WillRepeatedly(Return(testData.size()));
    EXPECT_CALL(*packet, getData()).Times(AtLeast(1))
        .WillRepeatedly(Return(testData.c_str()));
    EXPECT_EQ(mRunnerInterface->newPacketNotification(
        0, static_cast<std::shared_ptr<MemHandle>>(packet)), Status::SUCCESS);
    EXPECT_EQ(streamCb->data, packet->getData());
    EXPECT_EQ(streamCb->timestamp, packet->getTimeStamp());
}

}  // namespace
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
