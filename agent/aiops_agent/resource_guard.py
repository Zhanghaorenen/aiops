"""Local, opt-in memory-pressure protection. Never executes shell commands.

The network worker cannot block the local sampling loop. A remote policy can only
narrow the locally configured business UID boundary; it cannot arm this host.
"""
from __future__ import annotations
import collections
import hashlib
import json
import math
import os
from pathlib import Path
import signal
import threading
import time
import uuid
import psutil

CRITICAL_NAMES = frozenset({'systemd','init','sshd','sshd-session','sshd-auth','sudo','su',
    'dbus-daemon','tailscaled','mysqld','mariadbd','postgres','qdrant','dockerd','containerd',
    'kubelet','kube-apiserver','nginx','redis-server','java','node','uvicorn','gunicorn'})
PLATFORM_MARKERS = ('aiops_agent','aiops-agent','uvicorn','gunicorn','daphne','celery','manage.py')

def validate_policy(value):
    if not isinstance(value,dict):raise ValueError('invalid policy')
    keys={'mode','businessUids','minAvailableMb','minAvailablePercent','memoryPsiThreshold',
          'sustainSeconds','cooldownSeconds','maxActionsPerHour','graceSeconds','minRssMb'}
    if set(value)!=keys or value['mode'] not in {'DISABLED','OBSERVE','AUTO'}:raise ValueError('invalid policy fields')
    uids=value['businessUids']
    if not isinstance(uids,list) or len(uids)>32 or any(type(u) is not int or u<1000 for u in uids):raise ValueError('business UIDs must be >=1000')
    bounds={'minAvailableMb':(256,65536),'minAvailablePercent':(1,15),'memoryPsiThreshold':(1,100),
            'sustainSeconds':(4,300),'cooldownSeconds':(30,3600),'maxActionsPerHour':(1,10),
            'graceSeconds':(1,30),'minRssMb':(128,65536)}
    for k,(lo,hi) in bounds.items():
        if type(value[k]) is not int or not lo<=value[k]<=hi:raise ValueError('invalid '+k)
    return dict(value,businessUids=sorted(set(uids)))

def cgroup(pid):
    # Unknown service membership must fail closed, never guess it is a business process.
    lines=Path(f'/proc/{pid}/cgroup').read_text().splitlines()
    if not lines:raise ValueError('cgroup unavailable')
    return tuple(line.split(':',2)[2] for line in lines)

class ProcessTerminator:
    def __init__(self, business_uids, protected_pids=()):
        self.business_uids=frozenset(business_uids)
        me=psutil.Process()
        self.protected_pids={1,me.pid,*protected_pids,*[p.pid for p in me.parents()]}
        self.own_groups=cgroup(me.pid) if os.name=='posix' and Path('/proc').exists() else ()

    def identity(self,process):
        uids=process.uids();uid=uids.real
        if uid!=uids.effective or uid!=uids.saved:raise ValueError('privileged_identity')
        groups=cgroup(process.pid)
        if (uid not in self.business_uids or uid<1000 or process.pid in self.protected_pids
                or process.name().lower() in CRITICAL_NAMES):raise ValueError('protected_process')
        # Used locally only; command lines are never exported to audit or the LLM.
        if any(marker in arg.lower() for arg in process.cmdline() for marker in PLATFORM_MARKERS):
            raise ValueError('protected_platform')
        # All system service units and any process sharing the collector's unit are protected.
        if any('/system.slice/' in g or g.rstrip('/').endswith('.service') for g in groups):raise ValueError('protected_service')
        if self.own_groups and any(g in self.own_groups and g!='/' for g in groups):raise ValueError('collector_service')
        return {'pid':process.pid,'startedAt':process.create_time(),'uid':uid,'rssBytes':process.memory_info().rss}

    def terminate(self,target,grace,still_emergency):
        if not hasattr(os,'pidfd_open') or not hasattr(signal,'pidfd_send_signal'):
            return 'UNSUPPORTED_PIDFD'
        fd=os.pidfd_open(target['pid'])
        try:
            process=psutil.Process(target['pid'])
            actual=self.identity(process)
            if actual['startedAt']!=target['startedAt'] or actual['uid']!=target['uid']:
                return 'IDENTITY_CHANGED'
            # pidfd is opened before checking identity: PID reuse cannot redirect either signal.
            if not still_emergency():return 'CANCELLED_NO_SIGNAL'
            signal.pidfd_send_signal(fd,signal.SIGTERM)
            import select
            if select.select([fd],[],[],grace)[0]:return 'EXITED_AFTER_TERM'
            # Recheck protection and resource pressure before escalating to SIGKILL.
            self.identity(process)
            if not still_emergency():return 'TERM_SENT_NO_ESCALATION'
            signal.pidfd_send_signal(fd,signal.SIGKILL)
            return 'EXITED_AFTER_KILL' if select.select([fd],[],[],2)[0] else 'KILL_SENT_UNCONFIRMED'
        finally:os.close(fd)

class ResourceGuard:
    def __init__(self,client,credential,state_dir,stop,armed=False,business_uids=(),protected_pids=()):
        self.client,self.credential,self.stop=client,credential,stop
        self.binding=hashlib.sha256(credential.encode()).hexdigest()
        self.directory=Path(state_dir);self.directory.mkdir(parents=True,exist_ok=True)
        self.armed=armed;self.local_uids=frozenset(business_uids)
        self.terminator=ProcessTerminator(self.local_uids,protected_pids)
        self.lock=threading.Lock();self.events=collections.deque(maxlen=200)
        self.policy=None;self.received=0.;self.low_since=None;self.last_observation=0.
        self.status={'armed':armed,'localBusinessUids':sorted(self.local_uids),'state':'WAITING_POLICY'}
        self.attempts=[]
        for name in ['events.previous.jsonl','events.jsonl']:
            try:
                for line in (self.directory/name).read_text().splitlines():
                    event=json.loads(line)
                    if event['status']=='PREPARED':self.attempts.append(event['at'])
                    if event.get('binding')==self.binding:
                        self.events.append({k:v for k,v in event.items() if k!='binding'})
            except FileNotFoundError:pass
        try:
            saved=json.loads((self.directory/'policy.json').read_text())
            if saved.get('binding')==self.binding:self.install(saved['policy'],saved['received'])
        except (OSError,ValueError,KeyError):pass

    def install(self,policy,received=None):
        policy=validate_policy(policy);received=time.time() if received is None else received
        temp=self.directory/'policy.tmp'
        temp.write_text(json.dumps({'policy':policy,'received':received,'binding':self.binding}));temp.chmod(0o600);temp.replace(self.directory/'policy.json')
        with self.lock:self.policy=policy;self.received=received

    def audit(self,status,details):
        event={'id':str(uuid.uuid4()),'at':time.time(),'status':status,'details':details}
        path=self.directory/'events.jsonl'
        # If durable audit fails, PREPARED cannot complete and no signal is sent.
        if path.exists() and path.stat().st_size>1024*1024:path.replace(self.directory/'events.previous.jsonl')
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
        try:
            with os.fdopen(fd,'w') as f:f.write(json.dumps({**event,'binding':self.binding})+'\n');f.flush();os.fsync(f.fileno())
        finally:pass
        with self.lock:self.events.append(event)
        if status=='PREPARED':self.attempts.append(event['at'])

    @staticmethod
    def pressure():
        memory=psutil.virtual_memory();psi=None
        try:
            line=next(s for s in Path('/proc/pressure/memory').read_text().splitlines() if s.startswith('some '))
            psi=float(dict(s.split('=') for s in line.split()[1:])['avg10'])
        except (OSError,ValueError,KeyError,StopIteration):pass
        return {'availableBytes':memory.available,'totalBytes':memory.total,'memoryPsiAvg10':psi}

    @staticmethod
    def severe(sample,policy):
        available,total,psi=sample['availableBytes'],sample['totalBytes'],sample['memoryPsiAvg10']
        if total<=0 or available<0 or available>total:return False
        low=available<max(policy['minAvailableMb']*2**20,total*policy['minAvailablePercent']/100)
        critical=available<max(256*2**20,total*.005)
        return low and (critical or psi is not None and math.isfinite(psi) and psi>=policy['memoryPsiThreshold'])

    def tick(self,now=None):
        now=time.time() if now is None else now
        with self.lock:policy=self.policy;age=now-self.received
        if policy is None or not 0<=age<=300 or policy['mode']=='DISABLED':
            self.low_since=None;self.status.update(state='DISABLED_OR_POLICY_EXPIRED');return
        sample=self.pressure();self.status.update(sample=sample,state='MONITORING',lastEvaluatedAt=now)
        if not self.severe(sample,policy):self.low_since=None;return
        self.low_since=now if self.low_since is None else self.low_since
        if now-self.low_since<policy['sustainSeconds']:return
        self.attempts=[t for t in self.attempts if now-t<3600]
        if self.attempts and now-self.attempts[-1]<policy['cooldownSeconds']:
            self.status.update(state='COOLDOWN');return
        if len(self.attempts)>=policy['maxActionsPerHour']:
            self.status.update(state='CIRCUIT_OPEN');return
        if now-self.last_observation<30:return
        self.last_observation=now
        allowed=self.local_uids.intersection(policy['businessUids'])
        candidate=None
        self.status['scanTruncated']=False
        for index,process in enumerate(psutil.process_iter()):
            if index>=4096:
                self.status['scanTruncated']=True;break
            try:
                target=self.terminator.identity(process)
                if (target['uid'] in allowed and target['rssBytes']>=policy['minRssMb']*2**20
                        and (candidate is None or target['rssBytes']>candidate['rssBytes'])):candidate=target
            except (psutil.Error,OSError,ValueError):continue
        if candidate is None:
            self.audit('NO_ELIGIBLE_PROCESS',{'sample':sample});self.status.update(state='NO_ELIGIBLE_PROCESS');return
        target=candidate
        if not self.armed or policy['mode']!='AUTO':
            self.audit('WOULD_TERMINATE',{'target':target,'sample':sample});self.status.update(state='OBSERVING');return
        self.audit('PREPARED',{'target':target,'sample':sample})
        try:
            result=self.terminator.terminate(target,policy['graceSeconds'],lambda:self.authorized_and_severe(target))
        except (psutil.Error,OSError,ValueError) as error:result='FAILED_'+type(error).__name__
        self.audit(result,{'target':target,'sampleAfter':self.pressure()});self.low_since=None
        self.status.update(state=result)

    def authorized_and_severe(self,target):
        with self.lock:p=self.policy;age=time.time()-self.received
        process=psutil.Process(target['pid'])
        return bool(not self.stop.is_set() and self.armed and p and p['mode']=='AUTO' and 0<=age<=300
                    and target['uid'] in self.local_uids.intersection(p['businessUids'])
                    and process.create_time()==target['startedAt']
                    and process.memory_info().rss>=p['minRssMb']*2**20
                    and self.severe(self.pressure(),p))

    def synchronize(self):
        while not self.stop.is_set():
            try:
                with self.lock:events=list(self.events)[:50];status=dict(self.status)
                response=self.client.post('/api/agent/resource-guard/sync',{'events':events,'status':status},self.credential)
                self.install(response['policy'])
                self.status.pop('syncError',None)
                if response.get('checkRequest'):
                    self.status['lastCheckRequest']=response['checkRequest']
                ids={e['id'] for e in events}
                with self.lock:self.events=collections.deque((e for e in self.events if e['id'] not in ids),maxlen=200)
            except Exception as error:self.status['syncError']=type(error).__name__
            self.stop.wait(10)

    def run(self):
        try:
            import fcntl
            lock_path=self.directory/'guard.lock'
            with lock_path.open('a') as owner:
                lock_path.chmod(0o600)
                fcntl.flock(owner,fcntl.LOCK_EX|fcntl.LOCK_NB)
                threading.Thread(target=self.synchronize,name='guard-sync',daemon=True).start()
                while not self.stop.is_set():
                    try:
                        self.tick()
                        if self.status.get('lastCheckRequest'):
                            self.status['lastCheckEvaluatedAt']=time.time()
                            self.status['lastCheckOutcome']=self.status['state']
                    except Exception as error:self.status.update(state='ERROR',error=type(error).__name__);self.low_since=None
                    self.stop.wait(2)
        except (ImportError,OSError):self.status.update(state='UNSUPPORTED_OR_ANOTHER_GUARD_RUNNING')
