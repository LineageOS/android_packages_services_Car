#!/usr/bin/env python3
#  Copyright (C) 2022 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
import sys
import os
import subprocess

rootDir = os.getenv("ANDROID_BUILD_TOP")
if (rootDir is None):
    print("\nANDROID_BUILD_TOP not set. Run source and lunch.\n")
    sys.exit(1)

# Generate class list using tool
java_cmd = "java -jar " + rootDir + "/packages/services/Car/tools/GenericCarApiBuilder" \
                                    "/GenericCarApiBuilder.jar --print-classes-only "
new_class_list = subprocess.check_output(java_cmd, shell=True).decode('utf-8').strip().split("\n")

# Read current class list
car_api = rootDir + "/packages/services/Car/tests/carservice_unit_test/res/raw/car_api_classes.txt"
car_built_in_api = rootDir + "/packages/services/Car/tests/carservice_unit_test/res/raw" \
                             "/car_built_in_api_classes.txt"
existing_class_list = []
with open(car_api) as f:
    existing_class_list.extend(f.read().splitlines())
with open(car_built_in_api) as f:
    existing_class_list.extend(f.read().splitlines())


# Find the diff in both class list
extra_new_classes = [i for i in new_class_list if i not in existing_class_list]
extra_deleted_classes = [i for i in existing_class_list if i not in new_class_list]

# Print error is there is any class added or removed without changing test
error = ""
if len(extra_deleted_classes) > 0:
    error = error + "Following Classes are deleted \n" + "\n".join(extra_deleted_classes)
if len(extra_new_classes) > 0:
    error = error + "Following new classes are added \n" + "\n".join(extra_new_classes)

if error != "":
    print(error)
    print("\nRun following command to generate classlist for annotation test")
    print("cd $ANDROID_BUILD_TOP && m -j GenericCarApiBuilder && GenericCarApiBuilder "
          "--update-classes-for-test")
    print("\nThen run following test to make sure classes are properly annotated")
    print("atest CarServiceUnitTest:android.car.AnnotationTest")
    sys.exit(1)
