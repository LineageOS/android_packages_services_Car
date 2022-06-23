Lua Interpreter
=====

The Lua Interpreter is a web-based IDE tool that can run Lua scripts with the specific Android Auto telemetry callbacks.

Installation
----------
Install the required dependencies to run the local Flask server using [pip in a virtual environment](https://packaging.python.org/en/latest/guides/installing-using-pip-and-virtual-environments/).

Running
----------
Make sure to follow the steps under installation above. Then, run the following command on the command line to start the server:
```
python3 app.py
```
Open the link provided from starting the server to access the tool (the link is accessible from the terminal window).

Testing
----------
Make sure to follow the steps under installation above. To run the tests for the server, make sure to be outside the tool's directory. Run the following command on the command line:
```
python3 -m lua-interpreter.tests.app_test
```
