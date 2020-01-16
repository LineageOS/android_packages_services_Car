// Copyright (C) 2020 The Android Open Source Project
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

#ifndef COMPUTEPIPE_RUNNER_GRAPH_INCLUDE_PREBUILTGRAPH_H_
#define COMPUTEPIPE_RUNNER_GRAPH_INCLUDE_PREBUILTGRAPH_H_

#include <functional>
#include <shared_mutex>
#include <string>

#include "ClientConfig.pb.h"
#include "Options.pb.h"
#include "PrebuiltEngineInterface.h"
#include "RunnerComponent.h"
#include "types/Status.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace graph {

enum PrebuiltGraphState {
    RUNNING = 0,
    UNINITIALIZED,
    FLUSHING,
    STOPPED,
};

// PrebuiltGraph is a singleton class. This is because the underlying functions that it implements
// are C functions that carry around state.
class PrebuiltGraph : public runner::RunnerComponentInterface {
  private:
    // Private constructor
    PrebuiltGraph() {
    }

  public:
    ~PrebuiltGraph();

    // No copy or move constructors or operators are available.
    PrebuiltGraph(const PrebuiltGraph&) = delete;
    PrebuiltGraph& operator=(const PrebuiltGraph&) = delete;

    // Override RunnerComponent interface functions for applying configs,
    // starting the graph and stopping the graph.
    Status handleConfigPhase(const runner::ClientConfig& e) override;
    Status handleExecutionPhase(const runner::RunnerEvent& e) override;
    Status handleStopWithFlushPhase(const runner::RunnerEvent& e) override;
    Status handleStopImmediatePhase(const runner::RunnerEvent& e) override;
    Status handleResetPhase(const runner::RunnerEvent& e) override;

    static PrebuiltGraph* GetPrebuiltGraphFromLibrary(
        const std::string& prebuiltLib, std::shared_ptr<PrebuiltEngineInterface> engineInterface);

    PrebuiltGraphState GetGraphState() const {
        return mGraphState;
    }

    Status GetStatus() const;

    std::string GetErrorMessage() const;

    // Gets the supported graph config options.
    const proto::Options& GetSupportedGraphConfigs() const {
        return mGraphConfig;
    }

    // Sets input stream data. The string is expected to be a serialized proto
    // the definition of which is known to the graph.
    Status SetInputStreamData(int streamIndex, int64_t timestamp, const std::string& streamData);

    // Sets pixel data to the specified input stream index.
    Status SetInputStreamPixelData(int streamIndex, int64_t timestamp, const uint8_t* pixels,
                                   int width, int height, int step, PixelFormat format);

    // Collects debugging and profiling information for the graph. The graph
    // needs to be started with debugging enabled in order to get valid info.
    std::string GetDebugInfo();

  private:
    // Starts the graph execution.
    Status StartGraphExecution(bool debuggingEnabled);

    // Stops the graph execution.
    Status StopGraphExecution(bool flushOutputFrames);

    // Callback functions. The class has a C++ function callback interface while it deals with pure
    // C functions underneath that do not have object context. We need to have these static
    // functions that need to be passed to the C interface.
    static void OutputPixelStreamCallbackFunction(void* cookie, int streamIndex, int64_t timestamp,
                                                  const uint8_t* pixels, int width, int height,
                                                  int step, int format);
    static void OutputStreamCallbackFunction(void* cookie, int streamIndex, int64_t timestamp,
                                             const unsigned char* data, size_t dataSize);
    static void GraphTerminationCallbackFunction(void* cookie,
                                                 const unsigned char* terminationMessage,
                                                 size_t terminationMessageSize);

    // Cached callback interface that is passed in from the runner.
    std::shared_ptr<PrebuiltEngineInterface> mEngineInterface;

    static std::mutex mCreationMutex;
    static PrebuiltGraph* mPrebuiltGraphInstance;

    // Even though mutexes are generally preferred over atomics, the only varialble in this class
    // that changes after initialization is graph state and that is the only vairable that needs
    // to be guarded. The prebuilt is internally assumed to be thread safe, so that concurrent
    // calls into the library will automatically be handled in a thread safe manner by the it.
    std::atomic<PrebuiltGraphState> mGraphState = PrebuiltGraphState::UNINITIALIZED;

    // Dynamic library handle
    void* mHandle;

    // Repeated function calls need not be made to get the graph version and the config is this is
    // constant through the operation of the graph. These values are just cached as strings.
    std::string mGraphVersion;
    proto::Options mGraphConfig;

    // Cached functions from the dynamic library.
    void* mFnGetErrorCode;
    void* mFnGetErrorMessage;
    void* mFnUpdateGraphConfig;
    void* mFnResetGraph;
    void* mFnSetInputStreamData;
    void* mFnSetInputStreamPixelData;
    void* mFnSetOutputStreamCallback;
    void* mFnSetOutputPixelStreamCallback;
    void* mFnSetGraphTerminationCallback;
    void* mFnStartGraphExecution;
    void* mFnStopGraphExecution;
    void* mFnGetDebugInfo;
};

}  // namespace graph
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif  // COMPUTEPIPE_RUNNER_GRAPH_INCLUDE_PREBUILTGRAPH_H_
