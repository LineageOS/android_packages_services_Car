#!/bin/bash
#
# Runs a CUJ and collects PSI measurement.
# Supported CUJs:
#   - Bootup
#   - User switch
#   - App startup
#   - Bootup with app launch
#   - Resume (Work in progress)
set -E
function trap_error() {
    local return_value=$?
    local line_no=$1
    echo "Error at line ${line_no}: \"${BASH_COMMAND}\". Return code ${return_value}" \
          | tee -a ${OUTPUT_DIR}/fatal_log.txt
    pkill -P $$
    aae_device_recover
}
trap 'trap_error $LINENO' ERR

function clean_exit() {
  execute_on_adb_connect "adb logcat -G 64K -b all -c"
}
trap 'clean_exit' EXIT

readonly PSI_MONITOR_REPO_DIR="packages/services/Car/tools/psi_monitor"
readonly ADB_WAIT_FOR_DEVICE_SCRIPT="adb_wait_for_device.sh"
readonly PSI_MONITOR_SCRIPT="psi_monitor.sh"
readonly PARSE_SCRIPT="parse_psi_events.py"
readonly PLOT_SCRIPT="psi_plot.py"
readonly GENERATE_KPIS_SCRIPT="generate_kpis.py"
function setup_local_dir() {
  if [[ -z ${ANDROID_BUILD_TOP} ]]; then
    readonly LOCAL_SCRIPT_DIR=$(dirname ${0})
  else
    readonly LOCAL_SCRIPT_DIR=${ANDROID_BUILD_TOP}/${PSI_MONITOR_REPO_DIR}
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${ADB_WAIT_FOR_DEVICE_SCRIPT} ]]; then
    echo -e "${ADB_WAIT_FOR_DEVICE_SCRIPT} script not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit 1
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${PSI_MONITOR_SCRIPT} ]]; then
    echo -e "PSI monitor script ${PSI_MONITOR_SCRIPT} not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit 1
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${PARSE_SCRIPT} ]]; then
    echo -e "PSI parse script ${PARSE_SCRIPT} not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${PLOT_SCRIPT} ]]; then
    echo -e "${PLOT_SCRIPT} script not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit 1
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${GENERATE_KPIS_SCRIPT} ]]; then
    echo -e "${GENERATE_KPIS_SCRIPT} script not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit 1
  fi
}

setup_local_dir
source ${LOCAL_SCRIPT_DIR}/${ADB_WAIT_FOR_DEVICE_SCRIPT}

readonly DEVICE_OUT_DIR="/data/local/tmp/psi_monitor"
readonly DEVICE_SCRIPT="/data/local/tmp/psi_monitor.sh"
readonly MIN_BOOTUP_DURATION_SECONDS=60
readonly MIN_USER_SWITCH_DURATION_SECONDS=30
readonly POST_APP_STARTUP_MONITOR_DURATION_SECONDS=30
readonly POST_BOOT_APPS_LAUNCH_TIMES=5
readonly MIN_BOOT_WITH_APP_LAUNCH_DURATION_SECONDS=90
readonly TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE=10

OUTPUT_DIR=${PWD}/out
PSI_AVG10_THRESHOLD=80
MAX_PSI_MONITOR_DURATION_SECONDS=120
PSI_MONITOR_GRACE_DURATION_SECONDS=20
CUJ=""
USER_SWITCH_ARGS=""
APP_STARTUP_PACKAGE=""
APP_STARTUP_TIMES=1
POST_BOOT_APP_LAUNCH_STATE="threshold_met"
MAX_WAIT_FOR_FIRST_CAR_LAUNCHER_DISPLAYED_LOG_MILLISECONDS=1500
SHOW_PLOTS=true
PRINT_CUJ_DESC_AND_EXIT=false

function usage() {
  echo "Runs a CUJ, collects PSI measurement, and generates PSI plots / KPIs ."
  echo "Usage: run_cuj.sh [-o|--out_dir <output_dir>]"\
       "[-d|--max_duration_seconds <max_duration_seconds>]"\
       "[-g|--grace_duration_seconds <grace_duration_seconds>]"\
       "[-b|--bootup] [-u|--user_switch [<to_user_id> | <from_user_id>-<to_user_id>]]"\
       "[-a|--app_startup <app_package_name>] [--app_startup_times <app_startup_times>]"\
       "[-r|--resume]"\
       "[--bootup_with_app_launch <post_boot_app_launch_state>]"

  echo "-o|--out_dir: Location to output the psi dump and logs"
  echo "-t|--psi_avg10_threshold: PSI threshold level for peak CPU activity during post boot"
  echo "-d|--max_duration_seconds: Maximum duration to monitor for PSI changes"
  echo "-g|--grace_duration_seconds: Grace duration to wait before terminating the monitor"
  echo "-b|--bootup: Run bootup CUJ"
  echo "-u|--user_switch: Run user switch CUJ"
  echo "-r|--resume: Run resume from suspend CUJ"
  echo "-a|--app_startup: Run app startup CUJ"
  echo "--app_startup_times: Number of times to launch the app during app startup CUJ"
  echo "--bootup_with_app_launch: Run bootup with app launch CUJ. Must provide post boot app"\
       "launch state, which is the post boot state to launch apps. Following states are supported:"
  echo "\t- threshold_met: Launch apps when PSI threshold is met"
  echo "\t- baseline_met: Launch apps when PSI baseline is met"
  echo "\t- immediate: Launch apps immediately after launcher is displayed"
  echo "\--no_show_plots: Don't show plots at the end of the run. Useful when running this script "\
       "in a loop"
  echo "--print_cuj_desc: Prints the CUJ description and exits"
  echo "-h|--help: Show this help message"

  echo "Example commands:"
  echo -e "\t1. ./run_cuj.sh -d 90 -g 20 -t 20 --bootup_with_app_launch threshold_met"
  echo -e "\t2. ./run_cuj.sh -d 90 -g 20 -t 20 --bootup_with_app_launch baseline_met"
  echo -e "\t3. ./run_cuj.sh -d 90 -g 20 -a com.android.contacts --app_startup_times 10"
  echo -e "\t4. ./run_cuj.sh -d 90 -g 20 -u 10-11"
  echo -e "\t5. ./run_cuj.sh -d 90 -g 20 -t 20 -b"
}

function usage_err() {
  err "$@\n"
  usage
}

function check_and_set_cuj() {
  if [[ ! -z ${CUJ} ]]; then
    usage_err "Multiple CUJs specified on the command line: ${CUJ} and ${1}"
    exit 1
  fi
  CUJ=$1
}

function parse_arguments() {
  while [[ $# > 0 ]]; do
    key="$1"
    case $key in
    -h|--help)
        usage
        exit 1;;
    -o|--out_dir)
      OUTPUT_DIR=${2}
      shift;;
    -t|--psi_avg10_threshold)
      PSI_AVG10_THRESHOLD=${2}
      shift;;
    -d|--max_duration_seconds)
      MAX_PSI_MONITOR_DURATION_SECONDS=${2}
      shift;;
    -g|--grace_duration_seconds)
      PSI_MONITOR_GRACE_DURATION_SECONDS=${2}
      shift;;
    -b|--bootup)
      check_and_set_cuj "bootup"
      ;;
    -u|--user_switch)
      check_and_set_cuj "user-switch"
      USER_SWITCH_ARGS=${2}
      shift;;
    -a|--app_startup)
      check_and_set_cuj "app-startup"
      APP_STARTUP_PACKAGE=${2}
      shift;;
    --app_startup_times)
      APP_STARTUP_TIMES=${2}
      shift;;
    -r|--resume)
      check_and_set_cuj "resume"
      ;;
    --bootup_with_app_launch)
      check_and_set_cuj "bootup-with-app-launch"
      POST_BOOT_APP_LAUNCH_STATE=${2}
      shift;;
    --no_show_plots)
      SHOW_PLOTS=false
      ;;
    --print_cuj_desc)
      PRINT_CUJ_DESC_AND_EXIT=true
      ;;
    *)
      echo "${0}: Invalid option '${1}'"
      usage
      exit 1;;
    esac
    shift # past argument or value
  done
}

function print_log() {
  if [[ ${PRINT_CUJ_DESC_AND_EXIT} == true ]]; then
    return
  fi
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S%z')] ${@}" | tee -a ${OUTPUT_DIR}/run_cuj_log.txt
}

function err() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S%z')] $@" >&2 | tee -a ${OUTPUT_DIR}/run_cuj_err.txt
}

function check_arguments() {
  readonly OUTPUT_DIR
  mkdir -p ${OUTPUT_DIR}
  if [[ ! -d ${OUTPUT_DIR} ]]; then
    usage_err "Out dir ${OUTPUT_DIR} does not exist"
    exit 1
  fi

  readonly PSI_AVG10_THRESHOLD
  if [[ ${PSI_AVG10_THRESHOLD} != +([[:digit:]]) || ${PSI_AVG10_THRESHOLD} -le 0 \
        || ${PSI_AVG10_THRESHOLD} -ge 100 ]]; then
    usage_err "PSI Avg10 threshold ${PSI_AVG10_THRESHOLD} is not a valid number. The value should"\
        "be between 1 and 99"
    exit 1
  fi

  if [[ ${MAX_PSI_MONITOR_DURATION_SECONDS} != +([[:digit:]]) \
        || ${MAX_PSI_MONITOR_DURATION_SECONDS} -lt 10 \
        || ${MAX_PSI_MONITOR_DURATION_SECONDS} -gt 3600 ]]; then
    usage_err "Max psi monitor duration seconds ${MAX_PSI_MONITOR_DURATION_SECONDS} is not"\
        "a valid number. The value should be between 10 and 3600"
    exit 1
  fi

  readonly PSI_MONITOR_GRACE_DURATION_SECONDS
  if [[ ${PSI_MONITOR_GRACE_DURATION_SECONDS} != +([[:digit:]]) \
        || ${PSI_MONITOR_GRACE_DURATION_SECONDS} -lt 10 \
        || ${PSI_MONITOR_GRACE_DURATION_SECONDS} -gt 3600 ]]; then
    usage_err "Psi monitor grace duration seconds ${PSI_MONITOR_GRACE_DURATION_SECONDS} is not"\
        "a valid number. The value should be between 10 and 3600"
    exit 1
  fi

  if [[ -z ${CUJ} ]]; then
    CUJ="bootup"
    print_log "CUJ not specified, defaulting to bootup"
  fi

  readonly CUJ
}

ALL_USER_IDS=()
##
#  Reads user ids from the device and sets the global variable ALL_USER_IDS.
#
# Parses the output of "adb shell pm list users", which is in the format of
# """
# Users:
#       UserInfo{0:Driver:813} running
#       UserInfo{10:Driver:412} running
# """
function read_all_user_ids() {
  IFS=' ' read -r ALL_USER_IDS <<< $(adb shell pm list users | grep "{" | cut -d'{' -f2 \
                                     | cut -d':' -f1 | tr '\n' ' ')
}

FROM_USER_ID=""
TO_USER_ID=""
function check_cuj_args() {
  case ${CUJ} in
    bootup)
      if [[ ${MAX_PSI_MONITOR_DURATION_SECONDS} -lt ${MIN_BOOTUP_DURATION_SECONDS} ]]; then
        MAX_PSI_MONITOR_DURATION_SECONDS=${MIN_BOOTUP_DURATION_SECONDS}
        print_log "Max psi monitor duration seconds is set to ${MAX_PSI_MONITOR_DURATION_SECONDS}"\
             "to accommodate bootup CUJ"
      fi
      ;;
    user-switch)
      user_switch_args=()
      IFS='-' read -r -a user_switch_args <<< "${USER_SWITCH_ARGS}"
      if [[ ${#user_switch_args[@]} -lt 1 ||${#user_switch_args[@]} -gt 2 ]]; then
        usage_err "Invalid user switch args: ${USER_SWITCH_ARGS}. It should be in the format of" \
            "<to_user_id> or <from_user_id>-<to_user_id>"
        exit 1
      elif [[ ${#user_switch_args[@]} -eq 2 ]]; then
        FROM_USER_ID=${user_switch_args[0]}
        TO_USER_ID=${user_switch_args[1]}
      else
        FROM_USER_ID=$(adb shell am get-current-user)
        TO_USER_ID=${user_switch_args[0]}
      fi

      if [[ ${FROM_USER_ID} -eq 0 || ${TO_USER_ID} -eq 0 ]]; then
        err "From user id and to user id should be non-zero or non system users"
        exit 1
      elif [[ ${FROM_USER_ID} -eq ${TO_USER_ID} ]]; then
        err "From user id and to user id should be different"
        exit 1
      fi

      read_all_user_ids
      if [[ ! " ${ALL_USER_IDS[@]} " =~ " ${FROM_USER_ID} " ]]; then
        err "From user id ${FROM_USER_ID} is not a valid user id."\
            "Valid user ids are: ${ALL_USER_IDS[@]}"
        exit 1
      elif [[ ! " ${ALL_USER_IDS[@]} " =~ " ${TO_USER_ID} " ]]; then
        err "To user id ${TO_USER_ID} is not a valid user id."\
            "Valid user ids are: ${ALL_USER_IDS[@]}"
        exit 1
      fi
      if [[ ${MAX_PSI_MONITOR_DURATION_SECONDS} -lt ${MIN_USER_SWITCH_DURATION_SECONDS} ]]; then
        MAX_PSI_MONITOR_DURATION_SECONDS=${MIN_USER_SWITCH_DURATION_SECONDS}
        print_log "Max psi monitor duration seconds is set to ${MAX_PSI_MONITOR_DURATION_SECONDS}"\
             "to accommodate user switch CUJ"
      fi
      ;;
    app-startup)
      if [[ -z ${APP_STARTUP_PACKAGE} ]]; then
        usage_err "App package name is not specified for app startup CUJ"
        exit 1
      fi
      if [[ ${APP_STARTUP_TIMES} != +([[:digit:]]) || ${APP_STARTUP_TIMES} -le 0
          || ${APP_STARTUP_TIMES} -gt 100 ]]; then
        usage_err "App startup times should be positive integer between 1 and 100"
        exit 1
      fi
      min_duration=$(echo "${APP_STARTUP_TIMES}
                      + ${POST_APP_STARTUP_MONITOR_DURATION_SECONDS}" | bc)
      if [[ ${MAX_PSI_MONITOR_DURATION_SECONDS} -lt ${min_duration} ]]; then
        MAX_PSI_MONITOR_DURATION_SECONDS=${min_duration}
        print_log "Max psi monitor duration seconds is set to ${MAX_PSI_MONITOR_DURATION_SECONDS}"\
             "to accommodate app startup CUJ"
      fi
      ;;
    bootup-with-app-launch)
      if [[ ${POST_BOOT_APP_LAUNCH_STATE} != "threshold_met" && \
            ${POST_BOOT_APP_LAUNCH_STATE} != "baseline_met" && \
            ${POST_BOOT_APP_LAUNCH_STATE} != "immediate" ]]; then
        usage_err "Post boot app launch state should be either threshold_met, baseline_met or"\
                  "immediate"
        exit 1
      fi
      if [[ ${MAX_PSI_MONITOR_DURATION_SECONDS} -lt ${MIN_BOOT_WITH_APP_LAUNCH_DURATION_SECONDS} ]]
      then
        MAX_PSI_MONITOR_DURATION_SECONDS=${MIN_BOOT_WITH_APP_LAUNCH_DURATION_SECONDS}
        print_log "Max psi monitor duration seconds is set to ${MAX_PSI_MONITOR_DURATION_SECONDS}"\
             "to accommodate bootup with app launch CUJ"
      fi
  esac
}

function setup() {
  adb_wait_with_recovery
  fetch_device_info
  adb shell rm -rf ${DEVICE_SCRIPT} ${DEVICE_OUT_DIR}
  adb push ${LOCAL_SCRIPT_DIR}/${PSI_MONITOR_SCRIPT} ${DEVICE_SCRIPT}
  adb shell chmod 755 ${DEVICE_SCRIPT}
  adb shell mkdir -p ${DEVICE_OUT_DIR}
  adb shell setprop persist.debug.psi_monitor.cuj_completed false
  adb logcat -G 16M -b all -c
}

FROM_USER_STOPPED_COUNT=0
function get_from_user_stopped_count() {
  echo $(adb logcat -d -b events | grep "ssm_user_stopped: ${FROM_USER_ID}" | wc -l)
}

# This function is called before starting the CUJ. It performs any setup required for the CUJ.
# This function should leave the device in a state where the adb connection is active.
function pre_start_cuj() {
  case ${CUJ} in
    bootup | bootup-with-app-launch)
      print_log "Rebooting adb device"; adb reboot
      adb_wait_with_recovery
      adb logcat -G 16M
      ;;
    user-switch)
      current_user_id=$(adb shell am get-current-user)
      if [[ ${current_user_id} -eq ${FROM_USER_ID} ]]; then
        return
      else
        print_log "Switching user to from user ${FROM_USER_ID}, which is different from current user"\
             "${current_user_id}"
        adb shell am switch-user ${FROM_USER_ID}
        print_log "Waiting for user switch to complete by sleeping for 60 seconds"
        sleep 60
        # User switch events from previous switches should be ignored. So, get the number of
        # user switch events from the previous run. Then use this to track the current user switch
        # event.
        FROM_USER_STOPPED_COUNT=$(get_from_user_stopped_count)
      fi
      ;;
    resume)
      err "Resume is not yet supported. Device needs an extra board to support this."\
          "Refer to http://go/seahawk-str"; exit 1
      adb shell cmd car_service hibernate --auto
      adb_wait_with_recovery
      ;;
    app-startup)
      res=$(adb shell pm list packages ${APP_STARTUP_PACKAGE})
      if [[ -z ${res} ]]; then
        err "App package ${APP_STARTUP_PACKAGE} is not installed on the device"
        exit 1
      elif [[ ! ${res} =~ ^package:${APP_STARTUP_PACKAGE}.* ]]; then
        err "Multiple app packages with prefix ${APP_STARTUP_PACKAGE} are installed on the device"
        exit 1
      else
        APP_STARTUP_PACKAGE=$(echo ${res} | cut -d':' -f2)
      fi
      # Reset the device to the home screen before starting the CUJ.
      adb shell am start com.android.car.carlauncher/.CarLauncher
      ;;
    *)
      ;;
  esac
}

function fetch_logs() {
  rm -f ${OUTPUT_DIR}/filtered_logcat.txt
  while true; do
    adb_wait_with_recovery
    adb logcat -v year -s ActivityTaskManager,SystemServerTimingAsync \
      >> ${OUTPUT_DIR}/filtered_logcat.txt
  done
}

function run_psi_monitor() {
  set +e
  readonly TERM_SIGNAL_TIMEOUT=$(echo "${MAX_PSI_MONITOR_DURATION_SECONDS} \
                        + ${PSI_MONITOR_GRACE_DURATION_SECONDS}" | bc)
  readonly KILL_SIGNAL_DURATION=${PSI_MONITOR_GRACE_DURATION_SECONDS}
  print_log "Starting PSI monitoring with timeout ${TERM_SIGNAL_TIMEOUT}s and kill duration"\
            "${KILL_SIGNAL_DURATION}s"
  timeout --preserve-status -k ${KILL_SIGNAL_DURATION} ${TERM_SIGNAL_TIMEOUT} \
    adb shell ${DEVICE_SCRIPT} --out_dir ${DEVICE_OUT_DIR} \
      --psi_avg10_threshold ${PSI_AVG10_THRESHOLD} \
      --max_duration_seconds ${MAX_PSI_MONITOR_DURATION_SECONDS} \
      --total_psi_entries_to_monitor_baseline ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} \
        > ${OUTPUT_DIR}/psi_monitor_log.txt 2> ${OUTPUT_DIR}/psi_monitor_log.txt
  exit_status=$?
  print_log "psi_monitor.sh timeout command exit status is '${exit_status}'"
  set -e
  return ${exit_status}
}

function start_activity() {
  if [ $# -eq 1 ]; then
    package=$1
  elif [ $# -eq 2 ]; then
    action=$1
    component=$2
  else
    action=$1
    category=$2
    flag=$3
    component=$4
  fi

  if [ $# -eq 1 ]; then
    print_log "Starting activity for ${package}"
    print_log $(adb shell am start -W --user current ${package})
    sleep 1 # Wait to allow the package to perform some init tasks.
    return
  fi

  print_log "Starting activity ${component}"
  if [ $# -eq 2 ]; then
    print_log $(adb shell am start -W --user current -a ${action} -n ${component})
  else
    print_log $(adb shell am start -W --user current -a ${action} -c ${category} -f ${flag} \
                -n ${component})
  fi
  sleep 1 # Wait to allow the package to perform some init tasks.
}

function start_cuj() {
  case ${CUJ} in
    bootup | bootup-with-app-launch)
      # Boot doesn't need to be started by the script as it is already done by the bootloader
      # as part of the reboot.
      ;;
    user-switch)
      print_log "Switching user to user ${TO_USER_ID}"
      adb shell am switch-user ${TO_USER_ID}
      ;;
    resume)
      # Device will be automatically woken up by the hibernate wakeup event. So, no action is
      # needed.
      ;;
    app-startup)
      print_log "Starting app startup CUJ for ${APP_STARTUP_PACKAGE}"
      for i in $(seq 1 ${APP_STARTUP_TIMES}); do
        adb shell am force-stop --user current ${APP_STARTUP_PACKAGE}
        print_log "Starting app startup CUJ for ${APP_STARTUP_PACKAGE} for the ${i} time"
        start_activity ${APP_STARTUP_PACKAGE}
      done
      ;;
  esac
}

function wait_for_cuj_to_complete() {
  print_log "Waiting for CUJ to complete"
  case ${CUJ} in
    bootup | bootup-with-app-launch)
      while [[ $(adb shell getprop sys.boot_completed) != 1 ]]; do
        sleep 0.5
        # Device connection will be lost intermittently during bootup. So, wait for the device
        # connection after every 0.5 seconds.
        adb_wait_with_recovery
      done
      # PSI monitor script will detect threshold and baseline changes only after the CUJ is
      # completed. So, for boot-with-app-launch CUJ, mark the CUJ as completed on boot completed.
      # This will allow the CUJ to progress forward based on the threshold_met or baseline_met
      # conditions.
      ;;
    user-switch)
      while [[ $(get_from_user_stopped_count) -eq ${FROM_USER_STOPPED_COUNT} ]]; do
        sleep 0.1
      done
      ;;
    resume)
      # When adb is available after the device is woken up, the CUJ is considered as completed. So,
      # no need to wait for any other event.
      ;;
    app-startup)
      # App startup CUJ is considered as completed when all the apps are started. So, no need to
      # wait for any other event.
      ;;
  esac
  print_log "CUJ completed, marking CUJ as completed"
  adb shell setprop persist.debug.psi_monitor.cuj_completed true
}

function wait_for_threshold_met() {
  print_log "Waiting for threshold met"
  while [[ $(adb shell getprop persist.debug.psi_monitor.threshold_met) != true ]]; do
    # The PSI values are read only every 1 seconds by the PSI monitor script. So, wait for 50% of
    # this duration to avoid waiting for too long and polling the device too often.
    sleep 0.5
  done
}

function wait_for_baseline_met() {
  print_log "Waiting for baseline met"
  while [[ $(adb shell getprop persist.debug.psi_monitor.baseline_met) != true ]]; do
    # The PSI values are read only every 1 seconds by the PSI monitor script. So, wait for 50% of
    # this duration to avoid waiting for too long and polling the device too often.
    sleep 0.5
  done
}

function start_launcher_activity() {
  start_activity android.intent.action.MAIN android.intent.category.HOME 0x14000000 \
    com.android.car.carlauncher/.CarLauncher
}

function start_app_grid_activity() {
  start_activity com.android.car.carlauncher.ACTION_APP_GRID android.intent.category.HOME \
    0x24000000 com.android.car.carlauncher/.GASAppGridActivity
}

function start_assistant_activity() {
  start_activity com.google.android.carassistant
}

function stop_assistant_activity() {
  adb shell am force-stop --user current com.google.android.carassistant
}

function start_maps_activity() {
  start_activity android.intent.action.VIEW \
    com.google.android.apps.maps/com.google.android.maps.MapsActivity
}

function stop_maps_activity() {
  adb shell am force-stop --user current com.google.android.apps.maps
}

function start_play_store_activity() {
  start_activity com.android.vending
}

function stop_play_store_activity() {
  adb shell am force-stop --user current com.android.vending
}

function start_contacts_activity() {
  start_activity com.android.contacts
}

function stop_contacts_activity() {
  adb shell am force-stop --user current com.android.contacts
}

function get_component_displayed_count() {
  echo $(grep "ActivityTaskManager: Displayed " ${OUTPUT_DIR}/filtered_logcat.txt | grep ${1} \
         | wc -l)
}

function wait_for_activity_displayed() {
  max_wait_millis=$2
  print_log "Waiting for activity ${1} to be displayed"
  slept_millis=0
  while [[ $(get_component_displayed_count ${1}) -eq 0
           && ${slept_millis} -lt ${max_wait_millis} ]]; do
    sleep 0.1
    slept_millis=$(($slept_millis + 100))
  done
  print_log "Activity ${1} completed"
}

function launch_app_on_post_boot() {
  # Limited maps activity is shown in the car launcher UI. So, force stop maps while in app grid
  # activity to ensure that the maps activity is not shown and the app performs a warm start during
  # the test.
  start_app_grid_activity
  stop_maps_activity
  for i in $(seq 1 ${POST_BOOT_APPS_LAUNCH_TIMES}); do
    print_log "Launching apps on post boot for the ${i}th time"
    # Start apps
    start_assistant_activity
    start_maps_activity
    start_play_store_activity
    start_contacts_activity

    if [[ ${i} -lt ${POST_BOOT_APPS_LAUNCH_TIMES} ]]; then
      # Switch to app grid and force stop apps, so these app can perform warm / cold start during
      # next run instead of bringing the previous activity to the foreground.
      start_app_grid_activity
      stop_assistant_activity
      stop_maps_activity
      stop_play_store_activity
      stop_contacts_activity
    fi
  done
}

function post_cuj_events() {
  case ${CUJ} in
    bootup-with-app-launch)
      if [[ ${POST_BOOT_APP_LAUNCH_STATE} == "threshold_met" ]]; then
        wait_for_threshold_met
      elif [[ ${POST_BOOT_APP_LAUNCH_STATE} == "baseline_met" ]]; then
        wait_for_baseline_met
      else
        # On immediate, wait for CarLauncher to be displayed and sleep for 2 seconds to ensure
        # the device has performed some init for the CarLauncher.
        wait_for_activity_displayed com.android.car.carlauncher/.CarLauncher \
          ${MAX_WAIT_FOR_FIRST_CAR_LAUNCHER_DISPLAYED_LOG_MILLISECONDS}
        sleep 2
      fi
      launch_app_on_post_boot
      ;;
    *)
      ;;
  esac
}

function get_cuj_desc() {
  desc=${CUJ}
  case ${CUJ} in
    bootup)
      desc="Post boot"
      ;;
    bootup-with-app-launch)
      desc="Post boot with app launch after "
      case ${POST_BOOT_APP_LAUNCH_STATE} in
        threshold_met)
          desc+="PSI threshold ${PSI_AVG10_THRESHOLD}% is met)"
          ;;
        baseline_met)
          desc+="PSI reaches baseline across ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} entries"
          ;;
        immediate)
          desc+="car launcher is displayed"
          ;;
        esac
      ;;
    user-switch)
      desc="Post user switch from ${FROM_USER_ID} to ${TO_USER_ID}"
      ;;
    app-startup)
      desc="App startup for ${APP_STARTUP_PACKAGE}"
      ;;
    resume)
      desc="Post resume"
      ;;
  esac
  echo ${desc}
}

function main() {
  set -e
  parse_arguments "$@"
  check_arguments
  check_cuj_args
  if [[ ${PRINT_CUJ_DESC_AND_EXIT} == true ]]; then
    echo $(get_cuj_desc)
    exit
  fi
  setup

  pre_start_cuj
  # Disable SELinux enforcement to allow the PSI monitor script to read the interfaces at
  # /proc/pressure. During pre_start_cuj the device may reboot, so perform these actions only after
  # the device is up.
  adb root; adb_wait_with_recovery
  adb shell setenforce 0

  fetch_logs &
  fetch_logs_pid=$!

  run_psi_monitor &
  psi_monitor_pid=$!

  start_cuj

  wait_for_cuj_to_complete

  post_cuj_events &
  post_cuj_events_pid=$!
  print_log "Triggered post CUJ events in the background at pid ${post_cuj_events_pid}"

  print_log "Waiting for PSI monitoring to complete at pid ${psi_monitor_pid}"
  set +e
  wait ${psi_monitor_pid}
  set -e

  if [[ $(ps -p ${post_cuj_events_pid} > /dev/null) ]]; then
    # If the post CUJ events are still running, kill them because the PSI monitor has completed.
    print_log "Killing post CUJ events at pid ${post_cuj_events_pid}"
    kill ${post_cuj_events_pid}
  fi

  print_log "Killing logcat at pid ${fetch_logs_pid}"
  kill ${fetch_logs_pid}

  adb_wait_with_recovery
  adb pull ${DEVICE_OUT_DIR} ${OUTPUT_DIR}
  psi_monitor_out=${OUTPUT_DIR}/$(basename ${DEVICE_OUT_DIR})
  processed_out=${OUTPUT_DIR}/processed; mkdir -p ${processed_out}

  ${LOCAL_SCRIPT_DIR}/${PARSE_SCRIPT} --psi_dump ${psi_monitor_out}/psi_dump.txt \
    --psi_csv ${processed_out}/psi.csv --logcat ${OUTPUT_DIR}/filtered_logcat.txt \
    --events_csv ${processed_out}/events.csv
  if [[ ${SHOW_PLOTS} == true ]]; then
    ${LOCAL_SCRIPT_DIR}/${PLOT_SCRIPT} --psi_csv ${processed_out}/psi.csv \
      --events_csv ${processed_out}/events.csv --cuj_name "$(get_cuj_desc)" \
      --out_file ${processed_out}/psi_plot.html --show_plot
  else
    ${LOCAL_SCRIPT_DIR}/${PLOT_SCRIPT} --psi_csv ${processed_out}/psi.csv \
      --events_csv ${processed_out}/events.csv --cuj_name "$(get_cuj_desc)" \
      --out_file ${processed_out}/psi_plot.html
  fi
  ${LOCAL_SCRIPT_DIR}/${GENERATE_KPIS_SCRIPT} --psi_csv ${processed_out}/psi.csv \
    --events_csv ${processed_out}/events.csv --out_kpi_csv ${processed_out}/kpis.csv

  # Capture a bugreport for investigation purposes.
  adb bugreport ${OUTPUT_DIR}
}

main "$@"
