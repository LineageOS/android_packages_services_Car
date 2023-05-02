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


BOOT_TIME_REPORT_HEADER_PATTERN = (
    r"Boot-time (?:performance|collection) report:"
)
TOP_N_STORAGE_IO_READS_HEADER_PATTERN = r"Top N (?:Storage I/O )?Reads:"
TOP_N_STORAGE_IO_WRITES_HEADER_PATTERN = r"Top N (?:Storage I/O )?Writes:"
STATS_COLLECTION_PATTERN = r"Collection (?P<id>\d+): <(?P<date>.+)>"
PACKAGE_STORAGE_IO_STATS_PATTERN = (
    r"(?P<userId>\d+), (?P<packageName>.+), (?P<fgBytes>\d+),"
    r" (?P<fgBytesPercent>\d+.\d+)%, "
    r"(?P<fgFsync>\d+), (?P<fgFsyncPercent>\d+.\d+)%, (?P<bgBytes>\d+), "
    r"(?P<bgBytesPercent>\d+.\d+)%, (?P<bgFsync>\d+),"
    r" (?P<bgFsyncPercent>\d+.\d+)%"
)
PACKAGE_CPU_STATS_PATTERN = (
    r"(?P<userId>\d+), (?P<packageName>.+), (?P<cpuTimeMs>\d+),"
    r" (?P<cpuTimePercent>\d+\.\d+)%"
    r"(, (?P<cpuCycles>\d+))?"
)
PROCESS_CPU_STATS_PATTERN = (
    r"\s+(?P<command>.+), (?P<cpuTimeMs>\d+), (?P<uidCpuPercent>\d+.\d+)%"
    r"(, (?P<cpuCycles>\d+))?"
)
TOTAL_CPU_TIME_PATTERN = r"Total CPU time \\(ms\\): (?P<totalCpuTimeMs>\d+)"
TOTAL_CPU_CYCLES_PATTERN = r"Total CPU cycles: (?P<totalCpuCycles>\d+)"
TOTAL_IDLE_CPU_TIME_PATTERN = (
    r"Total idle CPU time \\(ms\\)/percent: (?P<idleCpuTimeMs>\d+) / .+"
)
CPU_IO_WAIT_TIME_PATTERN = (
    r"CPU I/O wait time(?: \\(ms\\))?/percent: (?P<iowaitCpuTimeMs>\d+) / .+"
)
CONTEXT_SWITCHES_PATTERN = (
    r"Number of context switches: (?P<totalCtxtSwitches>\d+)"
)
IO_BLOCKED_PROCESSES_PATTERN = (
    r"Number of I/O blocked processes/percent: (?P<totalIoBlkProc>\d+)" r" / .+"
)
MAJOR_PAGE_FAULTS_PATTERN = (
    r"Number of major page faults since last collection:"
    r" (?P<totalMajPgFaults>\d+)"
)

COLLECTION_END_LINE_MIN_LEN = 50
PERIODIC_COLLECTION_HEADER = "Periodic collection report:"
LAST_N_MINS_COLLECTION_HEADER = "Last N minutes performance report:"
CUSTOM_COLLECTION_REPORT_HEADER = "Custom performance data report:"
TOP_N_CPU_TIME_HEADER = "Top N CPU Times:"

DUMP_DATETIME_FORMAT = "%a %b %d %H:%M:%S %Y %Z"
DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"


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
        "percent of total CPU time={}%, CPU cycles={}, process CPU stats={})"
        .format(
            self.user_id,
            self.package_name,
            self.cpu_time_ms,
            self.total_cpu_time_percent,
            self.cpu_cycles,
            process_cpu_stats_str,
        )
    )


class PackageStorageIoStats:

  def __init__(
      self,
      user_id,
      package_name,
      fg_bytes,
      fg_bytes_percent,
      fg_fsync,
      fg_fsync_percent,
      bg_bytes,
      bg_bytes_percent,
      bg_fsync,
      bg_fsync_percent,
  ):
    self.user_id = user_id
    self.package_name = package_name
    self.fg_bytes = fg_bytes
    self.fg_bytes_percent = fg_bytes_percent
    self.fg_fsync = fg_fsync
    self.fg_fsync_percent = fg_fsync_percent
    self.bg_bytes = bg_bytes
    self.bg_bytes_percent = bg_bytes_percent
    self.bg_fsync = bg_fsync
    self.bg_fsync_percent = bg_fsync_percent

  def to_dict(self):
    return {
        "user_id": self.user_id,
        "package_name": self.package_name,
        "fg_bytes": self.fg_bytes,
        "fg_bytes_percent": self.fg_bytes_percent,
        "fg_fsync": self.fg_fsync,
        "fg_fsync_percent": self.fg_fsync_percent,
        "bg_bytes": self.bg_bytes,
        "bg_bytes_percent": self.bg_bytes_percent,
        "bg_fsync": self.bg_fsync,
        "bg_fsync_percent": self.bg_fsync_percent,
    }

  def __repr__(self):
    return (
        "PackageStorageIoStats (user id={}, package name={}, foreground"
        " bytes={}, foreground bytes percent={}, foreground fsync={},"
        " foreground fsync percent={}, background bytes={}, background bytes"
        " percent={}, background fsync={}, background fsync percent={}) "
        .format(
            self.user_id,
            self.package_name,
            self.fg_bytes,
            self.fg_bytes_percent,
            self.fg_fsync,
            self.fg_fsync_percent,
            self.bg_bytes,
            self.bg_bytes_percent,
            self.bg_fsync,
            self.bg_fsync_percent,
        )
    )


class StatsCollection:

  def __init__(self):
    self.id = -1
    self.date = None
    self.total_cpu_time_ms = 0
    self.total_cpu_cycles = 0
    self.idle_cpu_time_ms = 0
    self.io_wait_time_ms = 0
    self.context_switches = 0
    self.io_blocked_processes = 0
    self.major_page_faults = 0
    self.package_cpu_stats = []
    self.package_storage_io_read_stats = []
    self.package_storage_io_write_stats = []

  def is_empty(self):
    val = (
        self.total_cpu_time_ms
        + self.total_cpu_cycles
        + self.idle_cpu_time_ms
        + self.io_wait_time_ms
        + self.context_switches
        + self.io_blocked_processes
        + self.major_page_faults
    )
    return (
        self.id == -1
        and not self.date
        and val == 0
        and not self.package_cpu_stats
        and not self.package_storage_io_read_stats
        and not self.package_storage_io_write_stats
    )

  def to_dict(self):
    return {
        "id": self.id,
        "date": self.date.strftime(DATETIME_FORMAT) if self.date else "",
        "total_cpu_time_ms": self.total_cpu_time_ms,
        "total_cpu_cycles": self.total_cpu_cycles,
        "idle_cpu_time_ms": self.idle_cpu_time_ms,
        "io_wait_time_ms": self.io_wait_time_ms,
        "context_switches": self.context_switches,
        "io_blocked_processes": self.io_blocked_processes,
        "major_page_faults": self.major_page_faults,
        "packages_cpu_stats": [p.to_dict() for p in self.package_cpu_stats],
        "package_storage_io_read_stats": [
            p.to_dict() for p in self.package_storage_io_read_stats
        ],
        "package_storage_io_write_stats": [
            p.to_dict() for p in self.package_storage_io_write_stats
        ],
    }

  def __repr__(self):
    date = self.date.strftime(DATETIME_FORMAT) if self.date else ""
    package_cpu_stats_dump = ""
    package_storage_io_read_stats_dump = ""
    package_storage_io_write_stats_dump = ""

    if self.package_cpu_stats:
      package_cpu_stats_str = "\n    ".join(
          list(map(repr, self.package_cpu_stats))
      )
      package_cpu_stats_dump = ", package CPU stats=\n    {}\n".format(
          package_cpu_stats_str
      )

    if self.package_storage_io_read_stats:
      package_storage_io_read_stats_str = "\n    ".join(
          list(map(repr, self.package_storage_io_read_stats))
      )
      package_storage_io_read_stats_dump = (
          ", package storage I/O read stats=\n    {}\n".format(
              package_storage_io_read_stats_str
          )
      )

    if self.package_storage_io_write_stats:
      package_storage_io_write_stats_str = "\n    ".join(
          list(map(repr, self.package_storage_io_write_stats))
      )
      package_storage_io_write_stats_dump = (
          ", package storage I/O write stats=\n    {}\n".format(
              package_storage_io_write_stats_str
          )
      )

    return (
        "StatsCollection (id={}, date={}, total CPU time={}ms, total CPU"
        " cycles={}, idle CPU time={}ms, I/O wait time={}ms, total context"
        " switches={}, total I/O blocked processes={}, major page"
        " faults={}{}{}{})\n".format(
            self.id,
            date,
            self.total_cpu_time_ms,
            self.total_cpu_cycles,
            self.idle_cpu_time_ms,
            self.io_wait_time_ms,
            self.context_switches,
            self.io_blocked_processes,
            self.major_page_faults,
            package_cpu_stats_dump,
            package_storage_io_read_stats_dump,
            package_storage_io_write_stats_dump,
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
    self.last_n_minutes_stats = None
    self.user_switch_stats = []
    self.custom_collection_stats = None

  def has_boot_time_stats(self):
    return self.boot_time_stats and not self.boot_time_stats.is_empty()

  def has_last_n_minutes_stats(self):
    return (
        self.last_n_minutes_stats and not self.last_n_minutes_stats.is_empty()
    )

  def has_custom_collection_stats(self):
    return (
        self.custom_collection_stats
        and not self.custom_collection_stats.is_empty()
    )

  def is_empty(self):
    return (
        not self.has_boot_time_stats()
        and not self.has_last_n_minutes_stats()
        and not self.has_custom_collection_stats()
        and not any(map(lambda u: not u.is_empty(), self.user_switch_stats))
    )

  def to_dict(self):
    return {
        "boot_time_stats": (
            self.boot_time_stats.to_list() if self.boot_time_stats else None
        ),
        "last_n_minutes_stats": (
            self.last_n_minutes_stats.to_list()
            if self.last_n_minutes_stats
            else None
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
        "\nlast n minutes stats={}\n"
        "\nuser-switch stats={}\n"
        "\ncustom-collection stats={}\n)".format(
            self.boot_time_stats,
            self.last_n_minutes_stats,
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
    for line in f:
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


def _is_stats_section_end(line):
  return (
      line.startswith("Top N")
      or re.match(STATS_COLLECTION_PATTERN, line)
      or line.startswith("-" * COLLECTION_END_LINE_MIN_LEN)
  )


def _parse_cpu_stats(lines, idx):
  package_cpu_stats = []
  package_cpu_stat = None

  while not _is_stats_section_end(line := lines[idx].rstrip()):
    if match := re.match(PACKAGE_CPU_STATS_PATTERN, line):
      cpu_cycles_str = match.group("cpuCycles")

      package_cpu_stat = PackageCpuStats(
          int(match.group("userId")),
          match.group("packageName"),
          int(match.group("cpuTimeMs")),
          float(match.group("cpuTimePercent")),
          int(cpu_cycles_str) if cpu_cycles_str is not None else -1,
      )
      package_cpu_stats.append(package_cpu_stat)
    elif match := re.match(PROCESS_CPU_STATS_PATTERN, line):
      command = match.group("command")
      cpu_cycles_str = match.group("cpuCycles")
      if package_cpu_stat:
        package_cpu_stat.process_cpu_stats.append(
            ProcessCpuStats(
                command,
                int(match.group("cpuTimeMs")),
                float(match.group("uidCpuPercent")),
                int(cpu_cycles_str) if cpu_cycles_str is not None else -1,
            )
        )
      else:
        print(
            "No package CPU stats parsed for process:", command, file=sys.stderr
        )

    idx += 1

  return package_cpu_stats, idx


def _parse_storage_io_stats(lines, idx):
  package_storage_io_stats = []

  while not _is_stats_section_end(line := lines[idx].rstrip()):
    if match := re.match(PACKAGE_STORAGE_IO_STATS_PATTERN, line):
      package_storage_io_stats.append(
          PackageStorageIoStats(
              int(match.group("userId")),
              match.group("packageName"),
              int(match.group("fgBytes")),
              float(match.group("fgBytesPercent")),
              int(match.group("fgFsync")),
              float(match.group("fgFsyncPercent")),
              int(match.group("bgBytes")),
              float(match.group("bgBytesPercent")),
              int(match.group("bgFsync")),
              float(match.group("bgFsyncPercent")),
          )
      )

    idx += 1

  return package_storage_io_stats, idx


def _parse_collection(lines, idx, match):
  collection = StatsCollection()
  collection.id = int(match.group("id"))
  collection.date = datetime.strptime(match.group("date"), DUMP_DATETIME_FORMAT)

  while not (
      re.match(STATS_COLLECTION_PATTERN, (line := lines[idx].strip()))
      or line.startswith("-" * COLLECTION_END_LINE_MIN_LEN)
  ):
    if match := re.match(TOTAL_CPU_TIME_PATTERN, line):
      collection.total_cpu_time_ms = int(match.group("totalCpuTimeMs"))
    if match := re.match(TOTAL_CPU_CYCLES_PATTERN, line):
      collection.total_cycles = int(match.group("totalCpuCycles"))
    elif match := re.match(TOTAL_IDLE_CPU_TIME_PATTERN, line):
      collection.idle_cpu_time_ms = int(match.group("idleCpuTimeMs"))
    elif match := re.match(CPU_IO_WAIT_TIME_PATTERN, line):
      collection.io_wait_time_ms = int(match.group("iowaitCpuTimeMs"))
    elif match := re.match(CONTEXT_SWITCHES_PATTERN, line):
      collection.context_switches = int(match.group("totalCtxtSwitches"))
    elif match := re.match(IO_BLOCKED_PROCESSES_PATTERN, line):
      collection.io_blocked_processes = int(match.group("totalIoBlkProc"))
    elif match := re.match(MAJOR_PAGE_FAULTS_PATTERN, line):
      collection.major_page_faults = int(match.group("totalMajPgFaults"))
    elif line == TOP_N_CPU_TIME_HEADER:
      idx += 1  # Skip subsection header
      package_cpu_stats, idx = _parse_cpu_stats(lines, idx)
      collection.package_cpu_stats = package_cpu_stats
      continue
    elif re.match(TOP_N_STORAGE_IO_READS_HEADER_PATTERN, line):
      idx += 1
      package_storage_io_stats, idx = _parse_storage_io_stats(lines, idx)
      collection.package_storage_io_read_stats = package_storage_io_stats
      continue
    elif re.match(TOP_N_STORAGE_IO_WRITES_HEADER_PATTERN, line):
      idx += 1
      package_storage_io_stats, idx = _parse_storage_io_stats(lines, idx)
      collection.package_storage_io_write_stats = package_storage_io_stats
      continue
    idx += 1

  return collection, idx


def _parse_stats_collections(lines, idx):
  system_event_stats = SystemEventStats()
  while not (line := lines[idx].strip()).startswith("-" * 50):
    if match := re.match(STATS_COLLECTION_PATTERN, line):
      idx += 1  # Skip the collection header
      collection, idx = _parse_collection(lines, idx, match)
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
    if re.match(BOOT_TIME_REPORT_HEADER_PATTERN, line):
      boot_time_stats, idx = _parse_stats_collections(lines, idx)
      if not boot_time_stats.is_empty():
        performance_stats.boot_time_stats = boot_time_stats
    if (
        line == PERIODIC_COLLECTION_HEADER
        or line == LAST_N_MINS_COLLECTION_HEADER
    ):
      last_n_minutes_stats, idx = _parse_stats_collections(lines, idx)
      if not last_n_minutes_stats.is_empty():
        performance_stats.last_n_minutes_stats = last_n_minutes_stats
    if line == CUSTOM_COLLECTION_REPORT_HEADER:
      idx += 2  # Skip the dashed-line after the custom collection header
      custom_collection_stats, idx = _parse_stats_collections(lines, idx)
      if not custom_collection_stats.is_empty():
        performance_stats.custom_collection_stats = custom_collection_stats
    else:
      idx += 1

  return performance_stats
