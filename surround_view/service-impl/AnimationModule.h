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

#ifndef SURROUND_VIEW_SERVICE_IMPL_ANIMATION_H_
#define SURROUND_VIEW_SERVICE_IMPL_ANIMATION_H_

#include "core_lib.h"

#include <utils/SystemClock.h>
#include <cstdint>
#include <map>
#include <set>
#include <vector>

#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>

using namespace ::android::hardware::automotive::vehicle::V2_0;
using namespace android_auto::surround_view;

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

struct Range {
    // Range start.
    // Start value may be greater than end value.
    float start;

    // Range end.
    float end;
};

// Rotation axis
struct RotationAxis {
    // Unit axis direction vector.
    std::array<float, 3> axisVector;

    // Rotate about this point.
    std::array<float, 3> rotationPoint;
};

enum AnimationType {
    // Rotate a part about an axis from a start to end angle.
    ROTATION_ANGLE = 0,

    // Continuously rotate a part about an axis by a specified angular speed.
    ROTATION_SPEED = 1,

    // Linearly translates a part from one point to another.
    TRANSLATION = 2,

    // Switch to another texture once.
    SWITCH_TEXTURE_ONCE = 3,

    // Adjust the brightness of the texture once.
    ADJUST_GAMMA_ONCE = 4,

    // Repeatedly toggle between two textures.
    SWITCH_TEXTURE_REPEAT = 5,

    // Repeatedly toggle between two gamma values.
    ADJUST_GAMMA_REPEAT = 6,
};

// Rotation operation
struct RotationOp {
    // VHAL signal to trigger operation.
    uint64_t vhalProperty;

    // Rotation operation type.
    AnimationType type;

    // Rotation axis.
    RotationAxis axis;

    // Default rotation (angle/speed) value.
    // It is used for default rotation when the signal is on while vhal_range is
    // not provided.
    float defaultRotationValue;

    // Default animation time elapsed to finish the rotation operation.
    // It is ignored if VHAL provides continuous signal value.
    float animationTime;

    // physical rotation range with start mapped to vhal_range start and
    // end mapped to vhal_range end.
    Range rotationRange;

    // VHAL signal range.
    // Un-supported types: STRING, BYTES and VEC
    // Refer:  hardware/interfaces/automotive/vehicle/2.0/types.hal
    // VehiclePropertyType
    Range vhalRange;
};

// Translation operation.
struct TranslationOp {
    // VHAL signal to trigger operation.
    uint64_t vhalProperty;

    // Translation operation type.
    AnimationType type;

    // Unit direction vector.
    std::array<float, 3> direction;

    // Default translation value.
    // It is used for default translation when the signal is on while vhal_range
    // is not provided.
    float defaultTranslationValue;

    // Default animation time elapsed to finish the texture operation.
    // It is ignored if VHAL provides continuous signal value.
    float animationTime;

    // Physical translation range with start mapped to vhal_range start and
    // end mapped to vhal_range end.
    Range translationRange;

    // VHAL signal range.
    // Un-supported types: STRING, BYTES and VEC
    // Refer:  hardware/interfaces/automotive/vehicle/2.0/types.hal
    // VehiclePropertyType
    Range vhalRange;
};

// Texture operation.
struct TextureOp {
    // VHAL signal to trigger operation.
    uint64_t vhalProperty;

    // Texture operation type.
    AnimationType type;

    // Default texture id.
    // It is used as default texture when the signal is on while vhal_range is
    // not provided.
    std::string defaultTexture;

    // Default animation time elapsed to finish the texture operation.
    // Unit is milliseconds.
    // If the animation time is specified, the vhal_property is assumed to be
    // on/off type.
    // It is ignored if it is equal or less than zero and vhal_property is
    // assumed to provide continuous value.
    int animationTime;

    // texture range mapped to texture_ids[i].first.
    Range textureRange;

    // VHAL signal range.
    // Un-supported types: STRING, BYTES and VEC
    // Refer:  hardware/interfaces/automotive/vehicle/2.0/types.hal
    // VehiclePropertyType
    Range vhalRange;

    // Texture ids for switching textures.
    // Applicable for animation types: kSwitchTextureOnce and
    // kSwitchTextureRepeated
    // 0 - n-1
    std::vector<std::pair<float, std::string>> textureIds;
};

// Gamma operation.
struct GammaOp {
    // VHAL signal to trigger operation.
    uint64_t vhalProperty;

    // Texture operation type.
    // Applicable for animation types: kAdjustGammaOnce and kAdjustGammaRepeat.
    AnimationType type;

    // Default animation time elapsed to finish the gamma operation.
    // Unit is milliseconds.
    // If the animation time is specified, the vhal_property is assumed to be
    // on/off type.
    // It is ignored if it is equal or less than zero and vhal_property is
    // assumed to provide continuous value.
    int animationTime;

    // Gamma range with start mapped to vhal_range start and
    // end mapped to vhal_range end.
    Range gammaRange;

    // VHAL signal range.
    // Un-supported types: STRING, BYTES and VEC
    // Refer:  hardware/interfaces/automotive/vehicle/2.0/types.hal
    // VehiclePropertyType
    Range vhalRange;
};

// Animation info of a car part
struct AnimationInfo {
    // Car animation part id(name). It is a unique id.
    std::string partId;

    // Car part parent name.
    std::string parentId;

    // Car part pose w.r.t parent's coordinate.
    Mat4x4 pose;

    // VHAL priority from high [0] to low [n-1]. Only VHALs specified in the
    // vector have priority.
    std::vector<uint64_t> vhalPriority;

    // TODO(b/158245554): simplify xxOpsMap data structs.
    // Map of gamma operations. Key value is VHAL property.
    std::map<uint64_t, std::vector<GammaOp>> gammaOpsMap;

    // Map of texture operations. Key value is VHAL property.
    std::map<uint64_t, std::vector<TextureOp>> textureOpsMap;

    // Map of rotation operations. Key value is VHAL property.
    // Multiple rotation ops are supported and will be simultaneously animated in
    // order if their rotation axis are different and rotation points are the
    // same.
    std::map<uint64_t, std::vector<RotationOp>> rotationOpsMap;

    // Map of translation operations. Key value is VHAL property.
    std::map<uint64_t, std::vector<TranslationOp>> translationOpsMap;
};

// Car animation class. It is constructed with textures, animations, and
// vhal_handler. It automatically updates animation params when
// GetUpdatedAnimationParams() is called.
class AnimationModule {
public:
    // Constructor.
    // |parts| is from I/O module. The key value is part id.
    // |textures| is from I/O module. The key value is texture id.
    // |animations| is from I/O module.
    AnimationModule(const std::map<std::string, CarPart>& partsMap,
                    const std::map<std::string, CarTexture>& texturesMap,
                    const std::vector<AnimationInfo>& animations);

    // Gets Animation parameters with input of VehiclePropValue.
    std::vector<AnimationParam> getUpdatedAnimationParams(
            const std::vector<VehiclePropValue>& vehiclePropValue);

private:
    // Internal car part status.
    struct CarPartStatus {
        // Car part id.
        std::string partId;

        // Car part children ids.
        std::vector<std::string> childIds;

        // Parent model matrix.
        Mat4x4 parentModel;

        // Local model in local coordinate.
        Mat4x4 localModel;

        // Current status model matrix in global coordinate with
        // animations combined.
        // current_model = local_model * parent_model;
        Mat4x4 currentModel;

        // Gamma parameters.
        float gamma;

        // Texture id.
        std::string textureId;

        // Internal vhal percentage. Each car part maintain its own copy
        // the vhal percentage.
        // Key value is vhal property (combined with area id).
        std::map<uint64_t, float> vhalProgressMap;

        // Vhal off map. Key value is vhal property (combined with area id).
        // Assume off status when vhal value is 0.
        std::map<uint64_t, bool> vhalOffMap;
    };

    // Internal Vhal status.
    struct VhalStatus {
        float vhalValueFloat;
    };

    // Help function to get vhal to parts map.
    void mapVhalToParts();

    // Help function to init car part status for constructor.
    void initCarPartStatus();

    // Iteratively update children parts status if partent status is changed.
    void updateChildrenParts(const std::string& partId, const Mat4x4& parentModel);

    // Perform gamma opertion for the part with given vhal property.
    void performGammaOp(const std::string& partId, uint64_t vhalProperty, const GammaOp& gammaOp);

    // Perform translation opertion for the part with given vhal property.
    void performTranslationOp(const std::string& partId, uint64_t vhalProperty,
                              const TranslationOp& translationOp);

    // Perform texture opertion for the part with given vhal property.
    // Not implemented yet.
    void performTextureOp(const std::string& partId, uint64_t vhalProperty,
                          const TextureOp& textureOp);

    // Perform rotation opertion for the part with given vhal property.
    void performRotationOp(const std::string& partId, uint64_t vhalProperty,
                           const RotationOp& rotationOp);

    // Last call time of GetUpdatedAnimationParams() in millisecond.
    float mLastCallTime;

    // Current call time of GetUpdatedAnimationParams() in millisecond.
    float mCurrentCallTime;

    // Flag indicating if GetUpdatedAnimationParams() was called before.
    bool mIsCalled;

    std::map<std::string, CarPart> mPartsMap;

    std::map<std::string, CarTexture> mTexturesMap;

    std::vector<AnimationInfo> mAnimations;

    std::map<std::string, AnimationInfo> mPartsToAnimationMap;

    std::map<uint64_t, VhalStatus> mVhalStatusMap;

    std::map<uint64_t, std::set<std::string>> mVhalToPartsMap;

    std::map<std::string, CarPartStatus> mCarPartsStatusMap;

    std::map<std::string, AnimationParam> mUpdatedPartsMap;
};

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android

#endif  // SURROUND_VIEW_SERVICE_IMPL_ANIMATION_H_
