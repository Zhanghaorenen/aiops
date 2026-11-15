import asyncio,json,unittest
from datetime import datetime,timedelta,timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock
import httpx
from langchain_core.tools import StructuredTool
from app.agents.graph_agent import GraphAgent
from app.tools.numeric_evidence import capacity,percent,result_summary
from app.tools.gpu_tool import GpuTool
from app.tools.gpu_manager import GPUManager
from app.tools.server_manager import ServerManager

def calls(n,prefix):
    return [{'id':f'{prefix}{i}','type':'function','function':{'name':'observe','arguments':json.dumps({'value':i})}} for i in range(n)]

class Reliability(unittest.IsolatedAsyncioTestCase):
    def test_units_and_nulls(self):
        for n in [0,1,2**20,2**30,10**9,26568884224]:
            self.assertAlmostEqual(capacity(n)['GiB'],round(n/2**30,2),places=2)
        self.assertEqual(capacity(26568884224)['GB'],26.57)
        self.assertEqual(percent(26568884224,51527024640),51.56)
        self.assertIsNone(capacity(None));self.assertIsNone(capacity(True));self.assertIsNone(percent(3,0))

    def test_process_counts(self):
        self.assertEqual(result_summary('get_gpu_processes',[])['gpu_process_count'],0)
        rows=[{'pid':2,'gpu_index':4},{'pid':3,'gpu_index':4},{'pid':8,'gpu_index':9}]
        self.assertEqual(result_summary('get_gpu_processes',rows)['process_counts_by_gpu'],{'4':2,'9':1})

    async def test_cpu_indices_and_unknown_disk_semantics(self):
        m=ServerManager(SimpleNamespace(get_server_latest_metrics=AsyncMock(return_value={'details':{'perCorePercent':[100,45,47,99]}})))
        r=await m.get_resource_usage(19)
        self.assertEqual(r['cpu_core_summary']['fully_busy_indices'],[0]);self.assertIn('unspecified',r['evidence_limits'])

    async def make_graph(self,plans):
        self.active=0;self.peak=0;self.executed=[];self.sent=[];self.reject=None
        async def observe(value:int):
            self.active+=1;self.peak=max(self.peak,self.active)
            await asyncio.sleep(.002);self.executed.append(value);self.active-=1
            if self.reject:raise self.reject
            return {'value':value}
        tool=StructuredTool.from_function(coroutine=observe,name='observe',description='Read only observation')
        async def chat(messages,tools=None):
            self.sent.append(messages)
            p=plans.pop(0)
            return SimpleNamespace(content='Evidence unavailable' if not p else '',tool_calls=p)
        return GraphAgent(SimpleNamespace(chat=chat),SimpleNamespace(get_tools=lambda:[tool]),SimpleNamespace(get_tools=lambda:[]))

    async def test_seven_calls_batched_and_ids_balanced(self):
        g=await self.make_graph([calls(7,'a'),[]]);await g.answer('read')
        self.assertEqual(len(self.executed),7);self.assertLessEqual(self.peak,3)
        msg=self.sent[-1];ids=[x['tool_call_id'] for x in msg if x['role']=='tool']
        self.assertEqual(ids,[f'a{i}' for i in range(7)])

    async def test_total_budget_returns_response_for_every_id(self):
        g=await self.make_graph([calls(8,'a'),calls(5,'b'),[]]);await g.answer('read')
        self.assertEqual(len(self.executed),8)
        messages=self.sent[-1];self.assertEqual(sum(x['role']=='tool' for x in messages),13)
        self.assertTrue(all('tool_budget_exceeded' in x['content'] for x in messages if x.get('tool_call_id','').startswith('b')))

    async def test_duplicate_id_rejected_before_execution(self):
        p=calls(2,'a');p[1]['id']=p[0]['id'];g=await self.make_graph([p])
        from app.llm.client import ModelResponseError
        with self.assertRaises(ModelResponseError):await g.answer('read')
        self.assertEqual(self.executed,[])

    async def test_failed_signature_not_executed_again(self):
        g=await self.make_graph([calls(1,'a'),calls(1,'b'),[]]);self.reject=ValueError('invalid observation')
        await g.answer('read')
        self.assertEqual(len(self.executed),1)
        self.assertEqual([x['tool_call_id'] for x in self.sent[-1] if x['role']=='tool'],['a0','b0'])

    async def test_permission_error_aborts_batches(self):
        g=await self.make_graph([calls(7,'a')])
        req=httpx.Request('GET','http://platform.test/api/servers')
        self.reject=httpx.HTTPStatusError('denied',request=req,response=httpx.Response(403,request=req))
        with self.assertRaises(httpx.HTTPStatusError):await g.answer('read')
        self.assertLessEqual(len(self.executed),3)

    async def test_batched_stream_preserves_tool_events(self):
        plans=[calls(7,'s'),[]];g=await self.make_graph([])
        async def stream(messages,tools=None):
            p=plans.pop(0)
            yield {'type':'tool_calls','calls':p} if p else {'type':'token','text':'Only observed evidence'}
        g.llm.stream_chat=stream
        events=[x async for x in g.astream('read')]
        self.assertEqual(sum(e['type']=='tool_start' for e in events),7)
        self.assertEqual(sum(e['type']=='tool_end' for e in events),7)
        self.assertTrue(any(e['type']=='token' for e in events))

    async def test_platform_clock_overrides_collector_and_local_time(self):
        platform_now=datetime.now(timezone.utc)-timedelta(seconds=20)
        manager=SimpleNamespace(client=SimpleNamespace(server_now=lambda:platform_now),get_gpu_history=AsyncMock(return_value=[]))
        r=await GpuTool(manager).get_recent_gpu_history(21,9,'temperature',30)
        self.assertEqual(datetime.fromisoformat(r['end_time']),platform_now-timedelta(seconds=2))

    async def test_recent_history_has_utc_window_and_empty_status(self):
        now=datetime.now(timezone.utc)
        manager=SimpleNamespace(client=SimpleNamespace(server_now=lambda:now),get_gpu_history=AsyncMock(return_value=[]))
        t=GpuTool(manager);r=await t.get_recent_gpu_history(19,4,'utilization',30)
        start,end=map(datetime.fromisoformat,(r['start_time'],r['end_time']))
        self.assertEqual((end-start).total_seconds(),1800);self.assertLess(end,now)
        self.assertEqual(r['status'],'no_history')
        with self.assertRaises(ValueError):await t.get_recent_gpu_history(19,4,'wrong',30)
        with self.assertRaises(ValueError):await t.get_recent_gpu_history(19,4,'utilization',0)

    async def test_explicit_future_and_naive_times_rejected(self):
        m=GPUManager(SimpleNamespace(get_gpu_history=AsyncMock()))
        now=datetime.now(timezone.utc)
        with self.assertRaises(ValueError):await m.get_gpu_history(19,4,now.isoformat(),(now+timedelta(seconds=5)).isoformat())
        with self.assertRaises(ValueError):await m.get_gpu_history(19,4,'2026-01-01','2026-01-02')
        m.client.get_gpu_history.assert_not_called()

    def test_prompt_boundary_is_explicit(self):
        s=GraphAgent._initial_state('why',None,False)['messages'][0].content
        self.assertIn('Swap占用不证明正在换页',s);self.assertIn('不得推断代码内部',s)

if __name__=='__main__':unittest.main()
