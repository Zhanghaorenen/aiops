import unittest
import json
from unittest.mock import patch, MagicMock
from aiops_agent.collectors.system import SystemCollector
from aiops_agent.training_sdk import TrainingMonitor
from aiops_agent.collectors.process import ProcessCollector
import psutil


class TrainingMetricsTests(unittest.TestCase):
    def test_pid_reuse_and_exit_keep_original_start_identity(self):
        collector=ProcessCollector()
        collector._processes[(123,1700000000)]=MagicMock()
        reused=MagicMock();reused.create_time.return_value=1700000010
        with patch('psutil.Process',return_value=reused):
            events=collector.collect_exits()
        self.assertEqual(events[0]['pid'],123)
        self.assertEqual(events[0]['status'],'EXITED_UNKNOWN')
        self.assertNotIn((123,1700000000),collector._processes)

    def test_permission_failure_does_not_imply_exit(self):
        collector=ProcessCollector();collector._processes[(123,1700000000)]=MagicMock()
        with patch('psutil.Process',side_effect=psutil.AccessDenied(123)):
            self.assertEqual(collector.collect_exits(),[])

    def test_native_pressure_and_oom_are_parsed_without_shell(self):
        def read(path, *args, **kwargs):
            return 'oom_kill 7\n' if str(path).endswith('vmstat') else 'some avg10=12.5 avg60=4.2 avg300=2 total=99\n'
        with patch('pathlib.Path.read_text', read), patch('psutil.disk_usage', side_effect=PermissionError()):
            values = SystemCollector.collect_pressure()
        self.assertEqual(values['memoryPsiAvg10'], 12.5)
        self.assertEqual(values['oomKillCount'], 7)
        self.assertNotIn('shmPercent', values)

    def test_unavailable_kernel_interfaces_do_not_fabricate_zeros(self):
        with patch('pathlib.Path.read_text', side_effect=OSError()), patch('psutil.disk_usage', side_effect=OSError()):
            self.assertEqual(SystemCollector.collect_pressure(), {})

    def test_optional_sdk_only_reports_named_fields_to_authenticated_endpoint(self):
        with patch('aiops_agent.training_sdk.urlopen') as send:
            send.return_value.__enter__.return_value.read.return_value=b''
            monitor=TrainingMonitor('http://platform.test','test-only',2,3)
            monitor.report(epoch=2, step=30, step_time=.5, samples_per_second=64)
            request=send.call_args.args[0]
            self.assertTrue(request.full_url.endswith('/api/servers/2/training/jobs/3/progress'))
            self.assertEqual(json.loads(request.data)['stepTime'], .5)
            monitor.finish(False)
            self.assertEqual(json.loads(send.call_args.args[0].data), {'state':'FAILED'})
