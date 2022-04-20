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
"""Tool to analyze CPU performance from perfetto trace
This too assumes that core clocks are fixed.
It will not give accurate results if clock frequecies change.
Should install perfetto: $ pip install perfetto
"""

import argparse
import sys

from perfetto.trace_processor import TraceProcessor

from config import get_script_dir as get_script_dir
from config import parse_config as parse_config

# Get total idle time and active time from each core
QUERY_SCHED_CORE_SUM = """SELECT
  cpu AS core,
  SUM (CASE
        WHEN utid = 0 THEN 0
        ELSE dur
  END) AS activeTime,
  SUM (CASE
        WHEN utid = 0 THEN dur
        ELSE 0
  END) AS idleTime
FROM sched
GROUP BY cpu
ORDER BY cpu"""

class CoreLoad:
  def __init__(self, coreId, totalCycles):
    self.coreId = coreId
    self.totalCycles = totalCycles

class ProcessInfo:
  def __init__(self, name):
    self.name = name
    self.perCoreLoads = {} # key: core, value :CoreLoad

  def addCoreLoad(self, load):
    self.perCoreLoads[load.coreId] = load

  def getCoreCycles(self, coreId):
    l = self.perCoreLoads.get(coreId)
    if l is None:
      return 0
    return l.totalCycles

  def getTotalCycles(self):
    sum = 0
    for c in self.perCoreLoads:
      l = self.perCoreLoads[c]
      sum = sum + l.totalCycles
    return sum

  def print(self, totalCpuCycles, perCoreTotalCycles):
    msgs = []
    msgs.append(("{}: total: {:.3f}%".format(self.name, float(self.getTotalCycles()) /\
                                            totalCpuCycles * 100.0)))
    msgs.append(50 * "-")
    for c in sorted(self.perCoreLoads):
      l = self.perCoreLoads[c]
      msgs.append("{:<10} {:<15}".format("Core {}".format(c),\
                                          "{:.3f}%".format(float(l.totalCycles) /\
                                                        perCoreTotalCycles[c] * 100.0)))
    print('\n'.join(msgs) + '\n')

class TotalCoreLoad:
  def __init__(self, coreId, activeTime, idleTime):
    self.coreId = coreId
    self.activeTime = activeTime
    self.idleTime = idleTime
    self.loadPercentile = float(activeTime) / (idleTime + activeTime) * 100.0

class SystemLoad:
  def __init__(self):
    self.totalLoads = [] # TotalCoreLoad
    self.totalLoad = 0.0
    self.processes = [] # ProcessInfo

  def addTimeMeasurements(self, coreData, allCores):
    coreLoads = {} # k: core, v: TotalCoreLoad
    maxTotalTime = 0
    for entry in coreData:
      coreId = entry.core
      activeTime = entry.activeTime
      idleTime = entry.idleTime
      totalTime = activeTime + idleTime
      if maxTotalTime < totalTime:
        maxTotalTime = totalTime
      load = TotalCoreLoad(coreId, activeTime, idleTime)
      coreLoads[coreId] = load
    for c in allCores:
      if coreLoads.get(c) is not None:
        continue
      # this core was not used at all. So add it with idle only
      coreLoads[c] = TotalCoreLoad(c, 0, maxTotalTime)
    for c in sorted(coreLoads):
      self.totalLoads.append(coreLoads[c])

  def print(self, cpuConfig, numTopN, filterProcesses):
    print("\nTime based CPU load\n" + 30 * "=")
    loadXClkSum = 0.0
    maxCapacity = 0.0
    perCoreCpuCycles = {}
    totalCpuCycles = 0
    maxCpuGHz = 0.0
    print("{:<10} {:<15} {:<15} {:<15}\n{}".\
          format("CPU", "CPU Load %", "CPU Usage", "Max CPU Freq.", 60 * "-"))
    for l in self.totalLoads:
      coreMaxFreqGHz = float(cpuConfig.coreMaxFreqKHz[l.coreId]) / 1e6
      coreIdStr = "Core {}".format(l.coreId)
      loadPercentileStr = "{:.3f}%".format(l.loadPercentile)
      loadUsageStr = "{:.3f} GHz".format(l.loadPercentile * coreMaxFreqGHz / 100)
      coreMaxFreqStr = "{:.3f} GHz".format(coreMaxFreqGHz)
      print("{:<10} {:<15} {:<15} {:<15}".\
            format(coreIdStr, loadPercentileStr, loadUsageStr, coreMaxFreqStr))
      maxCpuGHz += coreMaxFreqGHz
      loadXClkSum += l.loadPercentile * coreMaxFreqGHz
      perCoreCpuCycles[l.coreId] = (l.activeTime + l.idleTime) * coreMaxFreqGHz
      totalCpuCycles += perCoreCpuCycles[l.coreId]
    loadPercentile = float(loadXClkSum) / maxCpuGHz
    print("\nTotal Load: {:.3f}%, {:.2f} GHz with system max {:.2f} GHz".\
          format(loadPercentile, loadPercentile * maxCpuGHz / 100.0, maxCpuGHz))

    self.processes.sort(reverse = True, key = lambda p : p.getTotalCycles())
    print("\nFiltered processes\n" + 30 * "=")
    if filterProcesses is not None:
      processes = list(filter(
          lambda p: max(map(lambda filterName: p.name.find(filterName), filterProcesses)) > -1,
          self.processes))
      if len(processes) == 0:
        print("No process found with filters.")
      for p in processes:
        p.print(totalCpuCycles, perCoreCpuCycles)

    print("\nTop processes\n" + 30 * "=")
    for p in self.processes[:numTopN]:
      p.print(totalCpuCycles, perCoreCpuCycles)

def init_arguments():
  parser = argparse.ArgumentParser(description='Analyze CPU perf.')
  parser.add_argument('-f', '--configfile', dest='config_file',
                      default=get_script_dir() + '/pixel6.config', type=argparse.FileType('r'),
                      help='CPU config file', )
  parser.add_argument('-c', '--cpusettings', dest='cpusettings', action='store',
                      default='default',
                      help='CPU Settings to apply')
  parser.add_argument('-n', '--number_of_top_processes', dest='number_of_top_processes',
                      action='store', type=int, default=5,
                      help='Number of processes to show in performance report')
  parser.add_argument('-p', '--process-name', dest='process_name', action='append',
                      help='Name of process to filter')
  parser.add_argument('trace_file', action='store', nargs=1,
                      help='Perfetto trace file to analyze')
  return parser.parse_args()

def run_analysis(traceFile, cpuConfig, cpuSettings, numTopN=5, filterProcesses=None):
  tp = TraceProcessor(file_path=traceFile)

  systemLoad = SystemLoad()
  # get idle and active times per each cores
  core_times = tp.query(QUERY_SCHED_CORE_SUM)
  systemLoad.addTimeMeasurements(core_times, cpuSettings.onlines)

  cpu_metrics = tp.metric(['android_cpu']).android_cpu
  for p in cpu_metrics.process_info:
    info = ProcessInfo(p.name)
    for c in p.core:
      cpuFreqKHz = cpuConfig.coreMaxFreqKHz[c.id]
      if c.metrics.HasField('avg_freq_khz'):
        cpuFreqKHz = c.metrics.avg_freq_khz
      cpuCycles = cpuFreqKHz * c.metrics.runtime_ns / 1000000 # unit should be Hz * s
      l = CoreLoad(c.id, cpuCycles)
      info.addCoreLoad(l)
    systemLoad.processes.append(info)

  systemLoad.print(cpuConfig, numTopN, filterProcesses)

def main():
  args = init_arguments()

  # parse config
  cpuConfig = parse_config(args.config_file)
  cpuSettings = cpuConfig.configs.get(args.cpusettings)
  if cpuSettings is None:
    print("Cannot find cpusettings {}".format(args.cpusettings))
    return

  run_analysis(args.trace_file[0], cpuConfig, cpuSettings, args.number_of_top_processes, args.process_name)

if __name__ == '__main__':
  main()
