import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock
import httpx
from app.client.platform_client import PlatformClient
from app.tools.server_tool import ServerTool


class TrainingToolsTests(unittest.IsolatedAsyncioTestCase):
    async def test_training_queries_forward_jwt_and_use_existing_platform_authorization(self):
        requests=[]
        def backend(request):
            requests.append(request)
            return httpx.Response(200,json=[] if request.url.path.endswith('/jobs') else {'missing':['no PSI']})
        client=PlatformClient('http://platform.test','test-jwt',transport=httpx.MockTransport(backend))
        try:
            await client.get_training_jobs(2)
            await client.get_training_analysis(2,7)
            self.assertEqual([r.url.path for r in requests],['/api/servers/2/training/jobs','/api/servers/2/training/jobs/7/analysis'])
            self.assertTrue(all(r.headers['Authorization']=='Bearer test-jwt' for r in requests))
        finally:
            await client.close()

    async def test_graph_tools_only_expose_training_read_operations(self):
        platform=SimpleNamespace(get_training_jobs=AsyncMock(return_value=[]), get_training_analysis=AsyncMock(return_value={'observations':['IO pressure'],'hostEvidence':[{'collectedAt':'2026-11-06T00:00:00Z'}],'missing':['exit code']}))
        tools=ServerTool(SimpleNamespace(client=platform)).get_tools()
        names={t.name:t for t in tools}
        self.assertIn('list_training_jobs',names)
        self.assertFalse(any('start' in name or 'cancel' in name for name in names))
        report=await names['analyze_training_contention'].ainvoke({'server_id':2,'job_id':7})
        self.assertEqual(report['observations'],['IO pressure'])
        self.assertEqual(report['missing'],['exit code'])
