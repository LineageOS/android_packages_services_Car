#!/bin/bash
#
# Runs a CUJ multiple times and generates KPIs.

set -E
function trap_error() {
    local return_value=$?
    local line_no=$1
    echo "Error at line ${line_no}: \"${BASH_COMMAND}\"" | tee -a ${OUTPUT_DIR}/fatal_log.txt
    echo "Desc output ${desc}" | tee -a ${OUTPUT_DIR}/fatal_log.txt
    pkill -P $$
}
trap 'trap_error $LINENO' ERR

function clean_exit() {
  adb wait-for-device
  adb logcat -G 64K
  adb shell setprop persist.logd.logpersistd \\\"\\\"
  adb shell setprop persist.logd.logpersistd.enable false
}
trap 'clean_exit' EXIT

readonly PSI_MONITOR_REPO_DIR="packages/services/Car/tools/psi_monitor"
readonly ADB_WAIT_FOR_DEVICE_SCRIPT="adb_wait_for_device.sh"
readonly RUN_CUJ_SCRIPT="run_cuj.sh"
readonly GENERATE_KPI_STATS_SCRIPT="generate_kpi_stats.py"
function setup_local_dir() {
  if [[ -z ${ANDROID_BUILD_TOP} ]]; then
    readonly LOCAL_SCRIPT_DIR=$(dirname ${0})
  else
    readonly LOCAL_SCRIPT_DIR=${ANDROID_BUILD_TOP}/${PSI_MONITOR_REPO_DIR}
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${ADB_WAIT_FOR_DEVICE_SCRIPT} ]]; then
    ehco -e "${ADB_WAIT_FOR_DEVICE_SCRIPT} script not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit 1
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${RUN_CUJ_SCRIPT} ]]; then
    echo -e "PSI monitor script ${RUN_CUJ_SCRIPT} not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit 1
  fi
  if [[ ! -f ${LOCAL_SCRIPT_DIR}/${GENERATE_KPI_STATS_SCRIPT} ]]; then
    echo -e "KPI stats script ${GENERATE_KPI_STATS_SCRIPT} not found in ${LOCAL_SCRIPT_DIR}" >&2
    exit 1
  fi
}

setup_local_dir
source ${LOCAL_SCRIPT_DIR}/adb_wait_for_device.sh

OUTPUT_DIR=${PWD}/out
CUJ_RUNS_OUTPUT_DIR_PREFIX=""
RUN_PREFIX=run
RUN_CUJ_ARGS=""
ITERATIONS=0
TOTAL_FAILURE_RETRIES=0
KPI_CSV_FILE_LOCATION="processed/kpis.csv"

function print_log() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S%z')] $@" | tee -a ${OUTPUT_DIR}/log.txt
}

function err() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S%z')] $@" >&2 | tee -a ${OUTPUT_DIR}/err.txt
}

function usage() {
  echo "Runs a CUJ multiple times and generates KPIs."
  echo "Usage: multi_run_cuj.sh [-o|--out_dir=<output_dir>]"\
       "--run_cuj_args <run_cuj.sh args> [-i|--iterations] <Number of iterations>"\
       "[--retry_any_failure] <Number of times to retry failures>"

  echo "-o|--out_dir: Location to output the psi dump, CSV files, and KPIs"
  echo "--run_cuj_args: Args to pass down to the run_cuj.sh script except output dir argument"
  echo "-i|--iterations: Number of iterations to run the CUJ with run_cuj.sh script"
  echo "--retry_any_failure: Number of times to retry any failure"
  echo -e "\nExample commands:"
  echo -e "\t1. ./multi_run_cuj.sh -o boot_with_threshold_met_app_launch --run_cuj_args \"-d 90 "\
          "-g 20 -t 20 --bootup_with_app_launch threshold_met\" -i 20 --retry_any_failure 5"
  echo -e "\t1. ./multi_run_cuj.sh -o app_launch --run_cuj_args \"-d 90 "\
          "-g 20 -t 5 -a com.android.contacts --app_startup_times 10\" -i 20"
}

function usage_err() {
  err "$@\n"
  usage
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
    --run_cuj_args)
      RUN_CUJ_ARGS=${2}
      shift;;
    -i|--iterations)
      ITERATIONS=${2}
      shift;;
    --retry_any_failure)
      TOTAL_FAILURE_RETRIES=${2}
      shift;;
    *)
      echo "${0}: Invalid option ${1}"
      usage
      exit 1;;
    esac
    shift # past argument or value
  done
}

function check_arguments() {
  readonly OUTPUT_DIR
  mkdir -p ${OUTPUT_DIR}
  if [[ ! -d ${OUTPUT_DIR} ]]; then
    err "Out dir ${OUTPUT_DIR} does not exist"
    exit 1
  fi

  readonly RUN_CUJ_ARGS
  if [[ -z ${RUN_CUJ_ARGS} ]]; then
    echo "Must provide run_cuj.sh arguments with the --run_cuj_args option"
    exit 1
  fi

  readonly ITERATIONS
  if [[ ${ITERATIONS} != +([[:digit:]]) || ${ITERATIONS} -le 0 || ${ITERATIONS} -gt 100 ]]; then
    echo "Iterations ${ITERATIONS} is not a valid number. The values should be between 0 and 100"
  fi

  readonly TOTAL_FAILURE_RETRIES
  if [[ ${TOTAL_FAILURE_RETRIES} != +([[:digit:]]) ]]; then
    echo "Total failure retries ${TOTAL_FAILURE_RETRIES} is not a valid number"
  fi
}

function setup() {
  readonly CUJ_RUNS_OUTPUT_DIR_PREFIX=${OUTPUT_DIR}/"cuj_out/run_"
  adb shell setprop persist.logd.logpersistd logcatd
  adb shell setprop persist.logd.logpersistd.enable true
  # Capture a bugreport for tracking the build details.
  # adb bugreport ${OUTPUT_DIR}
  adb_wait_with_recovery
  fetch_device_info
}

function run_cuj_iterations() {
  total_failure_count=0
  for i in $(seq 1 ${ITERATIONS}); do
    adb_wait_with_recovery
    out_dir=${CUJ_RUNS_OUTPUT_DIR_PREFIX}${i}
    print_log "\n\nRunning iteration ${i}. Output dir ${out_dir}"
    while : ; do
      mkdir -p ${out_dir}
      (./run_cuj.sh ${RUN_CUJ_ARGS} -o ${out_dir} --no_show_plots)
      if [ $? -eq 0 ]; then
        break;
      else
        # Capture a bugreport for investigation purposes.
        adb bugreport ${out_dir}
        mv ${out_dir} ${out_dir}_failure_${total_failure_count}
        pkill -P $$
        if [[ ${total_failure_count} -ge ${TOTAL_FAILURE_RETRIES} ]]; then
          print_log "CUJ run ${i} failed... Total remaining retries is"\
          "((${TOTAL_FAILURE_RETRIES}-${total_failure_count})). Exiting";
          return;
        else
          print_log "CUJ run ${i} failed... Total remaining retries is"\
               "((${TOTAL_FAILURE_RETRIES}-${total_failure_count})). Retrying current failure"
          total_failure_count=$((${total_failure_count}+1))
        fi
      fi
    done
  done
}

function generate_kpi_stats() {
  kpi_csv_files=""
  for i in $(seq 1 ${ITERATIONS}); do
    kpi_csv_file=${CUJ_RUNS_OUTPUT_DIR_PREFIX}${i}/${KPI_CSV_FILE_LOCATION}
    if [[ ! -f ${kpi_csv_file} ]]; then
      err "Expected '${out_dir}' file doesn't exist. Skipping run iteration ${i}"
      continue
    fi
    if [ -z ${kpi_csv_files} ]; then
      kpi_csv_files=${kpi_csv_file}
    else
      kpi_csv_files+=",${kpi_csv_file}"
    fi
  done

  echo -e "#!/bin/bash\n\n${LOCAL_SCRIPT_DIR}/${GENERATE_KPI_STATS_SCRIPT}"\
       "--kpi_csv_files ${kpi_csv_files} --run_id_re \".*\/run_(\d+)\/processed\/kpis.csv\""\
       "--out_file ${OUTPUT_DIR}/kpi_stats.csv" > ${OUTPUT_DIR}/generate_kpi_stats_cmd.sh
  chmod +x ${OUTPUT_DIR}/generate_kpi_stats_cmd.sh

  ${LOCAL_SCRIPT_DIR}/${GENERATE_KPI_STATS_SCRIPT} --kpi_csv_files "${kpi_csv_files}"\
  --run_id_re ".*\/run_(\d+)\/processed\/kpis.csv" --out_file "${OUTPUT_DIR}/kpi_stats.csv"

# Sample command for manual run:
# (export IN_DIR=~/post_boot/cuj_out; export KPIS_CSV=processed/kpis.csv; \
#  ~/android/main/packages/services/Car/tools/psi_monitor/generate_kpi_stats.py --kpi_csv_files \
#  ${IN_DIR}/run_1/${KPIS_CSV},${IN_DIR}/run_2/${KPIS_CSV},${IN_DIR}/run_3/${KPIS_CSV},\
#  ${IN_DIR}/run_4/${KPIS_CSV},${IN_DIR}/run_5/${KPIS_CSV},${IN_DIR}/run_6/${KPIS_CSV},\
#  ${IN_DIR}/run_7/${KPIS_CSV},${IN_DIR}/run_8/${KPIS_CSV},${IN_DIR}/run_9/${KPIS_CSV},\
#  ${IN_DIR}/run_10/${KPIS_CSV},${IN_DIR}/run_11/${KPIS_CSV},${IN_DIR}/run_12/${KPIS_CSV},\
#  ${IN_DIR}/run_13/${KPIS_CSV},${IN_DIR}/run_14/${KPIS_CSV},${IN_DIR}/run_15/${KPIS_CSV},\
#  ${IN_DIR}/run_16/${KPIS_CSV},${IN_DIR}/run_17/${KPIS_CSV},${IN_DIR}/run_18/${KPIS_CSV},\
#  ${IN_DIR}/run_19/${KPIS_CSV} --run_id_re ".*\/run_(\d+)\/processed\/kpis.csv" \
#  --out_file ${IN_DIR}/kpi_stats.csv)
}

function main() {
  set -e
  parse_arguments "$@"
  check_arguments

  setup

  desc=$(${LOCAL_SCRIPT_DIR}/${RUN_CUJ_SCRIPT} ${RUN_CUJ_ARGS} --print_cuj_desc 2>&1)
  print_log "Running CUJ '${desc}' for ${ITERATIONS} times"

  set +e
  run_cuj_iterations
  set -e

  generate_kpi_stats

  adb shell setprop persist.logd.logpersistd ""
  adb shell setprop persist.logd.logpersistd.enable false
}

main "$@"
