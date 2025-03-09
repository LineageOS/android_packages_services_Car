#!/bin/bash
#
# This script contains helper functions to wait-for-device for an adb device with recovery.

readonly DEVICE_CONNECTION_TIMEOUT_SECONDS=120
readonly DEVICE_CONNECTION_RETRY_COUNT=5
# This constant may be updated by the inheriting script.
OUTPUT_DIR=${PWD}/out
HARDWARE_TYPE=""
DEVICE_SERIAL=""

function print_log() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S%z')] ${@}" | tee -a ${OUTPUT_DIR}/log.txt
}

# Fetches device information from the adb device.
# This method should be called during the script initialization and while the device is still
# connected.
function fetch_device_info() {
  readonly HARDWARE_TYPE=$(adb shell getprop ro.hardware.type)
  if [ -z ${ANDROID_SERIAL} ]; then
    readonly DEVICE_SERIAL=$(adb devices | grep -e "device$" | sed 's/\t/ /g' | cut -f1 -d' ')
  else
    readonly DEVICE_SERIAL=${ANDROID_SERIAL}
  fi
  if [[ -z ${DEVICE_SERIAL} || $(wc -l <<< "${DEVICE_SERIAL}") -ne 1 ]]; then
    print_log "Failed to get device serial '${DEVICE_SERIAL}'. Exiting"
    exit 1
  fi
}

function aae_device_recover() {
  if [[ ${HARDWARE_TYPE} != "automotive" ]]; then
    print_log "Non automotive device detected. Skipping device recovery"
  fi
  if [ ! -f ~/.aae-toolbox/bin/bashrc ]; then
    print_log "AAE toolbox not found at '~/.aae-toolbox/bin/bashrc'. Skipping device recovery"
  fi
  source ~/.aae-toolbox/bin/bashrc
  device_state=$(aae device list | grep ${DEVICE_SERIAL})
  device_count=$(grep -e missing -e recovery -e fastboot <<< ${device_state} | wc -l)
  if [ ${device_count} -eq 0 ]; then
    print_log "Don't have to recover ${DEVICE_SERIAL}. Skipping device recovery."\
      "\n\tDevice state:\n\t\t${device_state}"
    return
  fi
  aae device recover ${DEVICE_SERIAL}
}

is_exit_on_err_set=false
function _set_no_exit_on_error() {
  if [[ $- =~ "e" ]]; then
    is_exit_on_err_set=true
  else
    is_exit_on_err_set=false
  fi
  set +e
}

function _reset_exit_on_error() {
  if [ ${is_exit_on_err_set} == true ]; then
    set -e
  else
    set +e
  fi
}

is_adb_device_connected=false
function adb_wait_for_device_with_recovery() {
  print_log "Waiting for device connection with recovery after"\
    "${DEVICE_CONNECTION_TIMEOUT_SECONDS} seconds"
  is_adb_device_connected=false
  _set_no_exit_on_error
  timeout -k 10 ${DEVICE_CONNECTION_TIMEOUT_SECONDS} adb wait-for-device
  if [ $? -eq 0 ]; then
    is_adb_device_connected=true
    print_log "\t...Device connected"
    _reset_exit_on_error
    return
  fi
  total_retries=0
  while [ ${total_retries} -lt ${DEVICE_CONNECTION_RETRY_COUNT} ]; do
    total_retries+=1
    print_log "Device is in bad state. Trying to recover device. Recovery attempt #${total_retries}"
    aae_device_recover
    timeout -k 10 ${DEVICE_CONNECTION_TIMEOUT_SECONDS} adb wait-for-device
    if [ $? -eq 0 ]; then # Exit status is 124 if the command times out. Refer to `timeout --help`
      print_log "\t...Device connected"
      print_log "Generating post recovery bugreport for attempt #${total_retries}"
      adb bugreport ${OUTPUT_DIR}/post_recovery_bugreport_${total_retries}.zip
      is_adb_device_connected=true
      break
    fi
  done
  _reset_exit_on_error
}

function execute_on_adb_connect() {
  command="$@"
  adb_wait_for_device_with_recovery
  if [ ${is_adb_device_connected} != true ]; then
    print_log "ADB device '${DEVICE_SERIAL}' is not connected. Exiting"
    return
  fi
  command
}

function adb_wait_with_recovery() {
  adb_wait_for_device_with_recovery
  if [ ${is_adb_device_connected} != true ]; then
    print_log "ADB device '${DEVICE_SERIAL}' is not connected. Exiting"
    exit 1
  fi
}
