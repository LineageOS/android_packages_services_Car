#!/usr/bin/python3

# Copyright (C) 2023 The Android Open Source Project
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

from datetime import datetime
import re
import sys

BOOT_TIME_REPORT_HEADER = "Boot-time performance report:"
CUSTOM_COLLECTION_REPORT_HEADER = "Custom performance data report:"
TOP_N_CPU_TIME_HEADER = "Top N CPU Times:"

DUMP_DATETIME_FORMAT = "%a %b %d %H:%M:%S %Y %Z"
DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"

STATS_COLLECTION_PATTERN = "Collection (\d+): <(.+)>"
PACKAGE_CPU_STATS_PATTERN = "(\d+), (.+), (\d+), (\d+).(\d+)%(, (\d+))?"
PROCESS_CPU_STATS_PATTERN = "\s+(.+), (\d+), (\d+).(\d+)%(, (\d+))?"
TOTAL_CPU_TIME_PATTERN = "Total CPU time \\(ms\\): (\d+)"
TOTAL_IDLE_CPU_TIME_PATTERN = "Total idle CPU time \\(ms\\)/percent: (\d+) / .+"
CPU_IO_WAIT_TIME_PATTERN = "CPU I/O wait time \\(ms\\)/percent: (\d+) / .+"
CONTEXT_SWITCHES_PATTERN = "Number of context switches: (\d+)"
IO_BLOCKED_PROCESSES_PATTERN = (
    "Number of I/O blocked processes/percent: (\d+) / .+"
)


class BuildInformation:

  def __init__(self):
    self.fingerprint = None
    self.brand = None
    self.product = None
    self.device = None
    self.version_release = None
    self.id = None
    self.version_incremental = None
    self.type = None
    self.tags = None
    self.sdk = None
    self.platform_minor = None
    self.codename = None

  def __repr__(self):
    return (
        "BuildInformation (fingerprint={}, brand={}, product={}, device={}, "
        "version_release={}, id={}, version_incremental={}, type={}, tags={}, "
        "sdk={}, platform_minor={}, codename={})".format(
            self.fingerprint,
            self.brand,
            self.product,
            self.device,
            self.version_release,
            self.id,
            self.version_incremental,
            self.type,
            self.tags,
            self.sdk,
            self.platform_minor,
            self.codename,
        )
    )


class ProcessCpuStats:

  def __init__(
      self, command, cpu_time_ms, package_cpu_time_percent, cpu_cycles
  ):
    self.command = command
    self.cpu_time_ms = cpu_time_ms
    self.package_cpu_time_percent = package_cpu_time_percent
    self.cpu_cycles = cpu_cycles

  def __repr__(self):
    return (
        "ProcessCpuStats (command={}, CPU time={}ms, percent of "
        "package's CPU time={}%, CPU cycles={})".format(
            self.command,
            self.cpu_time_ms,
            self.package_cpu_time_percent,
            self.cpu_cycles,
        )
    )


class PackageCpuStats:

  def __init__(
      self,
      user_id,
      package_name,
      cpu_time_ms,
      total_cpu_time_percent,
      cpu_cycles,
  ):
    self.user_id = user_id
    self.package_name = package_name
    self.cpu_time_ms = cpu_time_ms
    self.total_cpu_time_percent = total_cpu_time_percent
    self.cpu_cycles = cpu_cycles
    self.process_cpu_stats = []

  def to_dict(self):
    return {
        "user_id": self.user_id,
        "package_name": self.package_name,
        "cpu_time_ms": self.cpu_time_ms,
        "total_cpu_time_percent": self.total_cpu_time_percent,
        "cpu_cycles": self.cpu_cycles,
        "process_cpu_stats": [vars(p) for p in self.process_cpu_stats],
    }

  def __repr__(self):
    process_cpu_stats_str = "[])"
    if len(self.process_cpu_stats) > 0:
      process_list_str = "\n      ".join(
          list(map(repr, self.process_cpu_stats))
      )
      process_cpu_stats_str = "\n      {}\n    )".format(process_list_str)
    return (
        "PackageCpuStats (user id={}, package name={}, CPU time={}ms, "
        "percent of total CPU time={}%, CPU cycles={}, process CPU stats={}"
        .format(
            self.user_id,
            self.package_name,
            self.cpu_time_ms,
            self.total_cpu_time_percent,
            self.cpu_cycles,
            process_cpu_stats_str,
        )
    )


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
    val = (
        self.total_cpu_time_ms
        + self.idle_cpu_time_ms
        + self.io_wait_time_ms
        + self.context_switches
        + self.io_blocked_processes
    )
    return (
        self.id == -1
        and not self.date
        and val == 0
        and len(self.package_cpu_stats) == 0
    )

  def to_dict(self):
    return {
        "id": self.id,
        "date": self.date.strftime(DATETIME_FORMAT) if self.date else "",
        "total_cpu_time_ms": self.total_cpu_time_ms,
        "idle_cpu_time_ms": self.idle_cpu_time_ms,
        "io_wait_time_ms": self.io_wait_time_ms,
        "context_switches": self.context_switches,
        "io_blocked_processes": self.io_blocked_processes,
        "packages_cpu_stats": [p.to_dict() for p in self.package_cpu_stats],
    }

  def __repr__(self):
    date = self.date.strftime(DATETIME_FORMAT) if self.date else ""
    pcs_str = "\n    ".join(list(map(repr, self.package_cpu_stats)))
    return (
        "StatsCollection (id={}, date={}, total CPU time={}ms, "
        "idle CPU time={}ms, I/O wait time={}ms, total context switches={}, "
        "total I/O blocked processes={}, package CPU stats=\n    {}\n  )"
        .format(
            self.id,
            date,
            self.total_cpu_time_ms,
            self.idle_cpu_time_ms,
            self.io_wait_time_ms,
            self.context_switches,
            self.io_blocked_processes,
            pcs_str,
        )
    )


class SystemEventStats:

  def __init__(self):
    self.collections = []

  def add(self, collection):
    self.collections.append(collection)

  def is_empty(self):
    return not any(map(lambda c: not c.is_empty(), self.collections))

  def to_list(self):
    return [c.to_dict() for c in self.collections]

  def __repr__(self):
    collections_str = "\n  ".join(list(map(repr, self.collections)))
    return "SystemEventStats (\n  {}\n)".format(collections_str)


class PerformanceStats:

  def __init__(self):
    self.boot_time_stats = None
    self.user_switch_stats = []
    self.custom_collection_stats = None

  def has_boot_time(self):
    return self.boot_time_stats and not self.boot_time_stats.is_empty()

  def has_custom_collection(self):
    return (
        self.custom_collection_stats
        and not self.custom_collection_stats.is_empty()
    )

  def is_empty(self):
    return (
        not self.has_boot_time()
        and not self.has_custom_collection()
        and not any(map(lambda u: not u.is_empty(), self.user_switch_stats))
    )

  def to_dict(self):
    return {
        "boot_time_stats": (
            self.boot_time_stats.to_list() if self.boot_time_stats else None
        ),
        "user_switch_stats": [u.to_list() for u in self.user_switch_stats],
        "custom_collection_stats": (
            self.custom_collection_stats.to_list()
            if self.custom_collection_stats
            else None
        ),
    }

  def __repr__(self):
    return (
        "PerformanceStats (\n"
        "boot-time stats={}\n"
        "\nuser-switch stats={}\n"
        "\ncustom-collection stats={}\n)".format(
            self.boot_time_stats,
            self.user_switch_stats,
            self.custom_collection_stats,
        )
    )


class DevicePerformanceStats:

  def __init__(self):
    self.build_info = None
    self.perf_stats = []

  def to_dict(self):
    return {
        "build_info": vars(self.build_info),
        "perf_stats": [s.to_dict() for s in self.perf_stats],
    }

  def __repr__(self):
    return "DevicePerformanceStats (\nbuild_info={}\n\nperf_stats={}\n)".format(
        self.build_info, self.perf_stats
    )


def parse_build_info(build_info_file):
  build_info = BuildInformation()

  def get_value(line):
    if ":" not in line:
      return ""
    return line.split(":")[1].strip()

  with open(build_info_file, "r") as f:
    for line in f.readlines():
      value = get_value(line)
      if line.startswith("fingerprint"):
        build_info.fingerprint = value
      elif line.startswith("brand"):
        build_info.brand = value
      elif line.startswith("product"):
        build_info.product = value
      elif line.startswith("device"):
        build_info.device = value
      elif line.startswith("version.release"):
        build_info.version_release = value
      elif line.startswith("id"):
        build_info.id = value
      elif line.startswith("version.incremental"):
        build_info.version_incremental = value
      elif line.startswith("type"):
        build_info.type = value
      elif line.startswith("tags"):
        build_info.tags = value
      elif line.startswith("sdk"):
        build_info.sdk = value
      elif line.startswith("platform minor version"):
        build_info.platform_minor = value
      elif line.startswith("codename"):
        build_info.codename = value

    return build_info


def parse_cpu_times(lines, idx):
  package_cpu_stats = []
  package_cpu_stat = None

  while (
      not (line := lines[idx].rstrip()).startswith("Top N")
      and not re.match(STATS_COLLECTION_PATTERN, line)
      and not line.startswith("-" * 50)
  ):
    if match := re.match(PACKAGE_CPU_STATS_PATTERN, line):
      user_id = int(match.group(1))
      package_name = match.group(2)
      cpu_time_ms = int(match.group(3))
      total_cpu_time_percent = float(
          "{}.{}".format(match.group(4), match.group(5))
      )
      cpu_cycles = int(match.group(7)) if match.group(7) is not None else -1

      package_cpu_stat = PackageCpuStats(
          user_id, package_name, cpu_time_ms, total_cpu_time_percent, cpu_cycles
      )
      package_cpu_stats.append(package_cpu_stat)
    elif match := re.match(PROCESS_CPU_STATS_PATTERN, line):
      command = match.group(1)
      cpu_time_ms = int(match.group(2))
      package_cpu_time_percent = float(
          "{}.{}".format(match.group(3), match.group(4))
      )
      cpu_cycles = int(match.group(6)) if match.group(6) is not None else -1
      if package_cpu_stat:
        package_cpu_stat.process_cpu_stats.append(
            ProcessCpuStats(
                command, cpu_time_ms, package_cpu_time_percent, cpu_cycles
            )
        )
      else:
        print(
            "No package CPU stats parsed for process:", command, file=sys.stderr
        )

    idx += 1

  return package_cpu_stats, idx


def parse_collection(lines, idx, match):
  collection = StatsCollection()
  collection.id = int(match.group(1))
  collection.date = datetime.strptime(match.group(2), DUMP_DATETIME_FORMAT)

  while not re.match(
      STATS_COLLECTION_PATTERN, (line := lines[idx].strip())
  ) and not line.startswith("-" * 50):
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
  while not (line := lines[idx].strip()).startswith("-" * 50):
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
    if line == CUSTOM_COLLECTION_REPORT_HEADER:
      idx += 2  # Skip the dashed-line after the custom collection header
      custom_collection_stats, idx = parse_stats_collections(lines, idx)
      if not custom_collection_stats.is_empty():
        performance_stats.custom_collection_stats = custom_collection_stats
    else:
      idx += 1

  return performance_stats
