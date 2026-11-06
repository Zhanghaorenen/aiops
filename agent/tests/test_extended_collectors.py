import unittest
from types import SimpleNamespace as NS
from unittest.mock import Mock, patch
import psutil
from aiops_agent.collectors.system import SystemCollector
from aiops_agent.collectors.process import ProcessCollector


class ExtendedCollectorTests(unittest.TestCase):
    def test_temperatures_keep_cpu_storage_and_board_separate(self):
        readings = {'coretemp': [NS(label='Package id 0', current=62.5, high=85, critical=100)],
                    'nvme': [NS(label='Composite', current=44, high=None, critical=None)],
                    'acpitz': [NS(label='', current=-5, high=None, critical=None)]}
        with patch.object(psutil, 'sensors_temperatures', return_value=readings, create=True) as read:
            status, rows = SystemCollector.collect_temperatures()
        self.assertEqual(status, 'AVAILABLE')
        self.assertEqual([r['category'] for r in rows], ['CPU', 'STORAGE', 'BOARD'])
        self.assertEqual(rows[0]['currentCelsius'], 62.5)
        self.assertEqual(rows[2]['currentCelsius'], -5)
        read.assert_called_once_with(fahrenheit=False)

    def test_temperatures_unsupported_and_permission_denied_are_distinct(self):
        with patch.object(psutil, 'sensors_temperatures', None, create=True):
            self.assertEqual(SystemCollector.collect_temperatures(), ('UNSUPPORTED', []))
        with patch.object(psutil, 'sensors_temperatures', return_value={}, create=True):
            self.assertEqual(SystemCollector.collect_temperatures(), ('UNSUPPORTED', []))
        with patch.object(psutil, 'sensors_temperatures', side_effect=PermissionError(), create=True):
            self.assertEqual(SystemCollector.collect_temperatures(), ('UNAVAILABLE', []))

    def test_invalid_temperature_is_not_reported_as_zero(self):
        readings = {'coretemp': [NS(label='CPU', current=float('nan'), high=None, critical=None),
                                 NS(label='CPU', current=999, high=None, critical=None)]}
        with patch.object(psutil, 'sensors_temperatures', return_value=readings, create=True):
            self.assertEqual(SystemCollector.collect_temperatures(), ('UNAVAILABLE', []))

    def test_network_rate_warmup_counter_reset_and_partition_permissions(self):
        memory=NS(total=1000,used=200,available=700,cached=100)
        disk=NS(total=1000,used=100,free=900,percent=10)
        network=[{'eth0':NS(bytes_recv=100,bytes_sent=200)}, {'eth0':NS(bytes_recv=1100,bytes_sent=2200)}, {'eth0':NS(bytes_recv=1,bytes_sent=2)}]
        with patch('psutil.virtual_memory',return_value=memory), patch('psutil.swap_memory',return_value=NS(total=100,used=5,percent=5)), patch('psutil.cpu_percent',return_value=[10,30]), patch('psutil.net_io_counters',side_effect=network), patch('psutil.disk_io_counters',return_value={}), patch('psutil.disk_partitions',return_value=[NS(device='/dev/sda',mountpoint='/',fstype='ext4'),NS(device='/dev/sdb',mountpoint='/private',fstype='ext4')]), patch('psutil.disk_usage',side_effect=lambda path: (_ for _ in ()).throw(PermissionError()) if path=='/private' else disk), patch('time.monotonic',side_effect=[100,110,120]):
            collector=SystemCollector('/')
            first,second,third=[collector.collect_system_info() for _ in range(3)]
        self.assertIsNone(first['details']['network'][0]['receiveBytesPerSecond'])
        self.assertEqual(second['details']['network'][0]['receiveBytesPerSecond'],100)
        self.assertIsNone(third['details']['network'][0]['receiveBytesPerSecond'])
        self.assertEqual(first['cpu']['percent'],20)
        self.assertEqual(len(first['details']['disks']),1)
        self.assertEqual(first['details']['memoryAvailableBytes'],700)

    def test_process_permissions_are_partial_and_cpu_needs_baseline(self):
        process=Mock(pid=123)
        process.children.return_value=[]
        process.ppid.return_value=1
        process.io_counters.side_effect=psutil.AccessDenied(123)
        process.create_time.return_value=1700000000
        process.name.return_value='python'
        process.username.return_value='alice'
        process.exe.side_effect=psutil.AccessDenied(123)
        process.cwd.return_value='/work/train'
        process.memory_info.return_value=NS(rss=1000)
        process.cpu_percent.return_value=120
        process.num_threads.return_value=4
        process.status.return_value='running'
        with patch('psutil.Process',return_value=process):
            collector=ProcessCollector();first=collector.get_process_info(123);second=collector.get_process_info(123)
        self.assertIsNone(first['cpuPercent'])
        self.assertEqual(second['cpuPercent'],120)
        self.assertIsNone(first['executablePath'])
        self.assertEqual(first['workingDirectory'],'/work/train')
        self.assertNotIn('cmdline',first)
        process.cmdline.assert_not_called();process.environ.assert_not_called()

    def test_exited_and_zombie_processes_do_not_abort_sampling(self):
        for error in (psutil.NoSuchProcess(1),psutil.ZombieProcess(1),psutil.AccessDenied(1)):
            with patch('psutil.Process',side_effect=error):
                self.assertIsNone(ProcessCollector().get_process_info(1))

    def test_no_gpu_keeps_no_gpu_status(self):
        from aiops_agent.collectors.gpu import GPUInfo
        nvml=NS(nvmlInit=Mock(),nvmlDeviceGetCount=Mock(return_value=0),nvmlShutdown=Mock(),NVMLError=RuntimeError)
        with patch('aiops_agent.collectors.gpu.pynvml',nvml):
            collector=GPUInfo()
            self.assertEqual(collector.get_gpu_info(),[])
            self.assertEqual(collector.collection_status,'NO_GPU')
            nvml.nvmlShutdown.assert_called_once()


if __name__=='__main__':unittest.main()
