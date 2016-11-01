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

TIME_DMESG = "\[\s*(\d+\.\d+)\]"
TIME_LOGCAT = "[0-9]+\.?[0-9]*"
VALUE_TOO_BIG = 600
MAX_RETRIES = 5
DEBUG = False

def main():
  args = init_arguments()

  if args.iterate < 1:
    raise Exception('Number of iteration must be >=1');

  if args.iterate > 1 and not args.reboot:
    print "Forcing reboot flag"
    args.reboot = True

  cfg = yaml.load(args.config)

  search_events = {key: re.compile(pattern)
                   for key, pattern in cfg['events'].iteritems()}
  timing_events = {key: re.compile(pattern)
                   for key, pattern in cfg['timings'].iteritems()}
  if 'stop_event' not in cfg:
    raise Exception('Logcat stop_event is missing in config');

  data_points = {}
  timing_points = {}
  for it in range(0, args.iterate):
    if args.iterate > 1:
      print "Run: {0}".format(it + 1)
    attempt = 1
    processing_data = None
    timings = None
    while attempt <= MAX_RETRIES and processing_data is None:
      attempt += 1
      processing_data, timings = iterate(
        args, search_events, timing_events, cfg)

    if processing_data is None:
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

  if args.iterate > 1:
    if timing_points and args.timings:
      print "Avg time values after {0} runs".format(args.iterate)
      print '{0:30}: {1:<7} {2:<7}'.format("Event", "Mean", "stddev")

      for item in sorted(timing_points.items(), key=operator.itemgetter(1)):
        print '{0:30}: {1:<7.5} {2:<7.5}'.format(
          item[0], sum(item[1])/len(item[1]), stddev(item[1]))

    print "Avg values after {0} runs".format(args.iterate)
    print '{0:30}: {1:<7} {2:<7}'.format("Event", "Mean", "stddev")

    for item in sorted(data_points.items(), key=operator.itemgetter(1)):
      print '{0:30}: {1:<7.5} {2:<7.5}'.format(
        item[0], sum(item[1])/len(item[1]), stddev(item[1]))

def iterate(args, search_events, timings, cfg):
  if args.reboot:
    reboot()

  logcat_events, logcat_timing_events = collect_events(
    search_events, 'adb logcat -b all -v epoch', timings, cfg['stop_event'])

  dmesg_events, e = collect_events(search_events, 'adb shell dmesg', {})

  logcat_event_time = extract_time(
    logcat_events, TIME_LOGCAT, float);
  logcat_original_time = extract_time(
    logcat_events, TIME_LOGCAT, str);
  dmesg_event_time = extract_time(
    dmesg_events, TIME_DMESG, float);

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
    debug("event[{0}, {1}]".format(k, v))
    if v <= time_correction_time:
      logcat_event_time[k] += time_correction_delta
      v = v + time_correction_delta
      debug("correcting event to event[{0}, {1}]".format(k, v))

    events[k] = v
    if k in dmesg_event_time:
      debug("{0} is in dmesg".format(k))
      if dmesg_event_time[k] > max_time:
        debug("{0} is bigger max={1}".format(dmesg_event_time[k], max_time))
        max_time = dmesg_event_time[k]
        diff_time = v - max_time
        debug("diff_time={0}".format(diff_time))
      events[k] = dmesg_event_time[k]
      replaced_from_dmesg.add(k)
    else:
      events_to_correct.append(k)

  debug("diff_time={0}".format(diff_time))
  for k in events_to_correct:
    debug("k={0}, {1}".format(k, events[k]))
    if round(events[k] - diff_time, 3) >= 0:
      events[k] = round(events[k] - diff_time, 3)
      if events[k] > VALUE_TOO_BIG and not args.ignore:
        print "Event {0} value {1} too big , possible processing error".format(
          k, events[k])
        return None, None

  data_points = {}
  timing_points = {}

  if args.timings:
    for k, l in logcat_timing_events.iteritems():
      for v in l:
        name, time_v = extract_timing(v, timings)
        if name:
          timing_points[name] = time_v
    print "Event timing"
    for item in sorted(timing_points.items(), key=operator.itemgetter(1)):
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
  return data_points, timing_points

def debug(string):
  if DEBUG:
    print string

def extract_timing(s, patterns):
  for k, p in patterns.iteritems():
    m = p.search(s)
    if m:
      g_dict = m.groupdict()
      return g_dict['name'], float(g_dict['time'])
  return None, Node

def init_arguments():
  parser = argparse.ArgumentParser(description='Measures boot time.')
  parser.add_argument('-r', '--reboot', dest='reboot',
                      action='store_true',
                      help='adb reboot device for measurement', )
  parser.add_argument('-c', '--config', dest='config',
                      default='config.yaml', type=argparse.FileType('r'),
                      help='config file for the tool', )
  parser.add_argument('-n', '--iterate', dest='iterate', type=int, default=1,
                      help='number of time to repeat the measurement', )
  parser.add_argument('-g', '--ignore', dest='ignore', action='store_true',
                      help='ignore too big values error', )
  parser.add_argument('-t', '--timings', dest='timings', action='store_true',
                      help='print individual component times', default=True, )

  return parser.parse_args()

def collect_events(search_events, command, timings, stop_event=None):
  events = {}
  timing_events = {}
  process = subprocess.Popen(command, shell=True,
                             stdout=subprocess.PIPE);
  out = process.stdout
  data_available = stop_event is None
  for line in out:
    if not data_available:
      print "Collecting data samples. Please wait...\n"
      data_available = True
    event = get_boot_event(line, search_events);
    if event:
      debug("event[{0}] captured: {1}".format(event, line))
      events[event] = line

    timing_event = get_boot_event(line, timings);
    if timing_event:
      if timing_event not in timing_events:
        timing_events[timing_event] = []
      timing_events[timing_event].append(line)
      debug("timing_event[{0}] captured: {1}".format(timing_event, line))

    if stop_event and stop_event in line:
      break;
  process.terminate()
  return events, timing_events

def get_boot_event(line, events):
  for event_key, event_pattern in events.iteritems():
    if event_pattern.search(line):
      return event_key
  return None

def extract_time(events, pattern, date_transform_function):
  result = {}
  for event, data in events.iteritems():
    found = re.findall(pattern, data)
    if len(found) > 0:
      result[event] = date_transform_function(found[0])
    else:
      print "Failed to find time for event: ", event, data
  return result

def reboot():
  print 'Rebooting the device'
  subprocess.Popen('adb reboot', shell=True).wait()

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
