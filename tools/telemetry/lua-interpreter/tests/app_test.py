"""
    Test Script for Lua Interpreter Flask server.
"""

import unittest
import app
from bs4 import BeautifulSoup


class TestWebApp(unittest.TestCase):

  def setUp(self):
    """This method will be run before each of the test methods in the class."""
    self.client = app.app.test_client()

  def test_home_page_render(self):
    response = self.client.get('/')
    self.assertIn('Lua Telemetry Interpreter', str(response.data))

  def test_execute_script_output(self):
    response = self.client.post(
        '/execute-script',
        data={
            'script':
                'function factorial(n) ' \
                'if (n == 1) then return n end ' \
                'return n * factorial (n-1) end ' \
                'return factorial(6)',
        })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    self.assertIn('<span>720</span>',
                  str(rendered_html.find(id='script-output')))

  def test_execute_script_error(self):
    response = self.client.post('/execute-script', data={
        'script': 'f',
    })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    span = rendered_html.find(id='script-output').find('span')
    self.assertIn('Error encountered while loading the script.', str(span))


if __name__ == '__main__':
  unittest.main()