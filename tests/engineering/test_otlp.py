import threading
import unittest
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import (
    ExportTraceServiceRequest,
)
from app import telemetry


class EndpointTests(unittest.TestCase):
    def test_langfuse_export_contains_usage_and_no_model_content(self):
        received = []

        class Sink(BaseHTTPRequestHandler):
            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                received.append((self.path, ExportTraceServiceRequest.FromString(raw)))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Sink)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with (
                patch.object(telemetry, "_provider", None),
                patch.dict(
                    os.environ,
                    {
                        "LANGFUSE_ENABLED": "true",
                        "LANGFUSE_HOST": f"http://127.0.0.1:{server.server_port}",
                        "LANGFUSE_PUBLIC_KEY": "fixture-public",
                        "LANGFUSE_SECRET_KEY": "fixture-secret",
                        "OTEL_ENABLED": "false",
                    },
                ),
            ):
                with telemetry.request_trace() as trace:
                    telemetry.identify(caseId="fixture")
                    with (
                        self.assertRaises(ValueError),
                        telemetry.span("llm.chat", "generation") as usage,
                    ):
                        usage["gen_ai.request.model"] = "fixture"
                        usage["gen_ai.usage.input_tokens"] = 12
                        raise ValueError("SECRET_CANARY_PRIVATE_PROMPT")
                telemetry.provider().force_flush()
                telemetry.provider().shutdown()
                self.assertEqual(received[0][0], "/api/public/otel/v1/traces")
                spans = [
                    s
                    for _, request in received
                    for rs in request.resource_spans
                    for ss in rs.scope_spans
                    for s in ss.spans
                ]
                self.assertTrue(
                    spans and all(s.trace_id.hex() == trace.trace_id for s in spans)
                )
                self.assertIn("gen_ai.usage.input_tokens", str(spans))
                self.assertNotIn("SECRET_CANARY", str(spans))
                self.assertNotIn("fixture-secret", str(spans))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)
