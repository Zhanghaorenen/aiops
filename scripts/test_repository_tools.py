"""开发配置读取不应执行 Shell、泄露值或默默接受损坏配置。"""
from pathlib import Path
import tempfile
import unittest
from run import load_env


class EnvironmentFileTests(unittest.TestCase):
    def parse(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text(text, encoding="utf-8")
            return load_env(path)

    def test_quoted_unicode_and_equals_are_preserved(self):
        result = self.parse('# comment\nAIOPS_TEST_VALUE="中文=a&b#c"\n')
        self.assertEqual(result["AIOPS_TEST_VALUE"], "中文=a&b#c")

    def test_expressions_stay_literal(self):
        result = self.parse("AIOPS_TEST_VALUE=$(not-a-real-command)\n")
        self.assertEqual(result["AIOPS_TEST_VALUE"], "$(not-a-real-command)")

    def test_missing_file_does_not_create_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            self.assertEqual(load_env(path), {})
            self.assertFalse(path.exists())

    def test_invalid_key_and_incomplete_quotes_report_no_value(self):
        for text in ["PATH=private-value", 'AIOPS_TEST_VALUE="private-value']:
            with self.subTest(text=text):
                with self.assertRaises(ValueError) as raised:
                    self.parse(text)
                self.assertNotIn("private-value", str(raised.exception))
