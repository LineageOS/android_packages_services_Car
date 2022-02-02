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
    msgs.append(("{}: total={:.3f}%".format(self.name, float(self.getTotalCycles()) /\
                                            totalCpuCycles * 100.0)))
    for c in sorted(self.perCoreLoads):
      l = self.perCoreLoads[c]
      msgs.append("c{}={:.3f}%".format(c, float(l.totalCycles) / perCoreTotalCycles[c] * 100.0))
    print(','.join(msgs))

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

  def addTimeMeasurements(self, coreData):
    for entry in coreData:
      coreId = entry.core
      activeTime = entry.activeTime
      idleTime = entry.idleTime
      load = TotalCoreLoad(coreId, activeTime, idleTime)
      self.totalLoads.append(load)

  def print(self, cpuConfig):
    print("*Time based CPU load*")
    loadXClkSum = 0.0
    maxCapacity = 0.0
    perCoreCpuCycles = {}
    totalCpuCycles = 0
    maxCpuGHz = 0.0
    for l in self.totalLoads:
      coreMaxFreqKHz = cpuConfig.coreMaxFreqKHz[l.coreId]
      coreMaxFreqGHz = coreMaxFreqKHz / 1e6
      print("Core {}: {:.3f}% busy, {:.3f} GHz used, {:.3f} GHz max".\
            format(l.coreId, l.loadPercentile, l.loadPercentile * coreMaxFreqGHz / 100,\
                   coreMaxFreqGHz))
      maxCpuGHz = maxCpuGHz + float(coreMaxFreqKHz) / 1e6
      loadXClkSum = loadXClkSum + l.loadPercentile * coreMaxFreqKHz
      # not multiply 100% to make the output %
      maxCapacity = maxCapacity + coreMaxFreqKHz
      perCoreCpuCycles[l.coreId] = (l.activeTime + l.idleTime) * coreMaxFreqKHz / 1000000
      totalCpuCycles = totalCpuCycles + perCoreCpuCycles[l.coreId]
    loadPercentile = float(loadXClkSum) / maxCapacity
    print("Total Load: {:.3f}%, {:.2f} GHz with system max {:.2f} GHz".\
          format(loadPercentile, loadPercentile * maxCpuGHz / 100.0, maxCpuGHz))

    self.processes.sort(reverse = True, key = lambda p : p.getTotalCycles())
    print("*Top processes*")
    for p in self.processes:
      p.print(totalCpuCycles, perCoreCpuCycles)

def init_arguments():
  parser = argparse.ArgumentParser(description='Analyze CPU perf.')
  parser.add_argument('-f', '--configfile', dest='config_file',
                      default=get_script_dir() + '/pixel6.config', type=argparse.FileType('r'),
                      help='CPU config file', )
  parser.add_argument('-n', '--number_of_top_processes', dest='number_of_top_processes',
                      action='store', default='5',
                      help='Number of processes to show in performance report')
  parser.add_argument('trace_file', action='store', nargs=1,
                      help='Perfetto trace file to analyze')
  return parser.parse_args()

def run_analysis(traceFile, cpuConfig, numTopN=5):
  tp = TraceProcessor(file_path=traceFile)

  systemLoad = SystemLoad()
  # get idle and active times per each cores
  core_times = tp.query(QUERY_SCHED_CORE_SUM)
  systemLoad.addTimeMeasurements(core_times)

  cpu_metrics = tp.metric(['android_cpu']).android_cpu
  for p in cpu_metrics.process_info:
    info = ProcessInfo(p.name)
    for c in p.core:
      cpuFreqKHz = cpuConfig.coreMaxFreqKHz[c.id]
      if c.metrics.HasField('avg_freq_khz'):
        cpuFreqKHz = c.metrics.avg_freq_khz
      cpuCycles = cpuFreqKHz * c.metrics.runtime_ns / 1000000 # unit should be Hz / s
      l = CoreLoad(c.id, cpuCycles)
      info.addCoreLoad(l)
    systemLoad.processes.append(info)

  systemLoad.print(cpuConfig)

def main():
  args = init_arguments()

  # parse config
  cpuConfig = parse_config(args.config_file)

  run_analysis(args.trace_file[0], cpuConfig, args.number_of_top_processes)

if __name__ == '__main__':
  main()
