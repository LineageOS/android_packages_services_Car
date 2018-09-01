# Copyright (C) 2015 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#

#disble build in PDK, should add prebuilts/fullsdk to make this work
ifneq ($(TARGET_BUILD_PDK),true)

LOCAL_PATH:= $(call my-dir)

# Build prebuilt android.support.car library
# ---------------------------------------------
include $(CLEAR_VARS)

LOCAL_AAPT_FLAGS := --auto-add-overlay
LOCAL_MODULE := android.support.car-prebuilt
LOCAL_MODULE_CLASS := JAVA_LIBRARIES

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

#TODO(b/72620511) support lib should be able to be using public APIs only
#LOCAL_SDK_VERSION := current
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MANIFEST_FILE := AndroidManifest.xml

LOCAL_SRC_FILES := $(call all-java-files-under, src) $(call all-Iaidl-files-under, src)
LOCAL_JAVA_LIBRARIES += android.car\
                        androidx.annotation_annotation

LOCAL_PROGUARD_ENABLED := custom optimization obfuscation
LOCAL_PROGUARD_FLAGS := -dontwarn
LOCAL_PROGUARD_FLAG_FILES := proguard-release.flags proguard-extra-keeps.flags

include $(BUILD_STATIC_JAVA_LIBRARY)

ifeq ($(BOARD_IS_AUTOMOTIVE), true)
    $(call dist-for-goals, dist_files, $(built_aar):android.support.car.aar)
endif

# Same as above, except without proguard.
# ---------------------------------------------
include $(CLEAR_VARS)

LOCAL_AAPT_FLAGS := --auto-add-overlay
LOCAL_MODULE := android.support.car-1p-prebuilt
LOCAL_MODULE_CLASS := JAVA_LIBRARIES

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

#TODO(b/72620511) support lib should be able to be using public APIs only
#LOCAL_SDK_VERSION := current
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MANIFEST_FILE := AndroidManifest.xml

LOCAL_SRC_FILES := $(call all-java-files-under, src) $(call all-Iaidl-files-under, src)
LOCAL_JAVA_LIBRARIES += android.car\
                        androidx.annotation_annotation

LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_STATIC_JAVA_LIBRARY)

ifeq ($(BOARD_IS_AUTOMOTIVE), true)
    $(call dist-for-goals, dist_files, $(built_aar):android.support.car-1p.aar)
endif

.PHONY: update-support-car-proguard-api
update-support-car-proguard-api: $(INTERNAL_PLATFORM_ANDROID_SUPPORT_CAR_PROGUARD_PROGUARD_FILE) | $(ACP)
	@echo $(PRIVATE_CAR_MODULE) copying $(INTERNAL_PLATFORM_ANDROID_SUPPORT_CAR_PROGUARD_PROGUARD_FILE) to $(LOCAL_PATH)/proguard-release.flags
	$(hide) $(ACP) $(INTERNAL_PLATFORM_ANDROID_SUPPORT_CAR_PROGUARD_PROGUARD_FILE) $(LOCAL_PATH)/proguard-release.flags

endif #TARGET_BUILD_PDK
