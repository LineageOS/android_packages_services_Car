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

car_ui_portrait_modules := \
    apps/HideApps

# TODO(b/199553899): temporarily exclude car_ui_portrait from coverage report.
# Car API xmls files are not generated when including car_ui_portrait.
ifneq (,$(filter %car_ui_portrait,$(TARGET_PRODUCT)))
car_ui_portrait_modules += \
    rro/car-ui-customizations \
    rro/car-ui-toolbar-customizations
endif #car_ui_portrait

include $(call all-named-subdir-makefiles,$(car_ui_portrait_modules))
