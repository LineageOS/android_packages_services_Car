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
"""
Tool to parse CarWatchdog's performance stats dump.
"""
import argparse
import os
import performancestats_pb2
import re
import sys

from datetime import datetime

BOOT_TIME_REPORT_HEADER = "Boot-time performance report:"
TOP_N_CPU_TIME_HEADER = "Top N CPU Times:"

DUMP_DATETIME_FORMAT = "%a %b %d %H:%M:%S %Y %Z"
DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"

STATS_COLLECTION_PATTERN = "Collection (\d+): <(.+)>"
PACKAGE_CPU_STATS_PATTERN = "(\d+), (.+), (\d+), (\d+).(\d+)%"
PROCESS_CPU_STATS_PATTERN = "\s+(.+), (\d+), (\d+).(\d+)%"
TOTAL_CPU_TIME_PATTERN = "Total CPU time \\(ms\\): (\d+)"
TOTAL_IDLE_CPU_TIME_PATTERN = "Total idle CPU time \\(ms\\)/percent: (\d+) / .+"
CPU_IO_WAIT_TIME_PATTERN = "CPU I/O wait time \\(ms\\)/percent: (\d+) / .+"
CONTEXT_SWITCHES_PATTERN = "Number of context switches: (\d+)"
IO_BLOCKED_PROCESSES_PATTERN = "Number of I/O blocked processes/percent: (\d+) / .+"

class ProcessCpuStats:
  def __init__(self, command, cpu_time_ms, package_cpu_time_percent):
    self.command = command
    self.cpu_time_ms = cpu_time_ms
    self.package_cpu_time_percent = package_cpu_time_percent

  def __repr__(self):
    return "ProcessCpuStats (command={}, CPU time={}ms, percent of " \
           "package's CPU time={}%)".format(self.command, self.cpu_time_ms,
                                           self.package_cpu_time_percent)


class PackageCpuStats:
  def __init__(self, user_id, package_name, cpu_time_ms,
      total_cpu_time_percent):
    self.user_id = user_id
    self.package_name = package_name
    self.cpu_time_ms = cpu_time_ms
    self.total_cpu_time_percent = total_cpu_time_percent
    self.process_cpu_stats = []

  def __repr__(self):
    process_cpu_stats_str = "[])"
    if len(self.process_cpu_stats) > 0:
      process_list_str = "\n      ".join(list(map(repr, self.process_cpu_stats)))
      process_cpu_stats_str = "\n      {}\n    )".format(process_list_str)
    return "PackageCpuStats (user id={}, package name={}, CPU time={}ms, " \
           "percent of total CPU time={}%, process CPU stats={}" \
      .format(self.user_id, self.package_name, self.cpu_time_ms,
              self.total_cpu_time_percent, process_cpu_stats_str)


class StatsCollection:
  def __init__(self):
    self.id = -1
    self.date = None
    self.total_cpu_time_ms = 0
    self.idle_cpu_time_ms = 0
    self.io_wait_time_ms = 0
    self.context_switches = 0
    self.io_blocked_processes = 0
    self.package_cpu_stats = []

  def is_empty(self):
    val = self.total_cpu_time_ms + self.idle_cpu_time_ms + self.io_wait_time_ms + \
          self.context_switches + self.io_blocked_processes
    return self.id == -1 and not self.date and val == 0 and len(self.package_cpu_stats) == 0

  def __repr__(self):
    date = self.date.strftime(DATETIME_FORMAT) if self.date else ""
    pcs_str = "\n    ".join(list(map(repr, self.package_cpu_stats)))
    return "StatsCollection (id={}, date={}, total CPU time={}ms, " \
           "idle CPU time={}ms, I/O wait time={}ms, total context switches={}, " \
           "total I/O blocked processes={}, package CPU stats=\n    {}\n  )" \
      .format(self.id, date, self.total_cpu_time_ms, self.idle_cpu_time_ms,
              self.io_wait_time_ms, self.context_switches,
              self.io_blocked_processes, pcs_str)


class SystemEventStats:
  def __init__(self):
    self.collections = []

  def add(self, collection):
    self.collections.append(collection)

  def is_empty(self):
    return not any(map(lambda c: not c.is_empty(), self.collections))

  def __repr__(self):
    collections_str = "\n  ".join(list(map(repr, self.collections)))
    return "SystemEventStats (\n" \
           "  {}".format(collections_str)


class PerformanceStats:
  def __init__(self):
    self.boot_time_stats = None
    self.user_switch_stats = []

  def has_boot_time(self):
    return self.boot_time_stats and not self.boot_time_stats.is_empty()

  def is_empty(self):
    return not self.has_boot_time() \
           and not any(map(lambda u: not u.is_empty(), self.user_switch_stats))

  def __repr__(self):
    return "PerformanceStats (\n" \
          "boot-time stats={}\n" \
          "\nuser-switch stats={}\n)" \
      .format(self.boot_time_stats, self.user_switch_stats)


def parse_cpu_times(lines, idx):
  package_cpu_stats = []
  package_cpu_stat = None

  while not (line := lines[idx].rstrip()).startswith("Top N") \
      and not re.match(STATS_COLLECTION_PATTERN, line) \
      and not line.startswith('-' * 50):
    if match := re.match(PACKAGE_CPU_STATS_PATTERN, line):
      user_id = int(match.group(1))
      package_name = match.group(2)
      cpu_time_ms = int(match.group(3))
      total_cpu_time_percent = float("{}.{}".format(match.group(4),
                                                    match.group(5)))

      package_cpu_stat = PackageCpuStats(user_id, package_name,
                                         cpu_time_ms,
                                         total_cpu_time_percent)
      package_cpu_stats.append(package_cpu_stat)
    elif match := re.match(PROCESS_CPU_STATS_PATTERN, line):
      command = match.group(1)
      cpu_time_ms = int(match.group(2))
      package_cpu_time_percent = float("{}.{}".format(match.group(3),
                                                      match.group(4)))
      if package_cpu_stat:
        package_cpu_stat.process_cpu_stats.append(
          ProcessCpuStats(command, cpu_time_ms, package_cpu_time_percent))
      else:
        print("No package CPU stats parsed for process:", command, file=sys.stderr)

    idx += 1

  return package_cpu_stats, idx


def parse_collection(lines, idx, match):
  collection = StatsCollection()
  collection.id = int(match.group(1))
  collection.date = datetime.strptime(match.group(2), DUMP_DATETIME_FORMAT)

  while not re.match(STATS_COLLECTION_PATTERN,
                     (line := lines[idx].strip())) and not line.startswith('-' * 50):
    if match := re.match(TOTAL_CPU_TIME_PATTERN, line):
      collection.total_cpu_time_ms = int(match.group(1))
    elif match := re.match(TOTAL_IDLE_CPU_TIME_PATTERN, line):
      collection.idle_cpu_time_ms = int(match.group(1))
    elif match := re.match(CPU_IO_WAIT_TIME_PATTERN, line):
      collection.io_wait_time_ms = int(match.group(1))
    elif match := re.match(CONTEXT_SWITCHES_PATTERN, line):
      collection.context_switches = int(match.group(1))
    elif match := re.match(IO_BLOCKED_PROCESSES_PATTERN, line):
      collection.io_blocked_processes = int(match.group(1))
    elif line == TOP_N_CPU_TIME_HEADER:
      idx += 1  # Skip subsection header
      package_cpu_stats, idx = parse_cpu_times(lines, idx)
      collection.package_cpu_stats = package_cpu_stats
      continue

    idx += 1

  return collection, idx


def parse_stats_collections(lines, idx):
  system_event_stats = SystemEventStats()
  while not (line := lines[idx].strip()).startswith('-' * 50):
    if match := re.match(STATS_COLLECTION_PATTERN, line):
      idx += 1  # Skip the collection header
      collection, idx = parse_collection(lines, idx, match)
      if not collection.is_empty():
        system_event_stats.add(collection)
    else:
      idx += 1
  return system_event_stats, idx


def parse_dump(dump):
  lines = dump.split("\n")
  performance_stats = PerformanceStats()
  idx = 0
  while idx < len(lines):
    line = lines[idx].strip()
    if line == BOOT_TIME_REPORT_HEADER:
      boot_time_stats, idx = parse_stats_collections(lines, idx)
      if not boot_time_stats.is_empty():
        performance_stats.boot_time_stats = boot_time_stats
    else:
      idx += 1

  return performance_stats


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


def add_system_event_pb(system_event_stats, system_event_pb):
  for collection in system_event_stats.collections:
    stats_collection_pb = system_event_pb.collections.add()
    stats_collection_pb.id = collection.id
    stats_collection_pb.date.CopyFrom(create_date_pb(collection.date))
    stats_collection_pb.time.CopyFrom(create_timeofday_pb(collection.date))
    stats_collection_pb.total_cpu_time_ms = collection.total_cpu_time_ms
    stats_collection_pb.idle_cpu_time_ms = collection.idle_cpu_time_ms
    stats_collection_pb.io_wait_time_ms = collection.io_wait_time_ms
    stats_collection_pb.context_switches = collection.context_switches
    stats_collection_pb.io_blocked_processes = collection.io_blocked_processes

    for package_cpu_stats in collection.package_cpu_stats:
      package_cpu_stats_pb = stats_collection_pb.package_cpu_stats.add()
      package_cpu_stats_pb.user_id = package_cpu_stats.user_id
      package_cpu_stats_pb.package_name = package_cpu_stats.package_name
      package_cpu_stats_pb.cpu_time_ms = package_cpu_stats.cpu_time_ms
      package_cpu_stats_pb.total_cpu_time_percent = package_cpu_stats.total_cpu_time_percent

      for process_cpu_stats in package_cpu_stats.process_cpu_stats:
        process_cpu_stats_pb = package_cpu_stats_pb.process_cpu_stats.add()
        process_cpu_stats_pb.command = process_cpu_stats.command
        process_cpu_stats_pb.cpu_time_ms = process_cpu_stats.cpu_time_ms
        process_cpu_stats_pb.package_cpu_time_percent = process_cpu_stats.package_cpu_time_percent


def get_system_event(system_event_pb):
  system_event_stats = SystemEventStats()
  for stats_collection_pb in system_event_pb.collections:
    stats_collection = StatsCollection()
    stats_collection.id = stats_collection_pb.id
    date_pb = stats_collection_pb.date
    time_pb = stats_collection_pb.time
    stats_collection.date = datetime(date_pb.year, date_pb.month, date_pb.day,
                                     time_pb.hours, time_pb.minutes, time_pb.seconds)
    stats_collection.total_cpu_time_ms = stats_collection_pb.total_cpu_time_ms
    stats_collection.idle_cpu_time_ms = stats_collection_pb.idle_cpu_time_ms
    stats_collection.io_wait_time_ms = stats_collection_pb.io_wait_time_ms
    stats_collection.context_switches = stats_collection_pb.context_switches
    stats_collection.io_blocked_processes = stats_collection_pb.io_blocked_processes

    for package_cpu_stats_pb in stats_collection_pb.package_cpu_stats:
      package_cpu_stats = \
        PackageCpuStats(package_cpu_stats_pb.user_id,
                        package_cpu_stats_pb.package_name,
                        package_cpu_stats_pb.cpu_time_ms,
                        round(package_cpu_stats_pb.total_cpu_time_percent, 2))

      for process_cpu_stats_pb in package_cpu_stats_pb.process_cpu_stats:
        process_cpu_stats = \
          ProcessCpuStats(process_cpu_stats_pb.command,
                          process_cpu_stats_pb.cpu_time_ms,
                          round(process_cpu_stats_pb.package_cpu_time_percent,
                                2))

        package_cpu_stats.process_cpu_stats.append(process_cpu_stats)
      stats_collection.package_cpu_stats.append(package_cpu_stats)
    system_event_stats.add(stats_collection)

  return system_event_stats


def write_pb(perf_stats, out_file):
  if perf_stats.is_empty():
    print("Cannot write proto since performance stats are empty")
    return False

  perf_stats_pb = performancestats_pb2.PerformanceStats()

  # Boot time proto
  if perf_stats.has_boot_time():
    boot_time_stats_pb = performancestats_pb2.SystemEventStats()
    add_system_event_pb(perf_stats.boot_time_stats, boot_time_stats_pb)
    perf_stats_pb.boot_time_stats.CopyFrom(boot_time_stats_pb)

  # TODO(b/256654082): Add user switch events to proto

  # Write pb binary to disk
  with open(out_file, "wb") as f:
    f.write(perf_stats_pb.SerializeToString())

  return True


def read_pb(pb_file):
  perf_stats_pb = performancestats_pb2.PerformanceStats()

  with open(pb_file, "rb") as f:
    perf_stats_pb.ParseFromString(f.read())
    perf_stats_pb.DiscardUnknownFields()

  if not perf_stats_pb:
    raise IOError("Proto stored in", pb_file, "has incorrect format.")

  perf_stats = PerformanceStats()
  perf_stats.boot_time_stats = get_system_event(perf_stats_pb.boot_time_stats)

  return perf_stats


def init_arguments():
  parser = argparse.ArgumentParser(description="Parses CarWatchdog's dump.")
  parser.add_argument('-f', '--file', dest='file',
                      default='dump.txt',
                      help="File with the CarWatchdog dump", )
  parser.add_argument('-o', '--out', dest='out',
                      help='protobuf binary with parsed performance stats', )
  parser.add_argument('-p', '--print', dest='print', action='store_true',
                      help='prints the parsed performance data to the console '
                           'when out proto defined')
  parser.add_argument('-r', '--read', dest='read_proto',
                      help='Protobuf binary to be printed in console. If this flag is set no other'
                           'process is executed.')

  return parser.parse_args()

# TODO(b/256654082): Add a wrapper proto which includes the perf stats proto
#  plus build information. The build information is optional to pass through
#  arguments.
if __name__ == "__main__":
  args = init_arguments()

  if args.read_proto:
    if not os.path.isfile(args.read_proto):
      print("Proto binary '%s' does not exist" % args.read_proto)
      exit(1)
    print("Reading performance stats proto:")
    performance_stats = read_pb(args.read_proto)
    print(performance_stats)
    exit()

  print("Parsing CarWatchdog performance stats")
  if not os.path.isfile(args.file):
    print("File '%s' does not exist" % args.file)
    exit(1)

  with open(args.file, 'r') as f:
    performance_stats = parse_dump(f.read())

    if performance_stats.is_empty():
      print("No performance stats were parsed. Make sure dump file contains carwatchdog's dump "
            "text.")
      exit(1)

    if args.out and write_pb(performance_stats, args.out):
      print("Output protobuf binary in:", args.out)

    if args.print or not args.out:
      print(performance_stats)
