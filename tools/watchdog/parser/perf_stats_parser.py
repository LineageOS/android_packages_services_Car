#!/usr/bin/python3

# Copyright (C) 2022 The Android Open Source Project
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

"""Tool to parse CarWatchdog's performance stats dump.

To build the parser script run:
  m perf_stats_parser

To parse a carwatchdog dump text file run:
  perf_stats_parser -f <cw-dump>.txt -o cw_proto_out.pb

To read a carwatchdog proto file as a json run:
  pers_stats_parser -r <cw-proto-out>.pb -j
"""
import argparse
from datetime import datetime
import json
import os
import sys

from parser import deviceperformancestats_pb2
from parser import performancestats_pb2

from .carwatchdog_dump_parser import *


def create_date_pb(date):
  date_pb = performancestats_pb2.Date()
  date_pb.year = date.year
  date_pb.month = date.month
  date_pb.day = date.day
  return date_pb


def create_timeofday_pb(date):
  timeofday_pb = performancestats_pb2.TimeOfDay()
  timeofday_pb.hours = date.hour
  timeofday_pb.minutes = date.minute
  timeofday_pb.seconds = date.second
  return timeofday_pb


def add_package_storage_io_stats_pb(storage_io_stats, storage_io_stats_pb):
  storage_io_stats_pb.user_id = storage_io_stats.user_id
  storage_io_stats_pb.package_name = storage_io_stats.package_name
  storage_io_stats_pb.fg_bytes = storage_io_stats.fg_bytes
  storage_io_stats_pb.fg_bytes_percent = storage_io_stats.fg_bytes_percent
  storage_io_stats_pb.fg_fsync = storage_io_stats.fg_fsync
  storage_io_stats_pb.fg_fsync_percent = storage_io_stats.fg_fsync_percent
  storage_io_stats_pb.bg_bytes = storage_io_stats.bg_bytes
  storage_io_stats_pb.bg_bytes_percent = storage_io_stats.bg_bytes_percent
  storage_io_stats_pb.bg_fsync = storage_io_stats.bg_fsync
  storage_io_stats_pb.bg_fsync_percent = storage_io_stats.bg_fsync_percent


def add_system_event_pb(system_event_stats, system_event_pb):
  for collection in system_event_stats.collections:
    stats_collection_pb = system_event_pb.collections.add()
    stats_collection_pb.id = collection.id
    stats_collection_pb.date.CopyFrom(create_date_pb(collection.date))
    stats_collection_pb.time.CopyFrom(create_timeofday_pb(collection.date))
    stats_collection_pb.total_cpu_time_ms = collection.total_cpu_time_ms
    stats_collection_pb.total_cpu_cycles = collection.total_cpu_cycles
    stats_collection_pb.idle_cpu_time_ms = collection.idle_cpu_time_ms
    stats_collection_pb.io_wait_time_ms = collection.io_wait_time_ms
    stats_collection_pb.context_switches = collection.context_switches
    stats_collection_pb.io_blocked_processes = collection.io_blocked_processes
    stats_collection_pb.major_page_faults = collection.major_page_faults

    for package_cpu_stats in collection.package_cpu_stats:
      package_cpu_stats_pb = stats_collection_pb.package_cpu_stats.add()
      package_cpu_stats_pb.user_id = package_cpu_stats.user_id
      package_cpu_stats_pb.package_name = package_cpu_stats.package_name
      package_cpu_stats_pb.cpu_time_ms = package_cpu_stats.cpu_time_ms
      package_cpu_stats_pb.total_cpu_time_percent = (
          package_cpu_stats.total_cpu_time_percent
      )
      package_cpu_stats_pb.cpu_cycles = package_cpu_stats.cpu_cycles

      for process_cpu_stats in package_cpu_stats.process_cpu_stats:
        process_cpu_stats_pb = package_cpu_stats_pb.process_cpu_stats.add()
        process_cpu_stats_pb.command = process_cpu_stats.command
        process_cpu_stats_pb.cpu_time_ms = process_cpu_stats.cpu_time_ms
        process_cpu_stats_pb.package_cpu_time_percent = (
            process_cpu_stats.package_cpu_time_percent
        )
        process_cpu_stats_pb.cpu_cycles = process_cpu_stats.cpu_cycles

    for (
        package_storage_io_read_stats
    ) in collection.package_storage_io_read_stats:
      add_package_storage_io_stats_pb(
          package_storage_io_read_stats,
          stats_collection_pb.package_storage_io_read_stats.add(),
      )

    for (
        package_storage_io_write_stats
    ) in collection.package_storage_io_write_stats:
      add_package_storage_io_stats_pb(
          package_storage_io_write_stats,
          stats_collection_pb.package_storage_io_write_stats.add(),
      )


def get_system_event(system_event_pb):
  if not system_event_pb.collections:
    return None

  system_event_stats = SystemEventStats()
  for stats_collection_pb in system_event_pb.collections:
    stats_collection = StatsCollection()
    stats_collection.id = stats_collection_pb.id
    date_pb = stats_collection_pb.date
    time_pb = stats_collection_pb.time
    stats_collection.date = datetime(
        date_pb.year,
        date_pb.month,
        date_pb.day,
        time_pb.hours,
        time_pb.minutes,
        time_pb.seconds,
    )
    stats_collection.total_cpu_time_ms = stats_collection_pb.total_cpu_time_ms
    stats_collection.total_cpu_cycles = stats_collection_pb.total_cpu_cycles
    stats_collection.idle_cpu_time_ms = stats_collection_pb.idle_cpu_time_ms
    stats_collection.io_wait_time_ms = stats_collection_pb.io_wait_time_ms
    stats_collection.context_switches = stats_collection_pb.context_switches
    stats_collection.io_blocked_processes = (
        stats_collection_pb.io_blocked_processes
    )
    stats_collection.major_page_faults = stats_collection_pb.major_page_faults

    for package_cpu_stats_pb in stats_collection_pb.package_cpu_stats:
      package_cpu_stats = PackageCpuStats.from_proto(package_cpu_stats_pb)
      for process_cpu_stats_pb in package_cpu_stats_pb.process_cpu_stats:
        package_cpu_stats.process_cpu_stats.append(
              ProcessCpuStats.from_proto(process_cpu_stats_pb)
        )
      stats_collection.package_cpu_stats.append(package_cpu_stats)

    for (
        package_storage_io_read_stats_pb
    ) in stats_collection_pb.package_storage_io_read_stats:
      stats_collection.package_storage_io_read_stats.append(
          PackageStorageIoStats.from_proto(package_storage_io_read_stats_pb)
      )

    for (
        package_storage_io_write_stats_pb
    ) in stats_collection_pb.package_storage_io_write_stats:
      stats_collection.package_storage_io_write_stats.append(
          PackageStorageIoStats.from_proto(package_storage_io_write_stats_pb)
      )

    system_event_stats.add(stats_collection)

  return system_event_stats


def get_perf_stats(perf_stats_pb):
  perf_stats = PerformanceStats()
  perf_stats.boot_time_stats = get_system_event(perf_stats_pb.boot_time_stats)
  perf_stats.last_n_minutes_stats = get_system_event(
      perf_stats_pb.last_n_minutes_stats
  )
  perf_stats.custom_collection_stats = get_system_event(
      perf_stats_pb.custom_collection_stats
  )
  return perf_stats


def get_build_info(build_info_pb):
  build_info = BuildInformation()
  build_info.fingerprint = build_info_pb.fingerprint
  build_info.brand = build_info_pb.brand
  build_info.product = build_info_pb.product
  build_info.device = build_info_pb.device
  build_info.version_release = build_info_pb.version_release
  build_info.id = build_info_pb.id
  build_info.version_incremental = build_info_pb.version_incremental
  build_info.type = build_info_pb.type
  build_info.tags = build_info_pb.tags
  build_info.sdk = build_info_pb.sdk
  build_info.platform_minor = build_info_pb.platform_minor
  build_info.codename = build_info_pb.codename
  return build_info


def write_pb(perf_stats, out_file, build_info=None, out_build_file=None):
  if perf_stats.is_empty():
    print("Cannot write proto since performance stats are empty")
    return False

  perf_stats_pb = performancestats_pb2.PerformanceStats()

  # Boot time proto
  if perf_stats.has_boot_time_stats():
    boot_time_stats_pb = performancestats_pb2.SystemEventStats()
    add_system_event_pb(perf_stats.boot_time_stats, boot_time_stats_pb)
    perf_stats_pb.boot_time_stats.CopyFrom(boot_time_stats_pb)

  if perf_stats.has_last_n_minutes_stats():
    last_n_minutes_stats_pb = performancestats_pb2.SystemEventStats()
    add_system_event_pb(
        perf_stats.last_n_minutes_stats, last_n_minutes_stats_pb
    )
    perf_stats_pb.last_n_minutes_stats.CopyFrom(last_n_minutes_stats_pb)

  # TODO(b/256654082): Add user switch events to proto

  # Custom collection proto
  if perf_stats.has_custom_collection_stats():
    custom_collection_stats_pb = performancestats_pb2.SystemEventStats()
    add_system_event_pb(
        perf_stats.custom_collection_stats, custom_collection_stats_pb
    )
    perf_stats_pb.custom_collection_stats.CopyFrom(custom_collection_stats_pb)

  # Write pb binary to disk
  if out_file:
    with open(out_file, "wb") as f:
      f.write(perf_stats_pb.SerializeToString())

  if build_info is not None:
    build_info_pb = deviceperformancestats_pb2.BuildInformation()
    build_info_pb.fingerprint = build_info.fingerprint
    build_info_pb.brand = build_info.brand
    build_info_pb.product = build_info.product
    build_info_pb.device = build_info.device
    build_info_pb.version_release = build_info.version_release
    build_info_pb.id = build_info.id
    build_info_pb.version_incremental = build_info.version_incremental
    build_info_pb.type = build_info.type
    build_info_pb.tags = build_info.tags
    build_info_pb.sdk = build_info.sdk
    build_info_pb.platform_minor = build_info.platform_minor
    build_info_pb.codename = build_info.codename

    device_run_perf_stats_pb = (
        deviceperformancestats_pb2.DevicePerformanceStats()
    )
    device_run_perf_stats_pb.build_info.CopyFrom(build_info_pb)
    device_run_perf_stats_pb.perf_stats.add().CopyFrom(perf_stats_pb)

    with open(out_build_file, "wb") as f:
      f.write(device_run_perf_stats_pb.SerializeToString())

  return True


def read_pb(pb_file, is_device_run=False):
  perf_stats_pb = (
      deviceperformancestats_pb2.DevicePerformanceStats()
      if is_device_run
      else performancestats_pb2.PerformanceStats()
  )

  with open(pb_file, "rb") as f:
    try:
      perf_stats_pb.ParseFromString(f.read())
      perf_stats_pb.DiscardUnknownFields()
    except UnicodeDecodeError:
      proto_type = (
          "DevicePerformanceStats" if is_device_run else "PerformanceStats"
      )
      print(f"Error: Proto in {pb_file} probably is not '{proto_type}'")
      return None

  if not perf_stats_pb:
    print(f"Error: Proto stored in {pb_file} has incorrect format.")
    return None

  if not is_device_run:
    return get_perf_stats(perf_stats_pb)

  device_run_perf_stats = DevicePerformanceStats()
  device_run_perf_stats.build_info = get_build_info(perf_stats_pb.build_info)

  for perf_stat in perf_stats_pb.perf_stats:
    device_run_perf_stats.perf_stats.append(get_perf_stats(perf_stat))

  return device_run_perf_stats


def init_arguments():
  parser = argparse.ArgumentParser(description="Parses CarWatchdog's dump.")
  parser.add_argument(
      "-f",
      "--file",
      dest="file",
      default="dump.txt",
      help="File with the CarWatchdog dump",
  )
  parser.add_argument(
      "-o",
      "--out",
      dest="out",
      help="protobuf binary with parsed performance stats",
  )
  parser.add_argument(
      "-b",
      "--build",
      dest="build",
      help="File with Android device build information",
  )
  parser.add_argument(
      "-d",
      "--device-out",
      dest="device_out",
      default="device_perf_stats.pb",
      help="protobuf binary with build information",
  )
  parser.add_argument(
      "-p",
      "--print",
      dest="print",
      action="store_true",
      help=(
          "prints the parsed performance data to the console "
          "when out proto defined"
      ),
  )
  parser.add_argument(
      "-r",
      "--read",
      dest="read_proto",
      help=(
          "Protobuf binary to be printed in console. If this "
          "flag is set no other process is executed."
      ),
  )
  parser.add_argument(
      "-D",
      "--device-run",
      dest="device_run",
      action="store_true",
      help=(
          "Specifies that the proto to be read is a "
          "DevicePerformanceStats proto. (Only checked if "
          "-r is set)"
      ),
  )
  parser.add_argument(
      "-j",
      "--json",
      dest="json",
      action="store_true",
      help="Generate a JSON file from the protobuf binary read.",
  )

  return parser.parse_args()


if __name__ == "__main__":
  args = init_arguments()

  if args.read_proto:
    if not os.path.isfile(args.read_proto):
      print("Error: Proto binary '%s' does not exist" % args.read_proto)
      sys.exit(1)
    performance_stats = read_pb(args.read_proto, args.device_run)
    if performance_stats is None:
      print(f"Error: Could not read '{args.read_proto}'")
      sys.exit(1)
    if args.json:
      print(json.dumps(performance_stats.to_dict()))
    else:
      print(performance_stats)
    sys.exit()

  if not os.path.isfile(args.file):
    print("Error: File '%s' does not exist" % args.file)
    sys.exit(1)

  with open(args.file, "r", encoding="UTF-8", errors="ignore") as f:
    performance_stats = parse_dump(f.read())

    build_info = None
    if args.build:
      build_info = parse_build_info(args.build)
      print(build_info)

    if performance_stats.is_empty():
      print(
          "Error: No performance stats were parsed. Make sure dump file"
          " contains carwatchdog's dump text."
      )
      sys.exit(1)

    if (args.out or args.build) and write_pb(
        performance_stats, args.out, build_info, args.device_out
    ):
      out_file = args.out if args.out else args.device_out
      print("Output protobuf binary in:", out_file)

    if args.print or not (args.out or args.build):
      if args.json:
        print(json.dumps(performance_stats.to_dict()))
        sys.exit()
      print(performance_stats)
