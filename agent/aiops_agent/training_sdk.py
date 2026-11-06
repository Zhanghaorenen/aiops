"""可选训练进度上报。使用用户自己的 JWT，仅向平台报告，不启动或终止训练。"""
import json
from urllib.request import Request, urlopen


class TrainingMonitor:
    def __init__(self, backend_url, token, server_id, job_id, timeout=5):
        self.url = f"{backend_url.rstrip('/')}/api/servers/{int(server_id)}/training/jobs/{int(job_id)}"
        self.token, self.timeout = token, timeout

    def _post(self, path, payload):
        request = Request(self.url + path, json.dumps(payload, allow_nan=False).encode(),
                          {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        with urlopen(request, timeout=self.timeout) as response:
            response.read()

    def report(self, *, epoch=None, step=None, step_time=None, samples_per_second=None, phase=None, loss=None):
        self._post('/progress', {'epoch': epoch, 'step': step, 'stepTime': step_time,
                                'samplesPerSecond': samples_per_second, 'phase': phase, 'loss': loss})

    def finish(self, success=True):
        self._post('/state', {'state': 'SUCCEEDED' if success else 'FAILED'})
