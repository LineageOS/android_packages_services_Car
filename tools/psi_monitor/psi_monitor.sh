#! /system/bin/sh
#
# Monitors PSI and detects system readiness post CUJ completion.
#
# Setup required for the script to run:
#   adb push psi_monitor.sh /data/local/tmp/psi_monitor.sh
#   adb shell chmod 755 /data/local/tmp/psi_monitor.sh
#   adb shell mkdir -p /data/local/tmp/psi_monitor
#   adb root; adb shell setenforce 0;
#
# Examples:
#   adb shell psi_monitor.sh --no_detect_baseline --out_dir=/data/local/tmp/psi_monitor
#   adb shell psi_monitor.sh -d 120 -t 30 --exit_on_psi_stabilized
#   adb shell psi_monitor.sh -d 120 -t 30 -b 5 -m 5
#
# Incoming signal:
#   - PSI monitor waits for persist.debug.psi_monitor.cuj_completed to be true before starting
#     the monitoring for PSI changes.
#
# Outgoing signals:
#   - PSI monitor sets persist.debug.psi_monitor.threshold_met to true when the PSI exceeds the
#     threshold and drop below the threshold again after the CUJ is completed.
#   - PSI monitor sets persist.debug.psi_monitor.baseline_met to true when the PSI is stable
#     (defined by MAX_PSI_BASE_POINT_DIFF) across the last N PSI entries (defined by
#     TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE) after the CUJ is completed.

readonly PSI_AVG10_POSITION=2
PSI_AVG10_THRESHOLD=80
# Baseline should determined only when the PSI is below 50.
MAX_BASELINE_PSI=50
TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE=10
MAX_PSI_BASE_POINT_DIFF=5

SHOULD_DYNAMICALLY_DETECT_BASELINE=true
MAX_DURATION_SECONDS=120  # 2 minutes
OUTPUT_DIR=/data/local/tmp/psi_monitor
DID_CUJ_COMPLETE=false
EXIT_ON_PSI_STABILIZED=false

alias logcat_log=log
alias did_cuj_complete='getprop persist.debug.psi_monitor.cuj_completed'
alias reset_threshold_met='setprop persist.debug.psi_monitor.threshold_met false'
alias set_threshold_met='setprop persist.debug.psi_monitor.threshold_met true'
alias reset_baseline_met='setprop persist.debug.psi_monitor.baseline_met false'
alias set_baseline_met='setprop persist.debug.psi_monitor.baseline_met true'

set -e
function trap_error() {
    local return_value=$?
    local line_no=$1
    echo "Error at line ${line_no}: \"${BASH_COMMAND}\"" > ${LOG_FILE}
    echo "Return value ${return_value}" > ${LOG_FILE}
    exit ${return_value}
}
trap 'trap_error $LINENO' ERR


function uptime_millis() {
  seconds=$(cat /proc/uptime | cut -f1 -d" ")
  echo "(${seconds}*1000)/1" | bc
}

function err() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S%z')] [$(uptime_millis)]: $*" | tee -a ${LOG_FILE} >&2
  logcat_log -t "$0" -p e "$@"
}

function print_log() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S%z')] [$(uptime_millis)]: $*" | tee -a ${LOG_FILE}
  logcat_log -t "$0" -p i "$@"
}

function usage() {
  echo "Monitors PSI and detects system readiness post CUJ completion."
  echo "Usage: psi_monitor.sh [--no_detect_baseline] [--out_dir <output_dir>] "\
       "[--max_duration_seconds <max_duration_seconds>] "\
       "[--psi_avg10_threshold <psi_avg10_threshold>] "\
       "[--last_n_psi_entries_to_monitor_baseline <last_n_psi_entries_to_monitor_baseline>] "\
       "[--max_psi_base_point_diff <max_psi_base_point_diff>] --exit_on_psi_stabilized"
  echo "--no_detect_baseline: Instruct the monitor to not wait for the PSI to reach "\
       "a baseline value"
  echo "-o|--out_dir: Location to output the psi dump and logs"
  echo "-d|--max_duration_seconds: Maximum duration to monitor for PSI changes"
  echo "-t|--psi_avg10_threshold: PSI threshold level for peak CPU activity during post CUJ"
  echo "-b|--total_psi_entries_to_monitor_baseline: Last N PSI entries to monitor to determine "\
       "baseline"
  echo "-m|--max_psi_base_point_diff: Max PSI diff between the min and max PSI values to "\
       "determine baseline"
  echo "--exit_on_psi_stabilized: Exit the monitor when the PSI is stabilized"
}

function parse_arguments() {
  # Set the above threshold / duration / out_dir / dynamicall detect baseline by parsing
  # the arguments.
  while [[ $# > 0 ]]; do
    key="$1"
    case $key in
      -h|--help)
        usage
        exit 1;;
      --no_detect_baseline)
        SHOULD_DYNAMICALLY_DETECT_BASELINE=false
        ;;
      -o|--out_dir)
        OUTPUT_DIR=$2
        shift;;
      -d|--max_duration_seconds)
        MAX_DURATION_SECONDS=$2
        shift;;
      -t|--psi_avg10_threshold)
        PSI_AVG10_THRESHOLD=$2
        shift;;
      -b|--total_psi_entries_to_monitor_baseline)
        TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE=$2
        shift;;
      -m|--max_psi_base_point_diff)
        MAX_PSI_BASE_POINT_DIFF=$2
        shift;;
      --exit_on_psi_stabilized)
        EXIT_ON_PSI_STABILIZED=true
        shift;;
      *)
        err "Invalid option ${1}"
        usage
        exit 1;;
    esac
    shift # past argument or value
  done
}

function print_arguments() {
  print_log "Command line args:"
  print_log "\t SHOULD_DYNAMICALLY_DETECT_BASELINE=${SHOULD_DYNAMICALLY_DETECT_BASELINE}"
  print_log "\t OUTPUT_DIR=${OUTPUT_DIR}"
  print_log "\t MAX_DURATION_SECONDS=${MAX_DURATION_SECONDS}"
  print_log "\t PSI_AVG10_THRESHOLD=${PSI_AVG10_THRESHOLD}"
  print_log "\t TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE=${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE}"
  print_log "\t MAX_PSI_BASE_POINT_DIFF=${MAX_PSI_BASE_POINT_DIFF}"
  print_log "\t EXIT_ON_PSI_STABILIZED=${EXIT_ON_PSI_STABILIZED}"
}

function check_arguments() {
  readonly OUTPUT_DIR
  if [[ ! -d ${OUTPUT_DIR} ]]; then
    err "Out dir ${OUTPUT_DIR} does not exist"
    exit 1
  fi

  readonly DUMP_FILE=${OUTPUT_DIR}/psi_dump.txt
  readonly LOG_FILE=${OUTPUT_DIR}/log.txt
  rm -f ${DUMP_FILE}; touch ${DUMP_FILE}
  rm -f ${LOG_FILE}; touch ${LOG_FILE}

  if [[ ! -w ${DUMP_FILE} ]]; then
    err "Dump file ${DUMP_FILE} is not writable"
    exit 1
  fi
  if [[ ! -w ${LOG_FILE} ]]; then
    err "Log file ${LOG_FILE} is not writable"
    exit 1
  fi

  readonly PSI_AVG10_THRESHOLD
  if [[ ${PSI_AVG10_THRESHOLD} != +([[:digit:]]) || ${PSI_AVG10_THRESHOLD} -le 0 \
        || ${PSI_AVG10_THRESHOLD} -ge 100 ]]; then
    err "PSI Avg10 threshold ${PSI_AVG10_THRESHOLD} is not a valid number. The value should be "\
        "between 1 and 99"
    exit 1
  fi

  if [[ ${PSI_AVG10_THRESHOLD} -lt ${MAX_BASELINE_PSI} ]]; then
    print_log "Setting max baseline PSI to ${PSI_AVG10_THRESHOLD}, which is the PSI threshold"
    MAX_BASELINE_PSI=${PSI_AVG10_THRESHOLD}
  fi
  readonly MAX_BASELINE_PSI

  readonly MAX_DURATION_SECONDS
  if [[ ${MAX_DURATION_SECONDS} != +([[:digit:]]) || ${MAX_DURATION_SECONDS} -lt 10 \
        || ${MAX_DURATION_SECONDS} -gt 3600 ]]; then
    err "Max duration seconds ${MAX_DURATION_SECONDS} is not a valid number. The value should be"\
        "between 10 and 3600"
    exit 1
  fi

  readonly TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE
  if [[ ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} != +([[:digit:]]) \
        || ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} -lt 5 \
        || ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} -gt 10 ]]; then
    err "Last N PSI entries to monitor baseline ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} is not "\
        "a valid number. The value should be between 5 and 10"
    exit 1
  fi

  readonly MAX_PSI_BASE_POINT_DIFF
  if [[ ${MAX_PSI_BASE_POINT_DIFF} != +([[:digit:]]) || ${MAX_PSI_BASE_POINT_DIFF} -lt 1 \
        || ${MAX_PSI_BASE_POINT_DIFF} -gt 10 ]]; then
    err "Max PSI base point diff ${MAX_PSI_BASE_POINT_DIFF} is not a valid number. The value "\
        "should be between 1 and 10"
    exit 1
  fi
}

latest_psi_avg_10=0
latest_psi_line=""
function read_psi_avg() {
  latest_psi_line=$(grep . /proc/pressure/* | tr '\n' ' ' | sed 's|/proc/pressure/||g')
  local cpu_some_line=$(echo ${latest_psi_line} | sed 's/\(total=[0-9]*\) /\1\n/g' \
                        | grep "cpu:some")
  latest_psi_avg_10=$(echo ${cpu_some_line} | cut -f${PSI_AVG10_POSITION} -d' ' \
    | cut -f2 -d'=' | cut -f1 -d'.')
  if [[ ${latest_psi_avg_10} != +([[:digit:]]) ]]; then
    err "Error reading PSI. Read value ${latest_psi_avg_10}"
    exit 1
  fi
}

EXCEEDED_THRESHOLD_UPTIME_MILLIS=-1
DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS=-1
readonly PSI_TYPE_CPU_SOME="cpu:some avg10"
function populate_exceeded_and_dropped_below_threshold() {
  local psi_avg10=${1}
  if [[ ${EXCEEDED_THRESHOLD_UPTIME_MILLIS} -lt 0 && ${psi_avg10} -ge ${PSI_AVG10_THRESHOLD} ]]
  then
    EXCEEDED_THRESHOLD_UPTIME_MILLIS=$(uptime_millis)
    echo -n " \"PSI exceeded threshold: ${PSI_AVG10_THRESHOLD}% ${PSI_TYPE_CPU_SOME}\"" \
      >> ${DUMP_FILE}
    return
  fi
  if [[ ${EXCEEDED_THRESHOLD_UPTIME_MILLIS} -gt 0 && ${psi_avg10} -lt ${PSI_AVG10_THRESHOLD} ]]
  then
    DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS=$(uptime_millis)
    echo -n " \"PSI dropped below threshold: ${PSI_AVG10_THRESHOLD}% ${PSI_TYPE_CPU_SOME}\"" \
      >> ${DUMP_FILE}
  fi
}

function check_exceed_and_drop_below_threshold() {
  if [[ ${EXCEEDED_THRESHOLD_UPTIME_MILLIS} -gt 0 \
        && ${DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS} -gt 0 ]]; then
    return
  fi
  populate_exceeded_and_dropped_below_threshold ${@}
  if [[ ${EXCEEDED_THRESHOLD_UPTIME_MILLIS} -gt 0 \
        && ${DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS} -gt 0 ]]; then
    print_log "CPU PSI exceeded threshold ${PSI_AVG10_THRESHOLD} at" \
        "${EXCEEDED_THRESHOLD_UPTIME_MILLIS} and dropped below at" \
        "${DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS}"
    set_threshold_met
    return
  fi
}

LAST_N_PSI_AVG10_ARRAY=()
BASELINE_UPTIME_MILLIS=-1
NEXT_ELEMENT_TO_REMOVE=0
function monitor_baseline_psi() {
  if [[ ${1} -gt ${MAX_BASELINE_PSI} ||  ${SHOULD_DYNAMICALLY_DETECT_BASELINE} == false \
        || ${BASELINE_UPTIME_MILLIS} -gt 0 ]]; then
    return
  fi
  LAST_N_PSI_AVG10_ARRAY+=($1)
  length=${#LAST_N_PSI_AVG10_ARRAY[@]}
  if [[ ${length} -lt ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} ]]; then
    return
  elif [[ ${length} -gt ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} ]]; then
    unset 'LAST_N_PSI_AVG10_ARRAY[NEXT_ELEMENT_TO_REMOVE]'
    NEXT_ELEMENT_TO_REMOVE=$(expr ${NEXT_ELEMENT_TO_REMOVE} + 1)
  fi
  psi_min=$(echo ${LAST_N_PSI_AVG10_ARRAY[@]} | tr ' ' '\n' | sort -nr | tail -n1)
  psi_max=$(echo ${LAST_N_PSI_AVG10_ARRAY[@]} | tr ' ' '\n' | sort -nr | head -n1)

  if [[ `expr ${psi_max} - ${psi_min}` -gt ${MAX_PSI_BASE_POINT_DIFF} ]]; then
    return
  fi
  BASELINE_UPTIME_MILLIS=$(uptime_millis)
  print_log "PSI baseline is stable across ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE} entries. "\
            "Min / Max / Latest PSI: [${psi_min}, ${psi_max}, ${1}]"
  echo -n " \"PSI reached baseline across latest ${TOTAL_PSI_ENTRIES_TO_MONITOR_BASELINE}" \
          "entries\"" >> ${DUMP_FILE}
  set_baseline_met
  return
}

function main() {
  parse_arguments "$@"
  print_arguments
  check_arguments
  reset_threshold_met
  reset_baseline_met

  if [[ ${EXIT_ON_PSI_STABILIZED} == true ]]; then
    print_log "Starting CPU PSI monitoring. Will exit when PSI is stabilized or after"\
              "${MAX_DURATION_SECONDS} seconds"
  else
    print_log "Starting CPU PSI monitoring. Will exit after ${MAX_DURATION_SECONDS} seconds"
  fi

  start_uptime_millis=$(uptime_millis)
  max_uptime_millis=`echo "${start_uptime_millis} + (${MAX_DURATION_SECONDS} * 1000)" | bc`
  cuj_completion_uptime_millis=-1

  while [[ $(uptime_millis) -lt ${max_uptime_millis} ]]; do
    read_psi_avg

    echo -n "$(uptime_millis) $(date '+%Y-%m-%d %H:%M:%S.%N') ${latest_psi_line}" >> ${DUMP_FILE}

    if [[ ${cuj_completion_uptime_millis} -gt 0 || $(did_cuj_complete) == true ]]; then
      if [[ ${cuj_completion_uptime_millis} == -1 ]]; then
        cuj_completion_uptime_millis=$(uptime_millis)
        echo -n " \"CUJ completed\"" >> ${DUMP_FILE}
      fi
      check_exceed_and_drop_below_threshold ${latest_psi_avg_10}
      monitor_baseline_psi ${latest_psi_avg_10}
    fi
    if [[ ${EXCEEDED_THRESHOLD_UPTIME_MILLIS} -gt 0
          && ${DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS} -gt 0
          && ( ${SHOULD_DYNAMICALLY_DETECT_BASELINE} == false || ${BASELINE_UPTIME_MILLIS} -gt 0 )
          && ${EXIT_ON_PSI_STABILIZED} == true ]]; then
          print_log "Stopping on psi stabilized"
          break
    fi
    echo "" >> ${DUMP_FILE}
    sleep 1
  done

  if [[ ${cuj_completion_uptime_millis} -le 0 ]]; then
    print_log "CUJ did not complete"
  else
    print_log "CUJ completed at ${cuj_completion_uptime_millis}"
    if [[ ${EXCEEDED_THRESHOLD_UPTIME_MILLIS} -gt 0
          && ${DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS} -gt 0 ]]; then
      print_log "CPU PSI exceeded threshold at ${EXCEEDED_THRESHOLD_UPTIME_MILLIS} and dropped"\
                "below threshold at ${DROPPED_BELOW_THRESHOLD_UPTIME_MILLIS}"
    fi
    if [[ ${SHOULD_DYNAMICALLY_DETECT_BASELINE} == true && ${BASELINE_UPTIME_MILLIS} -gt 0 ]]; then
      print_log "CPU PSI reached baseline at ${BASELINE_UPTIME_MILLIS}"
    elif [[ ${SHOULD_DYNAMICALLY_DETECT_BASELINE} == true ]]; then
      print_log "CPU PSI did not reach baseline. Last N PSI values: ${LAST_N_PSI_AVG10_ARRAY[@]}"
    fi
  fi
}

main "$@"
