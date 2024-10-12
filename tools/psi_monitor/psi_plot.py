#!/usr/bin/env python

import argparse
import pandas as pd

from bokeh.layouts import column
from bokeh.models import ColumnDataSource
from bokeh.models import HoverTool
from bokeh.models import LabelSet
from bokeh.models import TapTool
from bokeh.models import VSpan
from bokeh.plotting import figure
from bokeh.plotting import output_file
from bokeh.plotting import save
from bokeh.plotting import show
from typing import Tuple

SHADES_OF_GREEN = ['GreenYellow', 'LimeGreen', 'LightGreen', 'SeaGreen', 'Green', 'DarkGreen',
                 'YellowGreen', 'DarkOliveGreen', 'MediumAquaMarine', 'Teal']
PLOT_HEIGHT = 600
PLOT_WIDTH = 1500
X_AXIS_FIELD = 'monitor_start_relative_millis'
X_AXIS_LABEL = 'Time since start of PSI monitor (millis)'

def add_events_to_plot(plot, events_df, vspan_opts, fixed_y_pos=0, color_list=None) -> figure:
  vspan_source = dict(x_pos=[], color=[], y_pos=[], desc=[])
  for index, row in events_df.iterrows():
    vspan_source['x_pos'].append(row[X_AXIS_FIELD])
    vspan_source['y_pos'].append(fixed_y_pos)
    vspan_source['desc'].append(row['event_description'])
    if 'color' in row:
      vspan_source['color'].append(row.color)
    elif color_list:
      vspan_source['color'].append(color_list[index % len(color_list)])
    else:
      vspan_source['color'].append('black')
    line_width= 4 if 'is_high_latency_event' in row and row['is_high_latency_event'] else 2

  glyph = VSpan(x='x_pos', line_width=line_width, line_dash='dashed', line_color='color')
  plot.add_glyph(ColumnDataSource(data=vspan_source), glyph)

  plot.add_layout(LabelSet(**vspan_opts, source=ColumnDataSource(data=vspan_source)))
  return plot


def get_psi_plot(cuj_name, psi_df, x_range_list) -> figure:
  if psi_df.empty:
    return None

  plot = figure(title="PSI metrics during CUJ '" + cuj_name + "'", width=PLOT_WIDTH,
                height=PLOT_HEIGHT, x_axis_label=X_AXIS_LABEL,
                y_axis_label='PSI %', x_range=x_range_list, y_range=[0, 100],
                x_axis_type='datetime', tooltips=[('X', '$x'), ('Psi', '$y'), ('Desc', '@desc')])

  psi_source_dict = dict(
      x_axis=[],
      psi=[psi_df.memory_full_avg10.values, psi_df.io_full_avg10.values,
           psi_df.cpu_some_avg10.values, psi_df.memory_some_avg10.values,
           psi_df.io_some_avg10.values],
      color=['red', 'blue', 'sandybrown', 'yellow', 'purple'],
      desc=['Memory full avg10', 'IO full avg10', 'CPU some avg10', 'Memory some avg10',
                    'IO some avg10'])
  if 'cpu_full_avg10' in psi_df.columns:
    psi_source_dict['psi'].append(psi_df.cpu_full_avg10.values)
    psi_source_dict['color'].append('black')
    psi_source_dict['desc'].append('CPU full avg10')

  if 'irq_full_avg10' in psi_df.columns:
    psi_source_dict['psi'].append(psi_df.irq_full_avg10.values)
    psi_source_dict['color'].append('pink')
    psi_source_dict['desc'].append('IRQ full avg10')

  x_axis = pd.to_timedelta(pd.Series(psi_df[X_AXIS_FIELD].values), unit='ms')
  psi_source_dict['x_axis'] = [x_axis for _ in psi_source_dict['psi']]

  plot.multi_line(xs='x_axis', ys='psi', source=ColumnDataSource(data=psi_source_dict),
                  color='color', legend_field='desc', line_width=3)
  plot.add_tools(TapTool())

  vspan_opts = dict(x='x_pos', y='y_pos', text='desc', x_units='data', y_units='data',
                    level='annotation', angle=90, angle_units='deg', x_offset=-15, y_offset=-200,
                    text_font_size='10pt', text_color='black', text_alpha=1.0, text_align='center',
                    text_baseline='middle')

  psi_events_df = psi_df[psi_df['event_description'].notna()].reset_index()
  if not psi_events_df.empty:
    plot = add_events_to_plot(plot, psi_events_df, vspan_opts, 100, SHADES_OF_GREEN)

  return plot


def get_events_plot(cuj_name, events_df, x_range_obj) -> figure:
  if events_df.empty:
    return None

  plot = figure(title="Events during CUJ '" + cuj_name + "'", width=PLOT_WIDTH, height=PLOT_HEIGHT,
                x_axis_label=X_AXIS_LABEL, y_axis_label='Event', x_range=x_range_obj,
                y_range=[0, 100], x_axis_type='datetime', tooltips=[('X', '@x_pos'),
                                                                    ('Event', '@desc')])

  vspan_opts = dict(x='x_pos', y='y_pos', x_units='data', y_units='data')

  plot = add_events_to_plot(plot, events_df, vspan_opts)

  return plot


def save_and_show_plot(plot, out_file, show_plot):
  output_file(out_file)
  save(plot)
  if show_plot:
    show(plot)


def get_x_range_list(psi_df, events_df) -> Tuple[int, int]:
  if events_df is not None and not events_df.empty:
    return (min(events_df[X_AXIS_FIELD].min(), psi_df[X_AXIS_FIELD].min()),
            max(events_df[X_AXIS_FIELD].max(), psi_df[X_AXIS_FIELD].max()))
  return (psi_df[X_AXIS_FIELD].min(), psi_df[X_AXIS_FIELD].max())


def generate_plot(cuj_name, psi_df, events_df, should_show_plot, out_file):
  psi_plot = get_psi_plot(cuj_name, psi_df, get_x_range_list(psi_df, events_df))
  events_plot = None

  if events_df is not None:
    events_plot = get_events_plot(cuj_name, events_df, psi_plot.x_range)

  if events_plot:
    plot = column([psi_plot, events_plot], sizing_mode='stretch_both')
  else:
    plot = psi_plot

  save_and_show_plot(plot, out_file, should_show_plot)


def main():
  parser = argparse.ArgumentParser(
      description='Plot PSI csv dump from PSI monitor.',
  )
  parser.add_argument('--psi_csv', action='store', type=str, required=True, dest='psi_csv',
                      help='PSI csv dump from psi_monitor.sh')
  parser.add_argument('--events_csv', action='store', type=str, dest='events_csv',
                      help='Events csv dump from run_cuj.sh')
  parser.add_argument('--cuj_name', action='store', type=str, dest='cuj_name',
                      default='Unknown CUJ', help='Name of the CUJ')
  parser.add_argument('--out_file', action='store', type=str, dest='out_file',
                      default='psi_plot.html', help='Output HTML file')
  parser.add_argument('--show_plot', action='store_true', dest='show_plot',
                      default=False, help='Show the plot')
  args = parser.parse_args()

  print('Plotting PSI csv dump {}'.format(args.psi_csv))
  psi_df = pd.read_csv(args.psi_csv)
  events_df = None
  if args.events_csv:
    print('Plotting PSI events csv dump {}'.format(args.events_csv))
    events_df = pd.read_csv(args.events_csv).drop_duplicates()
    events_df = events_df[events_df['should_plot']]

  generate_plot(args.cuj_name, psi_df, events_df, args.show_plot, args.out_file)

if __name__ == '__main__':
  main()
