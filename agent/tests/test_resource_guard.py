import json,os,tempfile,threading,time,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock,patch
from aiops_agent.resource_guard import ResourceGuard,ProcessTerminator,validate_policy

def policy(mode='AUTO'):
    return dict(mode=mode,businessUids=[1001],minAvailableMb=1024,minAvailablePercent=5,
                memoryPsiThreshold=10,sustainSeconds=10,cooldownSeconds=60,maxActionsPerHour=3,graceSeconds=5,minRssMb=256)

class GuardTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        with patch('aiops_agent.resource_guard.ProcessTerminator') as factory:
            self.terminator=factory.return_value
            self.guard=ResourceGuard(Mock(), 'fixture-credential',self.temp.name,threading.Event(),True,[1001])
        self.guard.install(policy())
        self.now=time.time();self.target={'pid':404,'startedAt':123.4,'uid':1001,'rssBytes':2**30}
        self.guard.pressure=Mock(return_value={'availableBytes':100*2**20,'totalBytes':32*2**30,'memoryPsiAvg10':30})
        self.terminator.identity.return_value=self.target;self.terminator.terminate.return_value='EXITED_AFTER_TERM'

    def run_pressure(self):
        with patch('aiops_agent.resource_guard.psutil.process_iter',return_value=[Mock()]):
            self.guard.tick(self.now);self.guard.tick(self.now+11)

    def test_sustained_pressure_ends_one_candidate_and_audits(self):
        self.run_pressure();self.terminator.terminate.assert_called_once()
        events=[json.loads(x) for x in (Path(self.temp.name)/'events.jsonl').read_text().splitlines()]
        self.assertEqual([x['status'] for x in events],['PREPARED','EXITED_AFTER_TERM'])
        self.assertNotIn('credential',json.dumps(events))

    def test_short_spike_does_not_terminate(self):
        self.guard.tick(self.now);self.guard.tick(self.now+2);self.terminator.terminate.assert_not_called()

    def test_healthy_ram_cpu_or_swap_is_not_a_trigger(self):
        self.guard.pressure.return_value={'availableBytes':10*2**30,'totalBytes':32*2**30,'memoryPsiAvg10':90}
        self.run_pressure();self.terminator.terminate.assert_not_called()

    def test_missing_psi_requires_critical_memory(self):
        self.guard.pressure.return_value={'availableBytes':512*2**20,'totalBytes':32*2**30,'memoryPsiAvg10':None}
        self.run_pressure();self.terminator.terminate.assert_not_called()
        self.guard.pressure.return_value['availableBytes']=128*2**20
        self.run_pressure();self.terminator.terminate.assert_called_once()

    def test_local_switch_cannot_be_armed_remotely(self):
        self.guard.armed=False;self.run_pressure();self.terminator.terminate.assert_not_called()
        self.assertEqual(self.guard.events[-1]['status'],'WOULD_TERMINATE')

    def test_observation_mode_never_sends_signals(self):
        self.guard.install(policy('OBSERVE'));self.run_pressure();self.terminator.terminate.assert_not_called()

    def test_expired_policy_is_fail_closed(self):
        self.guard.received=self.now-301;self.run_pressure();self.terminator.terminate.assert_not_called()

    def test_remote_uid_cannot_widen_local_scope(self):
        self.guard.install(dict(policy(),businessUids=[2000]));self.run_pressure();self.terminator.terminate.assert_not_called()

    def test_cooldown_and_hourly_circuit(self):
        self.run_pressure()
        with patch('aiops_agent.resource_guard.psutil.process_iter',return_value=[Mock()]):
            self.guard.tick(self.now+20);self.guard.tick(self.now+31)
        self.terminator.terminate.assert_called_once()
        self.guard.attempts=[self.now-200,self.now-150,self.now-100]
        self.guard.tick(self.now+32);self.assertEqual(self.guard.status['state'],'CIRCUIT_OPEN')

    def test_audit_failure_prevents_signal(self):
        self.guard.audit=Mock(side_effect=OSError('disk full'))
        with self.assertRaises(OSError):self.run_pressure()
        self.terminator.terminate.assert_not_called()

    def test_hourly_fuse_survives_restart(self):
        self.run_pressure()
        with patch('aiops_agent.resource_guard.ProcessTerminator'):
            restored=ResourceGuard(Mock(),'fixture',self.temp.name,threading.Event())
        self.assertEqual(len(restored.attempts),1)
        self.assertIsNone(restored.policy)  # new credential must not inherit old cached authority

    def test_policy_restored_only_for_same_credential(self):
        with patch('aiops_agent.resource_guard.ProcessTerminator'):
            restored=ResourceGuard(Mock(),'fixture-credential',self.temp.name,threading.Event())
        self.assertEqual(restored.policy['mode'],'AUTO')

    def test_scan_is_bounded_when_host_has_many_processes(self):
        with patch('aiops_agent.resource_guard.psutil.process_iter',return_value=[Mock()]*5000):
            self.guard.tick(self.now);self.guard.tick(self.now+11)
        self.assertEqual(self.terminator.identity.call_count,4096)
        self.assertTrue(self.guard.status['scanTruncated'])

    def test_schema_rejects_unsafe_or_extra_fields(self):
        for invalid in [dict(policy(),businessUids=[0]),dict(policy(),mode='SHELL'),dict(policy(),command='x'),dict(policy(),cooldownSeconds=0)]:
            with self.assertRaises(ValueError):validate_policy(invalid)

class IdentityTests(unittest.TestCase):
    def fake(self):
        return SimpleNamespace(pid=404,uids=lambda:SimpleNamespace(real=1001,effective=1001,saved=1001),name=lambda:'python',
            cmdline=lambda:['python','training.py'],create_time=lambda:123.4,memory_info=lambda:SimpleNamespace(rss=2**30))
    def terminator(self):
        t=object.__new__(ProcessTerminator);t.business_uids={1001};t.protected_pids={1,100};t.own_groups=('/user.slice/collector.service',);return t
    def test_platform_service_and_system_accounts_excluded(self):
        t=self.terminator();p=self.fake()
        with patch('aiops_agent.resource_guard.cgroup',return_value=('/system.slice/training.service',)):
            with self.assertRaises(ValueError):t.identity(p)
        with patch('aiops_agent.resource_guard.cgroup',return_value=('/user.slice/session.scope',)):
            p.cmdline=lambda:['python','-m','uvicorn']
            with self.assertRaises(ValueError):t.identity(p)
            p.cmdline=lambda:['python','training.py'];p.uids=lambda:SimpleNamespace(real=0,effective=0,saved=0)
            with self.assertRaises(ValueError):t.identity(p)
            p.uids=lambda:SimpleNamespace(real=1001,effective=0,saved=0)
            with self.assertRaises(ValueError):t.identity(p)

    def test_reused_pid_never_receives_signal(self):
        t=self.terminator();t.identity=Mock(return_value={'pid':404,'uid':1001,'startedAt':999,'rssBytes':2**30})
        with patch('aiops_agent.resource_guard.os.pidfd_open',return_value=7,create=True),patch('aiops_agent.resource_guard.os.close'),patch('aiops_agent.resource_guard.signal.pidfd_send_signal',create=True) as send,patch('aiops_agent.resource_guard.psutil.Process'):
            result=t.terminate({'pid':404,'uid':1001,'startedAt':123},1,lambda:True)
        self.assertEqual(result,'IDENTITY_CHANGED');send.assert_not_called()

    def test_recovery_before_signal_cancels_action(self):
        t=self.terminator();target={'pid':404,'uid':1001,'startedAt':123};t.identity=Mock(return_value=target)
        with patch('aiops_agent.resource_guard.os.pidfd_open',return_value=7,create=True),patch('aiops_agent.resource_guard.os.close'),patch('aiops_agent.resource_guard.signal.pidfd_send_signal',create=True) as send,patch('aiops_agent.resource_guard.psutil.Process'):
            self.assertEqual(t.terminate(target,1,lambda:False),'CANCELLED_NO_SIGNAL')
        send.assert_not_called()

    def test_recovery_after_term_prevents_kill(self):
        t=self.terminator();target={'pid':404,'uid':1001,'startedAt':123};t.identity=Mock(return_value=target)
        checks=iter([True,False])
        with patch('aiops_agent.resource_guard.os.pidfd_open',return_value=7,create=True),patch('aiops_agent.resource_guard.os.close'),patch('aiops_agent.resource_guard.signal.pidfd_send_signal',create=True) as send,patch('aiops_agent.resource_guard.psutil.Process'),patch('select.select',return_value=([],[],[])):
            self.assertEqual(t.terminate(target,1,lambda:next(checks)),'TERM_SENT_NO_ESCALATION')
        self.assertEqual(send.call_count,1)

    def test_unresponsive_process_escalates_on_same_pidfd_only(self):
        t=self.terminator();target={'pid':404,'uid':1001,'startedAt':123};t.identity=Mock(return_value=target)
        with patch('aiops_agent.resource_guard.os.pidfd_open',return_value=7,create=True),patch('aiops_agent.resource_guard.os.close'),patch('aiops_agent.resource_guard.signal.pidfd_send_signal',create=True) as send,patch('aiops_agent.resource_guard.signal.SIGKILL',9,create=True),patch('aiops_agent.resource_guard.psutil.Process'),patch('select.select',side_effect=[([],[],[]),([7],[],[])]):
            self.assertEqual(t.terminate(target,1,lambda:True),'EXITED_AFTER_KILL')
        self.assertEqual(send.call_count,2)
        self.assertEqual([c.args[0] for c in send.call_args_list],[7,7])

if __name__=='__main__':unittest.main()
