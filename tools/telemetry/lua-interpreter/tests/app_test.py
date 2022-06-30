"""
    Test Script for Lua Interpreter Flask server.
"""

import unittest
from ..app import app


class TestWebApp(unittest.TestCase):

    def setUp(self):
        """This method will be run before each of the test methods in the class."""
        self.client = app.test_client()

    def test_home_page_render(self):
        response = self.client.get('/')
        self.assertIn("Lua Telemetry Interpreter", str(response.data))


if __name__ == '__main__':
    unittest.main()
