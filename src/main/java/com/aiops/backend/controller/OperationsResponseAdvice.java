package com.aiops.backend.controller;

import java.time.*;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** JDBC 的 DATETIME 是 UTC 墙钟值，不能让默认时区把它在序列化时再次偏移。 */
@RestControllerAdvice(
    assignableTypes = {
      AlertController.class,
      IncidentController.class,
      WorkspaceController.class,
      AgentManagementController.class,
      GpuUsageController.class
    })
public class OperationsResponseAdvice implements ResponseBodyAdvice<Object> {
  @Override
  public boolean supports(
      MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter method,
      MediaType type,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    return normalize(body);
  }

  private Object normalize(Object value) {
    if (value instanceof java.sql.Timestamp stamp)
      return stamp.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((k, v) -> result.put(k.toString(), normalize(v)));
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(this::normalize).toList();
    return value;
  }
}
