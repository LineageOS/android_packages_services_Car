#
# Copyright (C) 2021 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_PACKAGE_NAME := CarUiPortraitHideApps
LOCAL_SDK_VERSION := current

# Add packages here to remove them from the build
LOCAL_OVERRIDES_PACKAGES := \
    googlecarui-com-android-car-ui-paintbooth \
    googlecarui-com-android-car-rotaryplayground \
    googlecarui-com-android-car-themeplayground \
    googlecarui-com-android-car-carlauncher \
    googlecarui-com-android-car-home \
    googlecarui-com-android-car-media \
    googlecarui-com-android-car-radio \
    googlecarui-com-android-car-calendar \
    googlecarui-com-android-car-companiondevicesupport \
    googlecarui-com-android-car-systemupdater \
    googlecarui-com-android-car-dialer \
    googlecarui-com-android-car-linkviewer \
    googlecarui-com-android-car-settings \
    googlecarui-com-android-car-voicecontrol \
    googlecarui-com-android-car-faceenroll \
    googlecarui-com-android-permissioncontroller \
    googlecarui-com-android-settings-intelligence \
    googlecarui-com-google-android-apps-automotive-inputmethod \
    googlecarui-com-google-android-apps-automotive-inputmethod-dev \
    googlecarui-com-google-android-embedded-projection \
    googlecarui-com-google-android-gms \
    googlecarui-com-google-android-packageinstaller \
    googlecarui-com-google-android-carassistant \
    googlecarui-com-google-android-tts \
    googlecarui-com-android-vending \

LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
include $(BUILD_PACKAGE)
