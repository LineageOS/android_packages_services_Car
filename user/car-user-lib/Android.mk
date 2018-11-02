# Copyright (C) 2018 The Android Open Source Project
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

#disble build in PDK, missing aidl import breaks build
ifneq ($(TARGET_BUILD_PDK),true)

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

car_user_lib_sources := $(call all-java-files-under, src)

# API Check
# ---------------------------------------------
car_user_module := android.car.userlib
car_user_module_src_files := $(car_user_lib_sources)
car_user_module_api_dir := $(LOCAL_PATH)/api
car_user_module_java_libraries := framework
car_user_module_include_systemapi := true
car_user_module_java_packages := android.car.userlib*
include $(CAR_API_CHECK)

# Build stubs jar for target android-support-car
# ---------------------------------------------
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.car.userlib

LOCAL_ADDITIONAL_JAVA_DIR := $(call intermediates-dir-for,JAVA_LIBRARIES,android.car.userlib,,COMMON)/src

android_car_userlib_stub_packages := \
    android.car.userlib*

android_car_userlib_api := \
    $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/android.car.userlib_api.txt

# Note: The make target is android.car.userlib-stub-docs
LOCAL_MODULE := android.car.userlib-stub
LOCAL_DROIDDOC_OPTIONS := \
    -stubs $(call intermediates-dir-for,JAVA_LIBRARIES,android.car.userlib-stubs,,COMMON)/src \
    -stubpackages $(subst $(space),:,$(android_car_userlib_stub_packages)) \
    -api $(android_car_userlib_api) \
    -nodocs

LOCAL_DROIDDOC_SOURCE_PATH := $(LOCAL_PATH)/java/
LOCAL_DROIDDOC_HTML_DIR :=

LOCAL_MODULE_CLASS := JAVA_LIBRARIES

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(android_car_userlib_api): $(full_target)

android.car.userlib-stubs_stamp := $(full_target)

###############################################
# Build the stubs java files into a jar. This build rule relies on the
# stubs_stamp make variable being set from the droiddoc rule.

include $(CLEAR_VARS)

# CAR_API_CHECK uses the same name to generate a module, but BUILD_DROIDDOC
# appends "-docs" to module name.
LOCAL_MODULE := android.car.userlib-stubs
LOCAL_SOURCE_FILES_ALL_GENERATED := true

# Make sure to run droiddoc first to generate the stub source files.
LOCAL_ADDITIONAL_DEPENDENCIES := $(android.car.userlib-stubs_stamp)

include $(BUILD_STATIC_JAVA_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))

endif #TARGET_BUILD_PDK