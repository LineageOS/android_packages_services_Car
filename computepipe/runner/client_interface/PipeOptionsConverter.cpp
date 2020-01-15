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

#include "PipeOptionsConverter.h"

#include "aidl/android/automotive/computepipe/runner/PipeInputConfigInputType.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {
namespace client_interface {
namespace aidl_client {

using ::aidl::android::automotive::computepipe::runner::PipeDescriptor;
using ::aidl::android::automotive::computepipe::runner::PipeInputConfig;
using ::aidl::android::automotive::computepipe::runner::PipeInputConfigFormatType;
using ::aidl::android::automotive::computepipe::runner::PipeInputConfigInputType;
using ::aidl::android::automotive::computepipe::runner::PipeOffloadConfig;
using ::aidl::android::automotive::computepipe::runner::PipeOffloadConfigOffloadType;
using ::aidl::android::automotive::computepipe::runner::PipeOutputConfig;
using ::aidl::android::automotive::computepipe::runner::PipeOutputConfigPacketType;
using ::aidl::android::automotive::computepipe::runner::PipeTerminationConfig;
using ::aidl::android::automotive::computepipe::runner::PipeTerminationConfigTerminationType;

namespace {

PipeInputConfigInputType ConvertInputType(proto::InputConfig_InputType type) {
    switch (type) {
        case proto::InputConfig_InputType_DRIVER_VIEW_CAMERA:
            return PipeInputConfigInputType::DRIVER_VIEW_CAMERA;
        case proto::InputConfig_InputType_OCCUPANT_VIEW_CAMERA:
            return PipeInputConfigInputType::OCCUPANT_VIEW_CAMERA;
        case proto::InputConfig_InputType_EXTERNAL_CAMERA:
            return PipeInputConfigInputType::EXTERNAL_CAMERA;
        case proto::InputConfig_InputType_SURROUND_VIEW_CAMERA:
            return PipeInputConfigInputType::SURROUND_VIEW_CAMERA;
        case proto::InputConfig_InputType_VIDEO_FILE:
            return PipeInputConfigInputType::VIDEO_FILE;
        case proto::InputConfig_InputType_IMAGE_FILES:
            return PipeInputConfigInputType::IMAGE_FILES;
    }
}

PipeInputConfigFormatType ConvertInputFormat(proto::InputConfig_FormatType type) {
    switch (type) {
        case proto::InputConfig_FormatType_RGB:
            return PipeInputConfigFormatType::RGB;
        case proto::InputConfig_FormatType_NIR:
            return PipeInputConfigFormatType::NIR;
        case proto::InputConfig_FormatType_NIR_DEPTH:
            return PipeInputConfigFormatType::NIR_DEPTH;
    }
}

PipeOffloadConfigOffloadType ConvertOffloadType(proto::OffloadOption_OffloadType type) {
    switch (type) {
        case proto::OffloadOption_OffloadType_CPU:
            return PipeOffloadConfigOffloadType::CPU;
        case proto::OffloadOption_OffloadType_GPU:
            return PipeOffloadConfigOffloadType::GPU;
        case proto::OffloadOption_OffloadType_NEURAL_ENGINE:
            return PipeOffloadConfigOffloadType::NEURAL_ENGINE;
        case proto::OffloadOption_OffloadType_CV_ENGINE:
            return PipeOffloadConfigOffloadType::CV_ENGINE;
    }
}

PipeOutputConfigPacketType ConvertOutputType(proto::PacketType type) {
    switch (type) {
        case proto::PacketType::SEMANTIC_DATA:
            return PipeOutputConfigPacketType::SEMANTIC_DATA;
        case proto::PacketType::PIXEL_DATA:
            return PipeOutputConfigPacketType::PIXEL_DATA;
        case proto::PacketType::PIXEL_ZERO_COPY_DATA:
            return PipeOutputConfigPacketType::PIXEL_ZERO_COPY_DATA;
    }
}

PipeTerminationConfigTerminationType ConvertTerminationType(
    proto::TerminationOption_TerminationType type) {
    switch (type) {
        case proto::TerminationOption_TerminationType_CLIENT_STOP:
            return PipeTerminationConfigTerminationType::CLIENT_STOP;
        case proto::TerminationOption_TerminationType_MIN_PACKET_COUNT:
            return PipeTerminationConfigTerminationType::MIN_PACKET_COUNT;
        case proto::TerminationOption_TerminationType_MAX_RUN_TIME:
            return PipeTerminationConfigTerminationType::MAX_RUN_TIME;
        case proto::TerminationOption_TerminationType_EVENT:
            return PipeTerminationConfigTerminationType::EVENT;
    }
}

PipeInputConfig ConvertInputConfigProto(proto::InputConfig proto) {
    PipeInputConfig aidlConfig;
    aidlConfig.options.type = ConvertInputType(proto.type());
    aidlConfig.options.format = ConvertInputFormat(proto.format());
    aidlConfig.options.width = proto.width();
    aidlConfig.options.height = proto.height();
    aidlConfig.options.stride = proto.stride();
    aidlConfig.options.camId = proto.cam_id();
    aidlConfig.configId = proto.config_id();
    return aidlConfig;
}

PipeOffloadConfig ConvertOffloadConfigProto(proto::OffloadConfig proto) {
    PipeOffloadConfig aidlConfig;

    for (int i = 0; i < proto.options().offload_types_size(); i++) {
        auto offloadType =
            static_cast<proto::OffloadOption_OffloadType>(proto.options().offload_types()[i]);
        PipeOffloadConfigOffloadType aidlType = ConvertOffloadType(offloadType);
        aidlConfig.options.type.emplace_back(aidlType);
        aidlConfig.options.isVirtual.emplace_back(proto.options().is_virtual()[i]);
    }

    aidlConfig.configId = proto.config_id();
    return aidlConfig;
}

PipeOutputConfig ConvertOutputConfigProto(proto::OutputConfig proto) {
    PipeOutputConfig aidlConfig;
    aidlConfig.output.name = proto.stream_name();
    aidlConfig.output.type = ConvertOutputType(proto.type());
    aidlConfig.outputId = proto.stream_id();
    return aidlConfig;
}

PipeTerminationConfig ConvertTerminationConfigProto(proto::TerminationConfig proto) {
    PipeTerminationConfig aidlConfig;
    aidlConfig.options.type = ConvertTerminationType(proto.options().type());
    aidlConfig.options.qualifier = proto.options().qualifier();
    aidlConfig.configId = proto.config_id();
    return aidlConfig;
}

}  // namespace

PipeDescriptor OptionsToPipeDesciptor(proto::Options options) {
    PipeDescriptor desc;
    for (int i = 0; i < options.input_configs_size(); i++) {
        PipeInputConfig inputConfig = ConvertInputConfigProto(options.input_configs()[i]);
        desc.inputConfig.emplace_back(inputConfig);
    }

    for (int i = 0; i < options.offload_configs_size(); i++) {
        PipeOffloadConfig offloadConfig = ConvertOffloadConfigProto(options.offload_configs()[i]);
        desc.offloadConfig.emplace_back(offloadConfig);
    }

    for (int i = 0; i < options.termination_configs_size(); i++) {
        PipeTerminationConfig terminationConfig =
            ConvertTerminationConfigProto(options.termination_configs()[i]);
        desc.terminationConfig.emplace_back(terminationConfig);
    }

    for (int i = 0; i < options.output_configs_size(); i++) {
        PipeOutputConfig outputConfig = ConvertOutputConfigProto(options.output_configs()[i]);
        desc.outputConfig.emplace_back(outputConfig);
    }
    return desc;
}

}  // namespace aidl_client
}  // namespace client_interface
}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
