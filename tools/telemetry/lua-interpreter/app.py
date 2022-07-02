"""
    Flask server for hosting Lua Interpreter tool.
"""

import ctypes
import flask

app = flask.Flask(__name__)
lua_lib = ctypes.cdll.LoadLibrary('./liblua_engine.so')
lua_lib.NewLuaEngine.argtypes = None
lua_lib.NewLuaEngine.restype = ctypes.c_void_p
lua_lib.ExecuteScript.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
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
    A string of the rendered index.html page with output and script.
    specified.
  """
  script = flask.request.form['script']
  lua_output = LuaOutput.from_address(
      lua_lib.ExecuteScript(engine, script.encode('UTF-8')))

  # ctypes encodes strings as bytes so they must be decoded back to strings.
  decoded_output = []
  for i in range(lua_output.size):
    decoded_output.append(lua_output.output[i].decode('UTF-8'))

  lua_lib.FreeLuaOutput(ctypes.byref(lua_output))

  return flask.render_template('index.html',
                               script=script,
                               output=decoded_output)


if __name__ == '__main__':
  app.run()