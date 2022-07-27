"""
    Flask server for hosting Lua Interpreter tool.
"""

import ctypes
import json
import flask

app = flask.Flask(__name__)
lua_lib = ctypes.cdll.LoadLibrary('./liblua_engine.so')
lua_lib.NewLuaEngine.argtypes = None
lua_lib.NewLuaEngine.restype = ctypes.c_void_p
lua_lib.ExecuteScript.argtypes = [
    ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
    ctypes.c_char_p
]
lua_lib.ExecuteScript.restype = ctypes.c_void_p
engine = lua_lib.NewLuaEngine()


class LuaOutput(ctypes.Structure):
  """Python wrapper class around LuaOutput struct as defined in lua_engine.h.
  """
  _fields_ = [('output', ctypes.POINTER(ctypes.c_char_p)),
              ('size', ctypes.c_int)]


@app.route('/')
def index():
  """Renders the main page of the tool.

  Returns:
    A string of the rendered index.html page.
  """
  return flask.render_template('index.html')


@app.route('/execute-script', methods=['POST'])
def execute_script():
  """Executes the Lua script from the request
  re-rendering the home page with the output.

  Returns:
    A string of the rendered index.html page with output, script, published data,
    and the saved state specified.
  """
  script = flask.request.form['script']
  function_name = flask.request.form['function-name']
  published_data = flask.request.form['published-data']
  saved_state = flask.request.form['saved-state']
  # ctypes requires that strings are encoded to bytes
  lua_output = LuaOutput.from_address(
      lua_lib.ExecuteScript(engine, script.encode(), function_name.encode(),
                            published_data.encode(), saved_state.encode()))

  # ctypes encodes strings as bytes so they must be decoded back to strings.
  decoded_output = []
  for i in range(lua_output.size):
    decoded_output.append(prettify_json(lua_output.output[i].decode()))

  lua_lib.FreeLuaOutput(ctypes.byref(lua_output))

  return flask.render_template('index.html',
                               script=script,
                               output=decoded_output,
                               function_name=function_name,
                               published_data=published_data,
                               saved_state=saved_state)


def prettify_json(string):
  """Prettifies the string if it represents a JSON with an indent of 2.

  Args:
    string (str): String to prettify

  Returns:
    A string of the formatted JSON. If the string does not represent a
    JSON, the string is returned back with no changes.
  """
  try:
    # If the string cannot be loaded into a JSON,
    # json.loads throws a JSONDecodeError exception.
    json_object = json.loads(string)
    json_formatted_str = json.dumps(json_object, indent=2)
    return json_formatted_str
  except json.JSONDecodeError as e:
    return string


if __name__ == '__main__':
  app.run()