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

#include "DisplayHandler.h"
#include "core_lib.h"
#include "GlRenderer.h"
#include "SurroundViewAppCommon.h"
#include "SurroundViewService.h"
#include "SurroundViewCallback.h"
#include "SurroundViewService.h"
#include "SurroundView3dSession.h"

// libhidl:
using android::sp;
using android::hardware::configureRpcThreadpool;
using android::hardware::joinRpcThreadpool;
using android::hardware::Return;
using android::hardware::automotive::evs::V1_0::EvsResult;

using namespace android::hardware::automotive::sv::V1_0;
using namespace android::hardware::automotive::evs::V1_1;
using namespace android::hardware::automotive::sv::V1_0::implementation;
using namespace android::hardware::automotive::sv::app;

using android_auto::surround_view::RendererInfo;
using android_auto::surround_view::OpenGlInitInfo;
using android::hardware::automotive::sv::V1_0::implementation::SurroundViewService;
using android::hardware::automotive::sv::V1_0::implementation::SurroundView3dSession;

namespace {
bool run2dSurroundView(sp<ISurroundViewService> pSurroundViewLibrary, sp<IEvsDisplay> pDisplay) {
    LOG(INFO) << "Running Surround View 2D.";

    // Initialize display handler and gl renderer.
    sp<DisplayHandler> displayHandler = new DisplayHandler(pDisplay);
    sp<GlRenderer> glRenderer = new GlRenderer(displayHandler);
    if (!glRenderer->initialize()) {
        LOG(ERROR) << "Failed to initialize glRenderer.";
        return false;
    }

    // Initialize a 2D Session.
    sp<ISurroundView2dSession> surroundView2dSession;
    SvResult svResult;
    pSurroundViewLibrary->start2dSession(
            [&surroundView2dSession, &svResult](const sp<ISurroundView2dSession>& session,
                                                SvResult result) {
                surroundView2dSession = session;
                svResult = result;
            });

    if (surroundView2dSession == nullptr || svResult != SvResult::OK) {
        LOG(ERROR) << "Failed to start2dSession";
        return false;
    }

    // Setup a SurroundViewCallback.
    sp<SurroundViewCallback> svCallback =
            new SurroundViewCallback(surroundView2dSession,
                                     [glRenderer](const HardwareBuffer& hardwareBuffer) {
                                         return glRenderer->renderBufferToScreen(
                                                 hardwareBuffer);
                                     });

    // Run Surround View 2D Session.
    if (!runSurroundView2dSession(surroundView2dSession, svCallback)) {
        // TODO(197005459): Refactor to use a helper class for simplifying clean-up.
        pSurroundViewLibrary->stop2dSession(surroundView2dSession);
        LOG(ERROR) << "Failed run2dSurroundViewSession";
        return false;
    }

    // Stop the 2D Session.
    pSurroundViewLibrary->stop2dSession(surroundView2dSession);
    LOG(INFO) << "End of Surround View 2D.";
    return true;
};

bool run3dSurroundView(sp<SurroundViewService> pSurroundViewLibrary, sp<IEvsDisplay> pDisplay) {
    LOG(INFO) << "Running Surround View 3D (Library).";

    // Initialize display handler and gl renderer.
    sp<DisplayHandler> displayHandler = new DisplayHandler(pDisplay);
    sp<GlRenderer> glRenderer = new GlRenderer(displayHandler);
    if (!glRenderer->initialize()) {
        LOG(ERROR) << "Failed to initialize glRenderer.";
        return false;
    }

    RendererInfo renderInfo = {.api = RendererInfo::RenderingApi::OPENGLES,
                               .version_major = 3,
                               .version_minor = 1};

    // Initialization info for external OpenGLES rendering.
    const OpenGlInitInfo glInitInfo = {.egl_display = glRenderer->getDisplay(),
                                       .egl_surface = glRenderer->getSurface(),
                                       .egl_context = glRenderer->getContext()};

    // Initialize a 3D Session with external rendering.
    sp<SurroundView3dSession> surroundView3dSession;
    if (!pSurroundViewLibrary->start3dSessionExternalRender(renderInfo, glInitInfo,
                                                            &surroundView3dSession)) {
        LOG(ERROR) << "Failed start3dSessionExternal.";
        return false;
    }

    // Setup a SurroundViewCallback with external rendering.
    sp<SurroundViewCallback> svCallback =
            new SurroundViewCallback(surroundView3dSession,
                                     [glRenderer](const HardwareBuffer& /*hardwareBuffer*/) {
                                         return glRenderer->renderGlTargetToScreen();
                                     });

    // Run Surround View 3D Session.
    if (!runSurroundView3dSession(surroundView3dSession, svCallback)) {
        // TODO(197005459): Refactor to use a helper class for simplifying clean-up.
        pSurroundViewLibrary->stop3dSession(surroundView3dSession);
        LOG(ERROR) << "Failed runSurroundView3dSession";
        return false;
    }

    // Stop the 3D Session.
    pSurroundViewLibrary->stop3dSession(surroundView3dSession);
    LOG(INFO) << "End of Surround View 3D.";
    return true;
}

}  // namespace

// Main entry point
int main(int argc, char** argv) {
    // Start up
    LOG(INFO) << "SV app starting";

    DemoMode mode = UNKNOWN;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--use2d") == 0) {
            mode = DEMO_2D;
        } else if (strcmp(argv[i], "--use3d") == 0) {
            mode = DEMO_3D;
        } else {
            LOG(WARNING) << "Ignoring unrecognized command line arg: " << argv[i];
        }
    }

    if (mode == UNKNOWN) {
        LOG(ERROR) << "No demo mode is specified. Exiting";
        return EXIT_FAILURE;
    }

    // Set thread pool size to one to avoid concurrent events from the HAL.
    // This pool will handle the SurroundViewStream callbacks.
    configureRpcThreadpool(1, false /* callerWillJoin */);

    // Try to connect to EVS service
    LOG(INFO) << "Acquiring EVS Enumerator";
    sp<IEvsEnumerator> evs = IEvsEnumerator::getService();
    if (evs == nullptr) {
        LOG(ERROR) << "getService(default) returned NULL.  Exiting.";
        return EXIT_FAILURE;
    }

    // Create a new instance of the SurroundViewService.
    LOG(INFO) << "Creating instance of Surround View Library.";
    // TODO(b/196727179): Use SurroundViewLibrary class instead of SurroundViewService.
    android::sp<SurroundViewService> surroundViewLibrary = SurroundViewService::getInstance();

    if (surroundViewLibrary == nullptr) {
        LOG(ERROR) << "getInstance(default) returned NULL.";
        return EXIT_FAILURE;
    }

    // Connect to evs display
    // getDisplayIdList returns a vector of uint64_t, so any valid display id is
    // guaranteed to be non-negative.
    int displayId = -1;
    evs->getDisplayIdList([&displayId](auto idList) {
        if (idList.size() > 0) {
            displayId = idList[0];
        }
    });
    if (displayId == -1) {
        LOG(ERROR) << "Cannot get a valid display";
        return EXIT_FAILURE;
    }

    LOG(INFO) << "Acquiring EVS Display with ID: " << displayId;
    sp<IEvsDisplay> display = evs->openDisplay_1_1(displayId);
    if (display == nullptr) {
        LOG(ERROR) << "EVS Display unavailable.  Exiting.";
        return EXIT_FAILURE;
    }

    if (mode == DEMO_2D) {
        if (!run2dSurroundView(surroundViewLibrary, display)) {
            LOG(ERROR) << "Something went wrong in 2d surround view demo. "
                       << "Exiting.";
            evs->closeDisplay(display);
            return EXIT_FAILURE;
        }
    } else if (mode == DEMO_3D) {
        if (!run3dSurroundView(surroundViewLibrary, display)) {
            LOG(ERROR) << "Something went wrong in 3d surround view demo. "
                       << "Exiting.";
            evs->closeDisplay(display);
            return EXIT_FAILURE;
        }
    }

    evs->closeDisplay(display);

    LOG(DEBUG) << "SV sample app finished running successfully";
    return EXIT_SUCCESS;
}
