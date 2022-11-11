# CarWatchdog's Performance Analysis Tools

## Performance Stats Parser
The `parser/` directory contains the scripts to parse CarWatchdog daemon's dumps
and generate a protobuf binary with the performance stats. The proto format is
available in `parser/performancestats.proto`.

### Generate protobuf binary
To generate the proto binary for the CarWatchdog performance stats, run:
```bash
export PARSER_PATH=$TOP/packages/services/Car/tools/watchdog/parser
python $PARSER_PATH/perf_stats_parser.py -f <file-with-dump>.txt -o ~/perf_stats_out.pb
```

The proto binary will be saved to `~/perf_stats_out.pb`.

### Read the protobuf binary
To read the protobuf binary from a python file, one could use:

```python
from perf_stats_parser import read_pb
```

The `read_pb` method takes the protobuf binary filename and returns a
`PerformanceStats` class instance with the same perf stats stored in the
protobuf.

### Generate `performancestats_pb2.py`
If changes are made to `parser/performancestats.proto`, the
`parser/performancestats_pb2.py` file needs to be regenerated. This can be done
by running:
```bash
aprotoc --proto_path=$PARSER_PATH --python_out=$PARSER_PATH $PARSER_PATH/performancestats.proto
```