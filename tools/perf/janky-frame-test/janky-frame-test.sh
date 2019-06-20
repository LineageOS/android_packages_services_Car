#!/bin/bash
#
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

NON_MAP_ACTIVITY=com.android.car.carlauncher/.AppGridActivity
MAP_PACKAGE=com.google.android.apps.maps
MAP_ACTIVITY=${MAP_PACKAGE}/com.google.android.maps.MapsActivity

# Function retrievie_frame returns two frame counts as space-separated string.
# The first one is total frame count and the second one is janky frame count.
function retrieve_frame()
{
  local START_PATTERN="Graphics info for pid ([0-9]+)"
  local PATTERN_FOUND=false
  local TOTAL_FRAME_PATTERN="Total frames rendered: ([0-9]+)"
  local JANKY_FRAME_PATTERN="Janky frames: ([0-9]+)"
  local TOTAL_FRAME=0
  local JANKY_FRAME=0

  while IFS= read -r LINE
  do
    if [[ ${LINE} =~ ${START_PATTERN} ]]; then
      if [ ${BASH_REMATCH[1]} = $1 ]; then
        PATTERN_FOUND=true
      else
        PATTERN_FOUND=false
      fi
    fi
    if [ ${PATTERN_FOUND} = "true" ]; then
      if [[ ${LINE} =~ ${TOTAL_FRAME_PATTERN} ]]; then
        TOTAL_FRAME=${BASH_REMATCH[1]}
      fi
      if [[ ${LINE} =~ ${JANKY_FRAME_PATTERN} ]]; then
        JANKY_FRAME=${BASH_REMATCH[1]}
      fi
    fi
  done < <(adb shell dumpsys gfxinfo ${MAP_PACKAGE})

  echo "${TOTAL_FRAME} ${JANKY_FRAME}"
}

echo "Testing...."

# Launch full-screen map.
# Starting full-screen map directly doesn't work due to some reasons.
# We need to kill the map when no map is shown and re-start map activity.
adb shell am start -n ${NON_MAP_ACTIVITY} >& /dev/null
adb shell am force-stop ${MAP_PACKAGE} >& /dev/null
adb shell am start -n ${MAP_ACTIVITY} >& /dev/null
sleep 7

# Get PID of map under user 10.
PS_INFO=($(adb shell ps -ef | fgrep ${MAP_PACKAGE} | fgrep u10))
MAP_PID=${PS_INFO[1]}

RET_VAL=($(retrieve_frame ${MAP_PID}))
OLD_TOTAL_FRAME=${RET_VAL[0]}
OLD_JANKY_FRAME=${RET_VAL[1]}

# Get screen size.
SIZE_PATTERN="Physical size: ([0-9]+)x([0-9]+)"
WM_SIZE=$(adb shell wm size)
if [[ ${WM_SIZE} =~ ${SIZE_PATTERN} ]]; then
  SCREEN_WIDTH=${BASH_REMATCH[1]}
  SCREEN_HEIGHT=${BASH_REMATCH[2]}
else
  echo "Test terminates due to failing to get screen size."
  exit 1
fi

LEFT_POS=$(awk -v width=${SCREEN_WIDTH} 'BEGIN {printf "%d", width * 0.2}')
RIGHT_POS=$(awk -v width=${SCREEN_WIDTH} 'BEGIN {printf "%d", width * 0.8}')
VERTICAL_MID_POS=$(awk -v height=${SCREEN_HEIGHT} 'BEGIN {printf "%d", height * 0.5}')
SWIPE_DURATION=100

# Send input signal to scroll map.
COUNTER=0
while [ $COUNTER -lt 10 ]; do
  adb shell input swipe ${LEFT_POS} ${VERTICAL_MID_POS} ${RIGHT_POS} ${VERTICAL_MID_POS} ${SWIPE_DURATION}
  sleep 0.5
  let COUNTER=COUNTER+1
done

COUNTER=0
while [ $COUNTER -lt 10 ]; do
  adb shell input swipe ${RIGHT_POS} ${VERTICAL_MID_POS} ${LEFT_POS} ${VERTICAL_MID_POS} ${SWIPE_DURATION}
  sleep 0.5
  let COUNTER=COUNTER+1
done

# Make sure that map drawing is finished.
sleep 3

RET_VAL=($(retrieve_frame ${MAP_PID}))
CUR_TOTAL_FRAME=${RET_VAL[0]}
CUR_JANKY_FRAME=${RET_VAL[1]}

if [ ${CUR_TOTAL_FRAME} = ${OLD_TOTAL_FRAME} ]; then
  echo "Map has not been updated. Test failed."
  exit 1
fi

TOTAL_COUNT=$(expr ${CUR_TOTAL_FRAME} - ${OLD_TOTAL_FRAME})
JANKY_COUNT=$(expr ${CUR_JANKY_FRAME} - ${OLD_JANKY_FRAME})

echo "Janky frame count: ${JANKY_COUNT} out of ${TOTAL_COUNT}"
