#!/usr/bin/env python

import argparse
import numpy as np
import pandas as pd

from bokeh.layouts import column
from bokeh.models import ColumnDataSource
from bokeh.models import CustomJSHover
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
MILLIS_TO_SECONDS = CustomJSHover(code="""
  if (value < 1000)
    return value + 'ms'
  return (value / 1000) +'s';""")

def add_events_to_plot(plot, events_df, vspan_opts, fixed_y_values=0, color_list=None) -> figure:
  vspan_source = dict(x_values=[], color=[], y_values=[], desc=[])
  for index, row in events_df.iterrows():
    vspan_source['x_values'].append(row[X_AXIS_FIELD])
    vspan_source['y_values'].append(fixed_y_values)
    vspan_source['desc'].append(row['event_description'])
    if 'color' in row:
      vspan_source['color'].append(row.color)
    elif color_list:
      vspan_source['color'].append(color_list[index % len(color_list)])
    else:
      vspan_source['color'].append('black')
    line_width = 4 if 'is_high_latency_event' in row and row['is_high_latency_event'] else 2

  glyph = VSpan(x='x_values', line_width=line_width, line_dash='dashed', line_color='color')
  plot.add_glyph(ColumnDataSource(data=vspan_source), glyph)
  plot.add_layout(LabelSet(**vspan_opts, source=ColumnDataSource(data=vspan_source)))
  return plot


# Bokeh cannot show tooltip for multi line plot. The workaround for this is to plot one scatter plot
# per plot in a multiline plot and add the tooltips for these scatter plots. Then overlay
# the scatter plots on the multi line plot.
def plot_tooltips(x_values, multi_line_plot_y_values, multi_line_plot_desc, plot) -> figure:
  for index, line_plot_y_values in enumerate(multi_line_plot_y_values):
    source = ColumnDataSource({'X': x_values, 'Y': line_plot_y_values,
      'Description': [multi_line_plot_desc[index]] * x_values.size})
    r = plot.scatter('X', 'Y', source = source, fill_alpha=0, line_alpha=0.3, line_color="grey")
    hover = HoverTool(tooltips=[("X", "@X{custom}"), ("PSI", "@Y"), ("Desc", "@Description")],
      formatters={'@X': MILLIS_TO_SECONDS}, renderers=[r])
    plot.add_tools(hover)
  return plot


def get_psi_plot(cuj_name, psi_df, x_range_list) -> figure:
  if psi_df.empty:
    return None

  plot = figure(title="PSI metrics during CUJ '" + cuj_name + "'", width=PLOT_WIDTH,
                height=PLOT_HEIGHT, x_axis_label=X_AXIS_LABEL,
                y_axis_label='PSI %', x_range=x_range_list, y_range=[0, 100],
                x_axis_type='datetime')

  psi_source_dict = dict(
      x_values=[],
      y_values=[psi_df.memory_full_avg10.values, psi_df.io_full_avg10.values,
           psi_df.cpu_some_avg10.values, psi_df.memory_some_avg10.values,
           psi_df.io_some_avg10.values],
      color=['red', 'blue', 'sandybrown', 'yellow', 'purple'],
      desc=['Memory full avg10', 'IO full avg10', 'CPU some avg10', 'Memory some avg10',
                    'IO some avg10'])
  if 'cpu_full_avg10' in psi_df.columns:
    psi_source_dict['y_values'].append(psi_df.cpu_full_avg10.values)
    psi_source_dict['color'].append('black')
    psi_source_dict['desc'].append('CPU full avg10')

  if 'irq_full_avg10' in psi_df.columns:
    psi_source_dict['y_values'].append(psi_df.irq_full_avg10.values)
    psi_source_dict['color'].append('pink')
    psi_source_dict['desc'].append('IRQ full avg10')

  x_values = pd.to_timedelta(pd.Series(psi_df[X_AXIS_FIELD].values), unit='ms')
  psi_source_dict['x_values'] = [x_values for _ in psi_source_dict['y_values']]

  source=ColumnDataSource(data=psi_source_dict)
  plot.multi_line(xs='x_values', ys='y_values', source=source,
                  color='color', legend_field='desc', line_width=3)
  plot = plot_tooltips(x_values, psi_source_dict['y_values'], psi_source_dict['desc'], plot)

  vspan_opts = dict(x='x_values', y='y_values', text='desc', x_units='data', y_units='data',
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
                y_range=[0, 100], x_axis_type='datetime')
  hover = HoverTool(tooltips=[("X", "@x_values{custom}"), ("Event", "@desc")],
    formatters={'@x_values': MILLIS_TO_SECONDS})
  plot.add_tools(hover)

  vspan_opts = dict(x='x_values', y='y_values', x_units='data', y_units='data')

  plot = add_events_to_plot(plot, events_df, vspan_opts)

  return plot


def save_and_show_plot(plot, should_show_plot, out_file):
  output_file(out_file)
  save(plot)
  if should_show_plot:
    show(plot)


def get_x_range_list(psi_df, events_df) -> Tuple[int, int]:
  if events_df is not None and not events_df.empty:
    return (min(events_df[X_AXIS_FIELD].min(), psi_df[X_AXIS_FIELD].min()),
            max(events_df[X_AXIS_FIELD].max(), psi_df[X_AXIS_FIELD].max()))
  return (psi_df[X_AXIS_FIELD].min(), psi_df[X_AXIS_FIELD].max())


def generate_plot(cuj_name, psi_df, events_df, should_show_plot, out_file):
  # psi_df has event_descriptions (which is a list of all events happened on a polling event)
  # while events_df has event_description (which is a single event). To keep the dfs consistent,
  # concatenate all events from event_descriptions in psi_df as a single event string.
  psi_df['event_description'] = psi_df['event_descriptions'].apply(
    lambda x: ','.join(x) if len(x) > 0 else np.nan)
  psi_plot = get_psi_plot(cuj_name, psi_df, get_x_range_list(psi_df, events_df))
  events_plot = None

  if events_df is not None:
    events_plot = get_events_plot(cuj_name, events_df, psi_plot.x_range)

  if events_plot:
    plot = column([psi_plot, events_plot], sizing_mode='stretch_both')
  else:
    plot = psi_plot

  save_and_show_plot(plot, should_show_plot, out_file)


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
  psi_df = pd.read_csv(args.psi_csv, converters={'event_descriptions': pd.eval})
  events_df = None
  if args.events_csv:
    print('Plotting PSI events csv dump {}'.format(args.events_csv))
    events_df = pd.read_csv(args.events_csv).drop_duplicates()
    events_df = events_df[events_df['should_plot']]

  generate_plot(args.cuj_name, psi_df, events_df, args.show_plot, args.out_file)

if __name__ == '__main__':
  main()
