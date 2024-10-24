/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef CAR_EVS_APP_RENDERDIRECTVIEW_H
#define CAR_EVS_APP_RENDERDIRECTVIEW_H

#include "ConfigManager.h"
#include "RenderBase.h"
#include "VideoTex.h"

#include <android/hardware/automotive/evs/1.0/IEvsEnumerator.h>

namespace android {
namespace automotive {
namespace evs {
namespace support {

using ::android::hardware::automotive::evs::V1_0::BufferDesc;

/*
 * Renders the view from a single specified camera directly to the full display.
 */
class RenderDirectView : public RenderBase {
public:
    virtual bool activate() override;
    virtual void deactivate() override;

    virtual bool drawFrame(const BufferDesc& tgtBuffer, const BufferDesc& imageBuffer) override;

protected:
    std::unique_ptr<VideoTex> mTexture;

    GLuint mShaderProgram = 0;
};

}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android

#endif  // CAR_EVS_APP_RENDERDIRECTVIEW_H
