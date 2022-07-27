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
                'function test(data,state) ' \
                'tbl = {}; tbl[\'sessionId\'] = state.data + data.id;' \
                'on_metrics_report(tbl) end',
            'function-name': 'test',
            'published-data': '{"id": 2}',
            'saved-state': '{"data": 5}'
        })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    self.assertIn('{<br/>  "sessionId": 7<br/>}',
                  str(rendered_html.find(id='script-output')))

  def test_execute_script_loading_error(self):
    response = self.client.post(
        '/execute-script',
        data={
            'script': 'function test(data, state) x == 1 end',
            'function-name': 'test',
            'published-data': "{}",
            'saved-state': "{}"
        })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    span = rendered_html.find(id='script-output').find('span')
    self.assertIn(
        'Error encountered while loading the script. A possible cause could ' \
        'be syntax errors in the script.',
        str(span))

  def test_execute_script_running_error(self):
    response = self.client.post(
        '/execute-script',
        data={
            'script': 'function test(data, state) call() end',
            'function-name': 'test',
            'published-data': "{}",
            'saved-state': "{}"
        })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    span = rendered_html.find(id='script-output').find('span')
    self.assertIn('Error encountered while running the script.', str(span))

  def test_execute_script_faulty_published_data(self):
    response = self.client.post('/execute-script',
                                data={
                                    'script': 'function test(data, state) end',
                                    'function-name': 'test',
                                    'published-data': "",
                                    'saved-state': "{}"
                                })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    span = rendered_html.find(id='script-output').find('span')
    self.assertIn('Error from parsing published data', str(span))

  def test_execute_script_faulty_saved_state(self):
    response = self.client.post('/execute-script',
                                data={
                                    'script': 'function test(data, state) end',
                                    'function-name': 'test',
                                    'published-data': "{}",
                                    'saved-state': "f"
                                })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    span = rendered_html.find(id='script-output').find('span')
    self.assertIn('Error from parsing saved state', str(span))

  def test_execute_script_wrong_function(self):
    response = self.client.post('/execute-script',
                                data={
                                    'script': 'function test(data, state) end',
                                    'function-name': 'tes',
                                    'published-data': "{}",
                                    'saved-state': "{}"
                                })
    rendered_html = BeautifulSoup(response.data.decode('UTF-8'), 'html.parser')
    span = rendered_html.find(id='script-output').find('span')
    self.assertIn('Wrong function name.', str(span))

  def test_prettify_json_success(self):
    self.assertEqual('{\n  "test": 2\n}', app.prettify_json('{"test":2}'))

  def test_prettify_json_failure(self):
    self.assertEqual('not_a_json', 'not_a_json')


if __name__ == '__main__':
  unittest.main()