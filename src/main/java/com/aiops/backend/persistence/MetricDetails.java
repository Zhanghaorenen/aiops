package com.aiops.backend.persistence;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 只持久化约定的扩展指标，命令行、环境变量和未知字段不会进入采样表。 */
public final class MetricDetails {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final String PROCESS =
      "cpuPercent,memoryRssBytes,numThreads,startedAt,status,executablePath,workingDirectory,parentPid,readBytes,writeBytes,readBytesPerSecond,writeBytesPerSecond";
  public static final String GPU = "powerLimitW,memoryClockMhz";

  public static String encode(Map<String, Object> input, String kind) {
    if (input == null) return null;
    Map<String, Object> result;
    if (kind.equals("HOST")) {
      result =
          pick(
              input,
              "load1,load5,load15,memoryAvailableBytes,memoryCachedBytes,swapTotalBytes,swapUsedBytes,swapPercent,temperatureStatus,cpuPsiAvg10,memoryPsiAvg10,ioPsiAvg10,oomKillCount,shmTotalBytes,shmUsedBytes,shmPercent");
      Object temperatureStatus = result.get("temperatureStatus");
      if (temperatureStatus != null && !Set.of("AVAILABLE", "UNSUPPORTED", "UNAVAILABLE").contains(temperatureStatus))
        throw bad();
      // 温度继续保存在主机原有扩展字段中，旧采样没有该字段时保持空列表。
      var temperatures = rows(input.get("temperatures"),
          "sensor,label,category,currentCelsius,highCelsius,criticalCelsius");
      result.put("temperatures", temperatures);
      for (var sensor : temperatures) {
        if (!(sensor.get("category") instanceof String category) || !Set.of("CPU", "BOARD", "STORAGE", "OTHER").contains(category)) throw bad();
        if (!sensor.containsKey("currentCelsius")) throw bad();
      }
      result.put("processExits", rows(input.get("processExits"), "pid,startedAt,observedAt,status"));
      Object cores = input.get("perCorePercent");
      if (cores instanceof List<?> list && list.size() <= 1024) {
        for (Object n : list) checkNumber(n, "perCorePercent");
        result.put("perCorePercent", list);
      }
      result.put(
          "disks",
          rows(
              input.get("disks"),
              "device,mountpoint,fstype,totalBytes,usedBytes,freeBytes,percent,readBytesPerSecond,writeBytesPerSecond,readIops,writeIops,busyPercent"));
      result.put(
          "network",
          rows(
              input.get("network"),
              "name,bytesReceived,bytesSent,receiveBytesPerSecond,sendBytesPerSecond,errorsIn,errorsOut,dropsIn,dropsOut"));
    } else result = pick(input, kind.equals("GPU") ? GPU : PROCESS);
    if (kind.equals("PROCESS")) {
      result.put("processTree", rows(input.get("processTree"), "pid,parentPid,name,startedAt,memoryRssBytes"));
      if(input.get("processTreeTruncated") instanceof Boolean truncated) result.put("processTreeTruncated",truncated);
    }
    String value = JSON.writeValueAsString(result);
    if (value.length() > 65536) throw bad();
    return value;
  }

  private static List<Map<String, Object>> rows(Object value, String fields) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list) || list.size() > 256) throw bad();
    return list.stream()
        .map(
            item -> {
              if (!(item instanceof Map<?, ?> map)) throw bad();
              @SuppressWarnings("unchecked")
              var data = (Map<String, Object>) map;
              return pick(data, fields);
            })
        .toList();
  }

  private static Map<String, Object> pick(Map<String, Object> input, String fields) {
    var output = new LinkedHashMap<String, Object>();
    for (String key : fields.split(",")) {
      Object value = input.get(key);
      if (value == null) continue;
      if (Set.of(
              "device",
              "mountpoint",
              "fstype",
              "name",
              "startedAt",
              "observedAt",
              "status",
              "executablePath",
              "workingDirectory")
          .contains(key) || Set.of("sensor", "label", "category", "temperatureStatus")
          .contains(key)) {
        if (!(value instanceof String s) || s.length() > 1024 || s.chars().anyMatch(c -> c < 32))
          throw bad();
      } else checkNumber(value, key);
      output.put(key, value);
    }
    return output;
  }

  private static void checkNumber(Object value, String key) {
    if (Set.of("currentCelsius", "highCelsius", "criticalCelsius").contains(key)) {
      if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < -100 || n.doubleValue() > 250)
        throw bad();
      return;
    }
    if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0)
      throw bad();
    if (Set.of("percent", "perCorePercent", "swapPercent").contains(key) && n.doubleValue() > 100)
      throw bad();
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> decode(String value) {
    if (value == null || value.isBlank()) return Map.of();
    return JSON.readValue(value, Map.class);
  }

  private static ResponseStatusException bad() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "扩展采样字段格式或长度无效");
  }
}
