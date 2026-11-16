package com.aiops.backend.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.Map;

final class InvestigationTrace {
  private InvestigationTrace() {}

  static String parent() {
    if (!Boolean.parseBoolean(System.getenv("OTEL_ENABLED"))) return null;
    SpanContext ctx = Span.current().getSpanContext();
    return ctx.isValid()
        ? "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + ctx.getTraceFlags().asHex()
        : null;
  }

  static Span claimed(Map<String, Object> run) {
    if (!Boolean.parseBoolean(System.getenv("OTEL_ENABLED"))) return Span.getInvalid();
    var carrier = Map.of("traceparent", java.util.Objects.toString(run.get("trace_parent"), ""));
    Context parent =
        W3CTraceContextPropagator.getInstance()
            .extract(
                Context.root(),
                carrier,
                new TextMapGetter<Map<String, String>>() {
                  public Iterable<String> keys(Map<String, String> value) {
                    return List.of("traceparent");
                  }

                  public String get(Map<String, String> value, String key) {
                    return value.get(key);
                  }
                });
    return GlobalOpenTelemetry.getTracer("aiops.worker")
        .spanBuilder("investigation.claimed")
        .setParent(parent)
        .setAttribute("taskId", run.get("id").toString())
        .setAttribute("runId", run.get("id").toString())
        .setAttribute("threadId", run.get("id").toString())
        .setAttribute("incidentId", run.get("incident_id").toString())
        .setAttribute("userId", run.get("requested_by").toString())
        .startSpan();
  }
}
