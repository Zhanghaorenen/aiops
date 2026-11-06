package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.persistence.MetricDetails;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class HostTemperatureTests {
  @Test
  void preservesSensorCategoriesAndCelsiusIncludingNegativeReadings() {
    var input = Map.<String, Object>of("temperatureStatus", "AVAILABLE", "temperatures", List.of(
        Map.of("sensor", "coretemp", "label", "Package 0", "category", "CPU", "currentCelsius", 61.5, "criticalCelsius", 100),
        Map.of("sensor", "board", "category", "BOARD", "currentCelsius", -5, "environment", "DROP")));
    var decoded = MetricDetails.decode(MetricDetails.encode(input, "HOST"));
    assertEquals("AVAILABLE", decoded.get("temperatureStatus"));
    var readings = (List<?>) decoded.get("temperatures");
    assertEquals(2, readings.size());
    assertEquals(61.5, ((Number) ((Map<?, ?>) readings.get(0)).get("currentCelsius")).doubleValue());
    assertEquals(-5, ((Number) ((Map<?, ?>) readings.get(1)).get("currentCelsius")).intValue());
    assertFalse(((Map<?, ?>) readings.get(1)).containsKey("environment"));
  }

  @Test
  void unsupportedAndLegacyAgentHaveNoInventedTemperatures() {
    assertEquals(List.of(), MetricDetails.decode(MetricDetails.encode(Map.of("temperatureStatus", "UNSUPPORTED"), "HOST")).get("temperatures"));
    assertEquals(List.of(), MetricDetails.decode(MetricDetails.encode(Map.of(), "HOST")).get("temperatures"));
    assertTrue(MetricDetails.decode(null).isEmpty());
  }

  @Test
  void invalidSensorValuesAreRejectedAsBadRequests() {
    for (var reading : List.of(
        Map.of("category", "CPU", "currentCelsius", 999),
        Map.of("category", "CPU", "currentCelsius", Double.NaN),
        Map.of("category", "CPU", "currentCelsius", "60"),
        Map.of("category", "UNKNOWN", "currentCelsius", 60),
        Map.of("label", "missing category", "currentCelsius", 60))) {
      var error = assertThrows(ResponseStatusException.class, () -> MetricDetails.encode(Map.of("temperatures", List.of(reading)), "HOST"));
      assertEquals(400, error.getStatusCode().value());
    }
  }
}
