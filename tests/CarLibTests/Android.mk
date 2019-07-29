# Copyright (C) 2019 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)

############################################################
# Robolectric test target for testing car test lib classes #
############################################################
include $(CLEAR_VARS)

LOCAL_MODULE := CarLibTests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_RESOURCE_DIRS := config

LOCAL_JAVA_LIBRARIES := \
    Robolectric_all-target \
    robolectric_android-all-stub \
    mockito-robolectric-prebuilt \
    truth-prebuilt \
    androidx.test.core \
    android.car.testapi \
    androidx.test.rules

LOCAL_INSTRUMENTATION_FOR := CarLibTestApp

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

###########################################################
# Runner to run the CarLibTests target                    #
###########################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunCarLibTests

LOCAL_JAVA_LIBRARIES := \
    CarLibTests \
    Robolectric_all-target \
    robolectric_android-all-stub \
    mockito-robolectric-prebuilt \
    android.car.testapi \
    truth-prebuilt \
    androidx.test.core \
    androidx.test.rules

LOCAL_TEST_PACKAGE := CarLibTestApp

include external/robolectric-shadows/run_robotests.mk
