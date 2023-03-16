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
import re

from tempfile import NamedTemporaryFile
from pathlib import Path

# Helper method that strips out the parameter names of methods. This will allow users to change
# parameter names for hidden apis without mistaking them as having been removed.
# [^ ]* --> Negation set on SPACE character. This wll match everything until a SPACE.
# *?(?=\)) --> This means the character ')' will not be included in the match.
# [^ (]*?(?=\)) --> This will handle the last parameter at the end of a method signature. It excludes matching any '(' characters when there are no parameters, i.e. method().
# [^ ]*?(?=,) --> This will handle multiple parameters delimited by commas.
def strip_param_names(api):
    return re.sub('[^ (]*?(?=\))|[^ ]*?(?=,)', " ", api)

rootDir = os.getenv("ANDROID_BUILD_TOP")
if rootDir is None or rootDir == "":
    # env variable is not set. Then use the arg passed as Git root
    rootDir = sys.argv[1]

javaHomeDir = os.getenv("JAVA_HOME")
if javaHomeDir is None or javaHomeDir == "":
    if Path(rootDir + '/prebuilts/jdk/jdk17/linux-x86').is_dir():
        javaHomeDir = rootDir + "/prebuilts/jdk/jdk17/linux-x86"
    else:
        print("$JAVA_HOME is not set. Please use source build/envsetup.sh` in $ANDROID_BUILD_TOP")
        sys.exit(1)

# This generates a list of all classes.
java_cmd = javaHomeDir + "/bin/java -jar " + rootDir + \
           "/packages/services/Car/tools/GenericCarApiBuilder" \
           "/GenericCarApiBuilder.jar --print-classes-only " \
           "--ANDROID-BUILD-TOP " + rootDir

# This produces a list of current hidden apis to determine if they have been modified or removed.
java_cmd_2 = javaHomeDir + "/bin/java -jar " + rootDir + \
             "/packages/services/Car/tools/GenericCarApiBuilder" \
             "/GenericCarApiBuilder.jar --print-hidden-api-for-test " \
             "--ANDROID-BUILD-TOP " + rootDir

# This determines all remaining hidden, system or public APIs.
java_cmd_3 = javaHomeDir + "/bin/java -jar " + rootDir + \
             "/packages/services/Car/tools/GenericCarApiBuilder" \
             "/GenericCarApiBuilder.jar --print-shortform-full-api-for-test " \
             "--include-constructors --ANDROID-BUILD-TOP " + rootDir

processes = []
cmds = [java_cmd, java_cmd_2, java_cmd_3]
for cmd in cmds:
    f = NamedTemporaryFile()
    p = subprocess.Popen(cmd, shell=True, stdout=f)
    processes.append((p, f))

results = []
for p, f in processes:
    p.wait()
    f.seek(0)
    results.append(f.read().decode('utf-8').strip().split("\n"))
    f.close()

new_class_list, new_hidden_apis, all_apis = results[0], results[1], results[2]

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
    error = error + "\n\nFollowing new classes are added \n" + "\n".join(extra_new_classes)

if error != "":
    print(error)
    print("\nRun following command to generate classlist for annotation test")
    print("cd $ANDROID_BUILD_TOP && m -j GenericCarApiBuilder && GenericCarApiBuilder "
          "--update-classes-for-test")
    print("\nThen run following test to make sure classes are properly annotated")
    print("atest CarServiceUnitTest:android.car.AnnotationTest")
    sys.exit(1)

for index, value in enumerate(all_apis):
    all_apis[index] = strip_param_names(value)

# read existing hidden APIs
previous_hidden_apis_path = rootDir + "/packages/services/Car/tests/carservice_unit_test/res/raw" \
                             "/car_hidden_apis.txt"

# hidden_apis_previous_releases contains all the cumulative hidden apis added in previous releases.
# If some hidden API was added in T-QPR and removed in master, then one should be able
# to identify it. Accordingly, a new file will need to be generated for each release.
hidden_apis_previous_releases_paths = [
    "/packages/services/Car/tests/carservice_unit_test/res/raw/car_hidden_apis_release_33.3.txt",
    "/packages/services/Car/tests/carservice_unit_test/res/raw/car_hidden_apis_release_33.2.txt",
    "/packages/services/Car/tests/carservice_unit_test/res/raw/car_hidden_apis_release_33.1.txt"
]

previous_hidden_apis = set()
with open(previous_hidden_apis_path) as f:
    previous_hidden_apis = set(f.read().splitlines())

hidden_apis_previous_releases = set()
for path in hidden_apis_previous_releases_paths:
    with open(rootDir + path) as f:
        hidden_apis = set(f.read().splitlines())
        hidden_apis_previous_releases = hidden_apis_previous_releases.union(hidden_apis)

excluded_removed_hidden_apis_path = rootDir + "/packages/services/Car/tests/carservice_unit_test/res/raw" \
                                      "/car_hidden_apis_excluded.txt"

with open(excluded_removed_hidden_apis_path) as f:
    excluded_removed_hidden_apis = set(f.read().splitlines())

hidden_apis_previous_releases = hidden_apis_previous_releases - excluded_removed_hidden_apis

# All new_hidden_apis should be in previous_hidden_apis. There can be some entry in previous_hidden_apis
# which is not in new_hidden_apis. It is okay as some APIs might have been promoted.
new_hidden_apis = set(new_hidden_apis)
modified_or_added_hidden_api = new_hidden_apis - previous_hidden_apis

upgraded_hidden_apis = previous_hidden_apis - new_hidden_apis

# TODO(b/266849922): Add a pre-submit test to also check for added or modified hidden apis,
# since one could also bypass the repohook tool using --no-verify.
if len(modified_or_added_hidden_api) > 0:
    print("\nHidden APIs should not be added or modified. The following Hidden APIs were added or modified in this CL:")
    print("\n".join(modified_or_added_hidden_api))
    print(
        "\nIf adding a hidden API is necessary, please create a bug here: go/car-mainline-add-hidden-api."
        "\nYou are responsible for maintaining the hidden API, which may include future deprecation or"
        " upgrade of the hidden API. \nTo learn more about hidden API usage and removal in the Car stack please visit go/car-hidden-api-usage-removal."
        "\nTo add a hidden API, please run the following command after creating the bug:")
    print("\ncd $ANDROID_BUILD_TOP && m -j GenericCarApiBuilder && GenericCarApiBuilder "
          "--update-hidden-api-for-test")
    print("\nPlease do not use \"no-verify\" to bypass this check. Reach out to gargmayank@ or"
          " ethanalee@ if there is any confusion or repo upload is not working for you even after running the previous command.")
    sys.exit(1)

# Hidden APIs should not be removed. Check that any of the previously hidden apis still exist in the remaining apis.
# This is different from hidden APIs that were upgraded to system or public APIs.
removed_hidden_api = []
for api in hidden_apis_previous_releases:
    if strip_param_names(api) not in all_apis:
        removed_hidden_api.append(api)

# If a hidden API was upgraded to system or public API, the car_hidden_apis.txt should be updated to reflect its upgrade.
upgraded_hidden_apis = upgraded_hidden_apis - set(removed_hidden_api)
if len(upgraded_hidden_apis) > 0:
    print("\nThe following hidden APIs were upgraded to either system or public APIs.")
    print("\n".join(upgraded_hidden_apis))
    print("\nPlease run the following command to update: ")
    print("\ncd $ANDROID_BUILD_TOP && m -j GenericCarApiBuilder && GenericCarApiBuilder "
          "--update-hidden-api-for-test")
    print("\nReach out to gargmayank@ or ethanalee@ if you have any questions or concerns regarding "
          "upgrading hidden APIs. Visit go/upgrade-hidden-api for more info.")
    print("\n\n")
    sys.exit(1)

if len(removed_hidden_api) > 0:
    print("\nHidden APIs cannot be removed as the Car stack is now a mainline module. The following Hidden APIs were removed:")
    print("\n".join(removed_hidden_api))
    print("\nPlease do not use \"no-verify\" to bypass this check. "
          "To learn more about hidden API deprecation and removal visit go/car-hidden-api-usage-removal. "
          "\nReach out to gargmayank@ or ethanalee@ if you have any questions or concerns regarding "
          "removing hidden APIs.")
    sys.exit(1)
