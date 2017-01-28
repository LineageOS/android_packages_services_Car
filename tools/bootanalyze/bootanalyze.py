#!/usr/bin/python

# Copyright (C) 2016 The Android Open Source Project
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
"""Tool to analyze logcat and dmesg logs.

bootanalyze read logcat and dmesg loga and determines key points for boot.
"""

import yaml
import argparse
import re
import os
import subprocess
import time
import math
import datetime
import sys
import operator
import collections
from datetime import datetime, date


TIME_DMESG = "\[\s*(\d+\.\d+)\]"
TIME_LOGCAT = "[0-9]+\.?[0-9]*"
KERNEL_TIME_KEY = "kernel"
BOOT_ANIM_END_TIME_KEY = "BootAnimEnd"
KERNEL_BOOT_COMPLETE = "BootComplete_kernel"
LOGCAT_BOOT_COMPLETE = "BootComplete"
BOOT_TIME_TOO_BIG = 200.0
MAX_RETRIES = 5
DEBUG = False
ADB_CMD = "adb"
TIMING_THRESHOLD = 5.0
BOOT_PROP = "\[ro\.boottime\.([^\]]+)\]:\s+\[(\d+)\]"

def main():
  global ADB_CMD

  args = init_arguments()

  if args.iterate < 1:
    raise Exception('Number of iteration must be >=1');

  if args.iterate > 1 and not args.reboot:
    print "Forcing reboot flag"
    args.reboot = True

  if args.serial:
    ADB_CMD = "%s %s" % ("adb -s", args.serial)

  error_time = BOOT_TIME_TOO_BIG * 10
  if args.errortime:
    error_time = float(args.errortime)

  cfg = yaml.load(args.config)

  search_events = {key: re.compile(pattern)
                   for key, pattern in cfg['events'].iteritems()}
  timing_events = {key: re.compile(pattern)
                   for key, pattern in cfg['timings'].iteritems()}

  data_points = {}
  timing_points = collections.OrderedDict()
  boottime_points = collections.OrderedDict()
  for it in range(0, args.iterate):
    if args.iterate > 1:
      print "Run: {0}".format(it + 1)
    attempt = 1
    processing_data = None
    timings = None
    boottime_events = None
    while attempt <= MAX_RETRIES and processing_data is None:
      attempt += 1
      processing_data, timings, boottime_events = iterate(
        args, search_events, timing_events, cfg, error_time)

    if not processing_data or not boottime_events:
      # Processing error
      print "Failed to collect valid samples for run {0}".format(it + 1)
      continue
    for k, v in processing_data.iteritems():
      if k not in data_points:
        data_points[k] = []
      data_points[k].append(v['value'])

    if timings is not None:
      for k, v in timings.iteritems():
        if k not in timing_points:
          timing_points[k] = []
        timing_points[k].append(v)

    for k, v in boottime_events.iteritems():
      if not k in boottime_points:
        boottime_points[k] = []
      boottime_points[k].append(v)

  if args.iterate > 1:
    print "-----------------"
    print "ro.boottime.* after {0} runs".format(args.iterate)
    print '{0:30}: {1:<7} {2:<7}'.format("Event", "Mean", "stddev")
    for item in boottime_points.items():
        print '{0:30}: {1:<7.5} {2:<7.5} {3}'.format(
          item[0], sum(item[1])/len(item[1]), stddev(item[1]),\
          "*time taken" if item[0].startswith("init.") else "")

    if timing_points and args.timings:
      print "-----------------"
      print "Timing in order, Avg time values after {0} runs".format(args.iterate)
      print '{0:30}: {1:<7} {2:<7}'.format("Event", "Mean", "stddev")

      for item in timing_points.items():
        print '{0:30}: {1:<7.5} {2:<7.5}'.format(
          item[0], sum(item[1])/len(item[1]), stddev(item[1]))

      print "-----------------"
      print "Timing top items, Avg time values after {0} runs".format(args.iterate)
      print '{0:30}: {1:<7} {2:<7}'.format("Event", "Mean", "stddev")
      for item in sorted(timing_points.items(), key=operator.itemgetter(1), reverse=True):
        average = sum(item[1])/len(item[1])
        if average < TIMING_THRESHOLD:
          break
        print '{0:30}: {1:<7.5} {2:<7.5}'.format(
          item[0], average, stddev(item[1]))

    print "-----------------"
    print "Avg values after {0} runs".format(args.iterate)
    print '{0:30}: {1:<7} {2:<7}'.format("Event", "Mean", "stddev")

    average_with_stddev = []
    for item in data_points.items():
      average_with_stddev.append((item[0], sum(item[1])/len(item[1]), stddev(item[1])))
    for item in sorted(average_with_stddev, key=lambda entry: entry[1]):
      print '{0:30}: {1:<7.5} {2:<7.5}'.format(
        item[0], item[1], item[2])

def iterate(args, search_events, timings, cfg, error_time):
  if args.reboot:
    reboot()

  logcat_events, logcat_timing_events = collect_events(
    search_events, ADB_CMD + ' logcat -b all -v epoch', timings, [ LOGCAT_BOOT_COMPLETE,\
                                                                   KERNEL_BOOT_COMPLETE ])

  dmesg_events, e = collect_events(search_events, ADB_CMD + ' shell su root dmesg -w', {},\
                                   [ KERNEL_BOOT_COMPLETE ])

  logcat_event_time = extract_time(
    logcat_events, TIME_LOGCAT, float);
  logcat_original_time = extract_time(
    logcat_events, TIME_LOGCAT, str);
  dmesg_event_time = extract_time(
    dmesg_events, TIME_DMESG, float);
  boottime_events = fetch_boottime_property()
  events = {}
  diff_time = 0
  max_time = 0
  events_to_correct = []
  replaced_from_dmesg = set()

  time_correction_delta = 0
  time_correction_time = 0
  if ('time_correction_key' in cfg
      and cfg['time_correction_key'] in logcat_events):
    match = search_events[cfg['time_correction_key']].search(
      logcat_events[cfg['time_correction_key']])
    if match and logcat_event_time[cfg['time_correction_key']]:
      time_correction_delta = float(match.group(1))
      time_correction_time = logcat_event_time[cfg['time_correction_key']]

  debug("time_correction_delta = {0}, time_correction_time = {1}".format(
    time_correction_delta, time_correction_time))

  for k, v in logcat_event_time.iteritems():
    if v <= time_correction_time:
      logcat_event_time[k] += time_correction_delta
      v = v + time_correction_delta
      debug("correcting event to event[{0}, {1}]".format(k, v))

  if not logcat_event_time.get(KERNEL_TIME_KEY):
    print "kernel time not captured in logcat, cannot get time diff"
    return None, None, None
  diffs = []
  diffs.append((logcat_event_time[KERNEL_TIME_KEY], logcat_event_time[KERNEL_TIME_KEY]))
  if logcat_event_time.get(BOOT_ANIM_END_TIME_KEY) and dmesg_event_time.get(BOOT_ANIM_END_TIME_KEY):
      diffs.append((logcat_event_time[BOOT_ANIM_END_TIME_KEY],\
                    logcat_event_time[BOOT_ANIM_END_TIME_KEY] -\
                      dmesg_event_time[BOOT_ANIM_END_TIME_KEY]))
  if not dmesg_event_time.get(KERNEL_BOOT_COMPLETE):
      print "BootAnimEnd time or BootComplete-kernel not captured in both log" +\
        ", cannot get time diff"
      return None, None, None
  diffs.append((logcat_event_time[KERNEL_BOOT_COMPLETE],\
                logcat_event_time[KERNEL_BOOT_COMPLETE] - dmesg_event_time[KERNEL_BOOT_COMPLETE]))

  for k, v in logcat_event_time.iteritems():
    debug("event[{0}, {1}]".format(k, v))
    events[k] = v
    if k in dmesg_event_time:
      debug("{0} is in dmesg".format(k))
      events[k] = dmesg_event_time[k]
      replaced_from_dmesg.add(k)
    else:
      events_to_correct.append(k)

  diff_prev = diffs[0]
  for k in events_to_correct:
    diff = diffs[0]
    while diff[0] < events[k] and len(diffs) > 1:
      diffs.pop(0)
      diff_prev = diff
      diff = diffs[0]
    events[k] = events[k] - diff[1]
    if events[k] < 0.0:
        if events[k] < -0.1: # maybe previous one is better fit
          events[k] = events[k] + diff[1] - diff_prev[1]
        else:
          events[k] = 0.0

  data_points = {}
  timing_points = collections.OrderedDict()


  print "-----------------"
  print "ro.boottime.*: time"
  for item in boottime_events.items():
    print '{0:30}: {1:<7.5} {2}'.format(item[0], item[1],\
      "*time taken" if item[0].startswith("init.") else "")
  print "-----------------"

  if args.timings:
    timing_abs_times = []
    for k, l in logcat_timing_events.iteritems():
      for v in l:
        name, time_v = extract_timing(v, timings)
        time_abs = extract_a_time(v, TIME_LOGCAT, float)
        if name and time_abs:
          timing_points[name] = time_v
          timing_abs_times.append(time_abs * 1000.0)
    timing_delta = []
    if len(timing_points.items()) > 0:
      timing_delta.append(timing_points.items()[0][1])
    for i in range(1, len(timing_abs_times)):
      timing_delta.append(timing_abs_times[i] -  timing_abs_times[i - 1])
    print "Event timing in time order, key: time (delta from prev too big)"
    for item in timing_points.items():
      delta = timing_delta.pop(0)
      msg = ""
      if (delta - item[1]) > TIMING_THRESHOLD:
        msg = "**big delta from prev step:" + str(delta)
      print '{0:30}: {1:<7.5} {2}'.format(
        item[0], item[1], msg)
    print "-----------------"
    print "Event timing top items"
    for item in sorted(timing_points.items(), key=operator.itemgetter(1), reverse = True):
      if item[1] < TIMING_THRESHOLD:
        break
      print '{0:30}: {1:<7.5}'.format(
        item[0], item[1])
    print "-----------------"

  for item in sorted(events.items(), key=operator.itemgetter(1)):
    data_points[item[0]] = {
      'value': item[1],
      'from_dmesg': item[0] in replaced_from_dmesg,
      'logcat_value': logcat_original_time[item[0]]
    }
    print '{0:30}: {1:<7.5} {2:1} ({3})'.format(
      item[0], item[1], '*' if item[0] in replaced_from_dmesg else '',
      logcat_original_time[item[0]])

  print '\n* - event time was obtained from dmesg log\n'

  if events[LOGCAT_BOOT_COMPLETE] > error_time and not args.ignore:
    now = datetime.now()
    bugreport_file = "bugreport-bootuptoolong-%s_%s.zip" % (now.strftime("%Y-%m-%d-%H-%M-%S"), \
                                                            str(events[LOGCAT_BOOT_COMPLETE]))
    print "Boot up time too big, treated as error, will capture bugreport %s and reject data"\
       % (bugreport_file)
    os.system(ADB_CMD + " bugreport " + bugreport_file)
    return None, None, None

  return data_points, timing_points, boottime_events

def debug(string):
  if DEBUG:
    print string

def extract_timing(s, patterns):
  for k, p in patterns.iteritems():
    m = p.search(s)
    if m:
      g_dict = m.groupdict()
      return g_dict['name'], float(g_dict['time'])
  return None, None

def init_arguments():
  parser = argparse.ArgumentParser(description='Measures boot time.')
  parser.add_argument('-r', '--reboot', dest='reboot',
                      action='store_true',
                      help='reboot device for measurement', )
  parser.add_argument('-c', '--config', dest='config',
                      default='config.yaml', type=argparse.FileType('r'),
                      help='config file for the tool', )
  parser.add_argument('-n', '--iterate', dest='iterate', type=int, default=1,
                      help='number of time to repeat the measurement', )
  parser.add_argument('-g', '--ignore', dest='ignore', action='store_true',
                      help='ignore too big values error', )
  parser.add_argument('-t', '--timings', dest='timings', action='store_true',
                      help='print individual component times', default=True, )
  parser.add_argument('-p', '--serial', dest='serial', action='store',
                      help='android device serial number')
  parser.add_argument('-e', '--errortime', dest='errortime', action='store',
                      help='handle bootup time bigger than this as error')
  return parser.parse_args()

def handle_zygote_event(zygote_pids, events, event, line):
  words = line.split()
  if len(words) > 1:
    pid = int(words[1])
    if len(zygote_pids) == 2:
      if pid == zygote_pids[1]: # secondary
        event = event + "-secondary"
    elif len(zygote_pids) == 1:
      if zygote_pids[0] != pid: # new pid, need to decide if old ones were secondary
        primary_pid = min(pid, zygote_pids[0])
        secondary_pid = max(pid, zygote_pids[0])
        zygote_pids.pop()
        zygote_pids.append(primary_pid)
        zygote_pids.append(secondary_pid)
        if pid == primary_pid: # old one was secondary:
          move_to_secondary = []
          for k, l in events.iteritems():
            if k.startswith("zygote"):
              move_to_secondary.append((k, l))
          for item in move_to_secondary:
            del events[item[0]]
            if item[0].endswith("-secondary"):
              print "Secondary already exists for event %s  while found new pid %d, primary %d "\
                % (item[0], secondary_pid, primary_pid)
            else:
              events[item[0] + "-secondary"] = item[1]
        else:
          event = event + "-secondary"
    else:
      zygote_pids.append(pid)
  events[event] = line

def collect_events(search_events, command, timings, stop_events):
  events = collections.OrderedDict()
  timing_events = {}
  process = subprocess.Popen(command, shell=True,
                             stdout=subprocess.PIPE);
  out = process.stdout
  data_available = stop_events is None
  zygote_pids = []

  for line in out:
    if not data_available:
      print "Collecting data samples from '%s'. Please wait...\n" % command
      data_available = True
    event = get_boot_event(line, search_events);
    if event:
      debug("event[{0}] captured: {1}".format(event, line))
      if event.startswith("zygote"):
        handle_zygote_event(zygote_pids, events, event, line)
      else:
        events[event] = line
      if event in stop_events:
        stop_events.remove(event)
        if len(stop_events) == 0:
          break;

    timing_event = get_boot_event(line, timings);
    if timing_event:
      if timing_event not in timing_events:
        timing_events[timing_event] = []
      timing_events[timing_event].append(line)
      debug("timing_event[{0}] captured: {1}".format(timing_event, line))

  process.terminate()
  return events, timing_events

def fetch_boottime_property():
  cmd = ADB_CMD + ' shell su root getprop'
  events = {}
  process = subprocess.Popen(cmd, shell=True,
                             stdout=subprocess.PIPE);
  out = process.stdout
  pattern = re.compile(BOOT_PROP)
  for line in out:
    match = pattern.match(line)
    if match:
      events[match.group(1)] = float(match.group(2)) / 1000000000.0 #ns to s
  ordered_event = collections.OrderedDict()
  for item in sorted(events.items(), key=operator.itemgetter(1)):
    ordered_event[item[0]] = item[1]
  return ordered_event


def get_boot_event(line, events):
  for event_key, event_pattern in events.iteritems():
    if event_pattern.search(line):
      return event_key
  return None

def extract_a_time(line, pattern, date_transform_function):
    found = re.findall(pattern, line)
    if len(found) > 0:
      return date_transform_function(found[0])
    else:
      return None

def extract_time(events, pattern, date_transform_function):
  result = collections.OrderedDict()
  for event, data in events.iteritems():
    time = extract_a_time(data, pattern, date_transform_function)
    if time:
      result[event] = time
    else:
      print "Failed to find time for event: ", event, data
  return result

def reboot():
  print 'Rebooting the device'
  subprocess.call(ADB_CMD + ' shell su root svc power reboot', shell=True)
  print 'Waiting the device'
  subprocess.call(ADB_CMD + ' wait-for-device', shell=True)

def logcat_time_func(offset_year):
  def f(date_str):
    ndate = datetime.datetime.strptime(str(offset_year) + '-' +
                                 date_str, '%Y-%m-%d %H:%M:%S.%f')
    return datetime_to_unix_time(ndate)
  return f


def datetime_to_unix_time(ndate):
  return time.mktime(ndate.timetuple()) + ndate.microsecond/1000000.0

def stddev(data):
  items_count = len(data)
  avg = sum(data) / items_count
  sq_diffs_sum = sum([(v - avg) ** 2 for v in data])
  variance = sq_diffs_sum / items_count
  return math.sqrt(variance)

if __name__ == '__main__':
  main()
