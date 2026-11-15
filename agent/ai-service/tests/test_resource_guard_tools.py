import unittest
from types import SimpleNamespace
import httpx
from app.client.platform_client import PlatformClient
from app.tools.server_tool import ServerTool

class ResourceGuardToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_request_is_authenticated_bounded_check_not_a_kill_receipt(self):
        requests=[]
        def backend(request):
            requests.append(request)
            return httpx.Response(200,json={'requested':True,'executed':False} if request.method=='POST' else {'policy':{'mode':'DISABLED'},'events':[]})
        platform=PlatformClient('http://fixture.test','fixture-jwt',transport=httpx.MockTransport(backend))
        try:
            tools={t.name:t for t in ServerTool(SimpleNamespace(client=platform)).get_tools()}
            self.assertEqual(set(tools['request_resource_relief'].args_schema.model_fields),{'server_id'})
            await tools['get_resource_guard_status'].ainvoke({'server_id':17})
            result=await tools['request_resource_relief'].ainvoke({'server_id':17})
            self.assertTrue(result['requested']);self.assertFalse(result['executed'])
            self.assertEqual([(r.method,r.url.path) for r in requests],[('GET','/api/servers/17/resource-guard'),('POST','/api/servers/17/resource-guard/check')])
            self.assertTrue(all(r.headers['Authorization']=='Bearer fixture-jwt' for r in requests))
            self.assertEqual(requests[-1].content,b'')
        finally:await platform.close()

    async def test_permission_denial_not_retried_or_presented_as_success(self):
        requests=[]
        def denied(request):requests.append(request);return httpx.Response(403)
        client=PlatformClient('http://fixture.test','fixture',transport=httpx.MockTransport(denied))
        try:
            with self.assertRaises(httpx.HTTPStatusError):await client.request_resource_relief(17)
            self.assertEqual(len(requests),1)
        finally:await client.close()

if __name__=='__main__':unittest.main()
