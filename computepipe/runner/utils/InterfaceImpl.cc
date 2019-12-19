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
// limitations under the License.c

#define LOG_TAG "RunnerIpcInterface"

#include "InterfaceImpl.h"

#include <android-base/logging.h>
#include "PacketDescriptor.pb.h"
#include "PipeOptionsConverter.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner_utils {
namespace {

using ::aidl::android::automotive::computepipe::runner::IPipeStateCallback;
using ::aidl::android::automotive::computepipe::runner::IPipeStream;
using ::aidl::android::automotive::computepipe::runner::PipeDescriptor;
using ::aidl::android::automotive::computepipe::runner::PipeState;
using ::ndk::ScopedAStatus;

ScopedAStatus ToNdkStatus(Status status) {
    switch (status) {
        case SUCCESS:
            return ScopedAStatus::ok();
        case INTERNAL_ERROR:
            return ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED);
        case INVALID_ARGUMENT:
            return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        case FATAL_ERROR:
        default:
            return ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED);
    }
}

PipeState ToAidlState(GraphState state) {
    switch (state) {
        case RESET:
            return PipeState::RESET;
        case CONFIG_DONE:
            return PipeState::CONFIG_DONE;
        case RUNNING:
            return PipeState::RUNNING;
        case DONE:
            return PipeState::DONE;
        case ERR_HALT:
        default:
            return PipeState::ERR_HALT;
    }
}

void deathNotifier(void* cookie) {
    InterfaceImpl* iface = static_cast<InterfaceImpl*>(cookie);
    iface->clientDied();
}

}  // namespace

// Thread-safe function to deliver new packets to client.
Status InterfaceImpl::newPacketNotification(int32_t streamId,
                                            const std::shared_ptr<MemHandle>& packetHandle) {
    // TODO(146464279) implement.
    (void)packetHandle;
    return Status::SUCCESS;
}

Status InterfaceImpl::stateUpdateNotification(const GraphState newState) {
    (void)mClientStateChangeCallback->handleState(ToAidlState(newState));
    return Status::SUCCESS;
}

ScopedAStatus InterfaceImpl::getPipeDescriptor(PipeDescriptor* _aidl_return) {
    if (_aidl_return == nullptr) {
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    *_aidl_return = OptionsToPipeDesciptor(mGraphOptions);
    return ScopedAStatus::ok();
}

ScopedAStatus InterfaceImpl::setPipeInputSource(int32_t configId) {
    if (!isClientInitDone()) {
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    proto::ConfigurationCommand configurationCommand;
    configurationCommand.mutable_set_input_source()->set_source_id(configId);

    Status status = mRunnerInterfaceCallbacks.mProcessConfigurationCommand(configurationCommand);
    return ToNdkStatus(status);
}

ScopedAStatus InterfaceImpl::setPipeOffloadOptions(int32_t configId) {
    if (!isClientInitDone()) {
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    proto::ConfigurationCommand configurationCommand;
    configurationCommand.mutable_set_offload_offload()->set_offload_option_id(configId);

    Status status = mRunnerInterfaceCallbacks.mProcessConfigurationCommand(configurationCommand);
    return ToNdkStatus(status);
}

ScopedAStatus InterfaceImpl::setPipeTermination(int32_t configId) {
    if (!isClientInitDone()) {
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    proto::ConfigurationCommand configurationCommand;
    configurationCommand.mutable_set_termination_option()->set_termination_option_id(configId);

    Status status = mRunnerInterfaceCallbacks.mProcessConfigurationCommand(configurationCommand);
    return ToNdkStatus(status);
}

ScopedAStatus InterfaceImpl::init(const std::shared_ptr<IPipeStateCallback>& stateCb) {
    if (isClientInitDone()) {
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    AIBinder_DeathRecipient* recipient = AIBinder_DeathRecipient_new(&deathNotifier);
    AIBinder_linkToDeath(stateCb->asBinder().get(), recipient, this);

    mClientStateChangeCallback = stateCb;
    return ScopedAStatus::ok();
}

bool InterfaceImpl::isClientInitDone() {
    if (mClientStateChangeCallback == nullptr) {
        return false;
    }
    return true;
}

void InterfaceImpl::clientDied() {
    LOG(INFO) << "Client has died";
    releaseRunner();
}

ScopedAStatus InterfaceImpl::setPipeOutputConfig(int32_t streamId, int32_t maxInFlightCount,
                                                 const std::shared_ptr<IPipeStream>& handler) {
    if (!isClientInitDone()) {
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    if (mPacketHandlers.find(streamId) != mPacketHandlers.end()) {
        LOG(INFO) << "Handler for stream id " << streamId
                  << " has already"
                     " been registered.";
        return ToNdkStatus(INVALID_ARGUMENT);
    }

    mPacketHandlers.insert(std::pair<int, std::shared_ptr<IPipeStream>>(streamId, handler));

    proto::ConfigurationCommand configurationCommand;
    configurationCommand.mutable_set_output_stream()->set_stream_id(streamId);
    configurationCommand.mutable_set_output_stream()->set_max_inflight_packets_count(
        maxInFlightCount);
    Status status = mRunnerInterfaceCallbacks.mProcessConfigurationCommand(configurationCommand);

    if (status != SUCCESS) {
        LOG(INFO) << "Failed to register handler for stream id " << streamId;
        mPacketHandlers.erase(streamId);
    }
    return ToNdkStatus(status);
}

ScopedAStatus InterfaceImpl::applyPipeConfigs() {
    if (!isClientInitDone()) {
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    proto::ControlCommand controlCommand;
    *controlCommand.mutable_apply_configs() = proto::ApplyConfigs();

    Status status = mRunnerInterfaceCallbacks.mProcessControlCommand(controlCommand);
    return ToNdkStatus(status);
}

ScopedAStatus InterfaceImpl::startPipe() {
    proto::ControlCommand controlCommand;
    *controlCommand.mutable_start_graph() = proto::StartGraph();

    Status status = mRunnerInterfaceCallbacks.mProcessControlCommand(controlCommand);
    return ToNdkStatus(status);
}

ScopedAStatus InterfaceImpl::stopPipe() {
    proto::ControlCommand controlCommand;
    *controlCommand.mutable_stop_graph() = proto::StopGraph();

    Status status = mRunnerInterfaceCallbacks.mProcessControlCommand(controlCommand);
    return ToNdkStatus(status);
}

ScopedAStatus InterfaceImpl::doneWithPacket(int32_t id) {
    // TODO(146464279) implement.
    return ScopedAStatus::ok();
}

ndk::ScopedAStatus InterfaceImpl::getPipeDebugger(
    std::shared_ptr<aidl::android::automotive::computepipe::runner::IPipeDebugger>* _aidl_return) {
    // TODO(146464281) implement.
    return ScopedAStatus::ok();
}

ndk::ScopedAStatus InterfaceImpl::releaseRunner() {
    proto::ControlCommand controlCommand;
    *controlCommand.mutable_death_notification() = proto::DeathNotification();

    Status status = mRunnerInterfaceCallbacks.mProcessControlCommand(controlCommand);

    mClientStateChangeCallback = nullptr;
    mPacketHandlers.clear();
    return ToNdkStatus(status);
}

}  // namespace runner_utils
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
