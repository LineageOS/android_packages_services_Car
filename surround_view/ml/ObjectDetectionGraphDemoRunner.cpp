// Copyright 2020 The Android Open Source Project
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
#include <android-base/logging.h>
#include <android/binder_process.h>
#include <utils/Errors.h>
#include <utils/Log.h>

#include <iostream>
#include <memory>

#include "ClientConfig.pb.h"
#include "ClientInterface.h"
#include "MemHandle.h"
#include "Options.pb.h"
#include "PrebuiltGraph.h"
#include "RunnerEngine.h"
#include "types/Status.h"

using android::automotive::computepipe::Status;
using android::automotive::computepipe::graph::PrebuiltGraph;
using android::automotive::computepipe::proto::ClientConfig;
using android::automotive::computepipe::proto::Options;
using android::automotive::computepipe::runner::client_interface::ClientInterface;
using android::automotive::computepipe::runner::client_interface::ClientInterfaceFactory;
using android::automotive::computepipe::runner::engine::RunnerEngine;
using android::automotive::computepipe::runner::engine::RunnerEngineFactory;

int main(int /* argc */, char** /* argv */) {
    // Create computepipe engine
    RunnerEngineFactory sEngineFactory;
    std::shared_ptr<RunnerEngine> engine =
            sEngineFactory.createRunnerEngine(RunnerEngineFactory::kDefault, "");

    // Load prebuilt object detection graph
    // TODO(b/160960433): current prebuilt graph only supports arm64, should add
    // binary for other architectures as well.
    std::unique_ptr<PrebuiltGraph> graph;
    const char graphLib[] = "libdemoobjectdetection.so";
    graph.reset(android::automotive::computepipe::graph::
                        GetLocalGraphFromLibrary(graphLib, engine));
    Options options = graph->GetSupportedGraphConfigs();
    engine->setPrebuiltGraph(std::move(graph));

    // Set up the current process as a client of computepipe
    ClientInterfaceFactory sClientFactory;
    std::unique_ptr<ClientInterface> client =
            sClientFactory.createClientInterface("aidl", options, engine);
    if (!client) {
        LOG(ERROR) << "Unable to allocate client";
        return EXIT_FAILURE;
    }
    engine->setClientInterface(std::move(client));

    // Start AIDL thread
    ABinderProcess_startThreadPool();
    engine->activate();
    ABinderProcess_joinThreadPool();
    return EXIT_SUCCESS;
}
