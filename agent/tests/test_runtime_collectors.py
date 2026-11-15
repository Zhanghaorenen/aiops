import unittest
from types import SimpleNamespace as NS
from unittest.mock import Mock, patch
import psutil
from aiops_agent.collectors.process import ProcessCollector
from aiops_agent.collectors.gpu import GPUInfo


class RuntimeCollectorTests(unittest.TestCase):
    def process(self):
        p=Mock(pid=123)
        p.create_time.return_value=1700000000
        p.cpu_percent.return_value=50
        p.memory_info.return_value=NS(rss=100)
        p.memory_full_info.return_value=NS(uss=60)
        p.uids.return_value=NS(real=1000)
        p.children.return_value=[]
        p.io_counters.return_value=NS(read_bytes=100,write_bytes=50)
        p.ppid.return_value=1
        p.name.return_value='python'
        p.username.return_value='alice'
        return p

    def test_one_sample_reuses_rates_across_cards_and_tree_traversal(self):
        process=self.process()
        with patch('psutil.Process',return_value=process), patch('time.monotonic',side_effect=[10,20]):
            collector=ProcessCollector(); collector.begin_sample()
            first=collector.get_process_info(123,include_tree=False)
            again=collector.get_process_info(123)
            self.assertIsNone(first['cpuPercent'])
            self.assertIsNone(again['cpuPercent'])
            self.assertEqual(process.io_counters.call_count,1)
            collector.end_sample(); collector.begin_sample()
            process.io_counters.return_value=NS(read_bytes=300,write_bytes=150)
            second=collector.get_process_info(123)
            self.assertEqual(second['readBytesPerSecond'],20)
            self.assertEqual(second['writeBytesPerSecond'],10)
            self.assertEqual(second['cpuPercent'],50)
            self.assertEqual(second['linuxUid'],1000)
            self.assertEqual(second['memoryUssBytes'],60)

    def test_gpu_upload_preserves_uid_uss_and_permission_unknowns(self):
        collector=GPUInfo()
        collector.process_collector.get_process_info=Mock(return_value={'linuxUid':1000,'memoryUssBytes':None,'processTreeStatus':'UNAVAILABLE'})
        with patch.object(collector,'_read',return_value=[NS(pid=123,usedGpuMemory=50)]):
            record=collector._processes(None,[])[0]
        self.assertEqual(record['details']['linuxUid'],1000)
        self.assertIsNone(record['details']['memoryUssBytes'])
        self.assertEqual(record['details']['processTreeStatus'],'UNAVAILABLE')
        self.assertNotIn('cmdline',record['details'])

    def test_permission_failure_does_not_fabricate_empty_complete_tree(self):
        process=self.process(); process.children.side_effect=psutil.AccessDenied(123)
        with patch('psutil.Process',return_value=process):
            result=ProcessCollector().get_process_info(123)
        self.assertEqual(result['processTreeStatus'],'UNAVAILABLE')
        self.assertNotIn('processTree',result)

    def test_cached_pid_reuse_is_not_attributed_to_previous_identity(self):
        process=self.process()
        with patch('psutil.Process',return_value=process):
            collector=ProcessCollector(); collector.begin_sample(); collector.get_process_info(123)
            process.create_time.return_value+=1
            self.assertIsNone(collector.get_process_info(123))
