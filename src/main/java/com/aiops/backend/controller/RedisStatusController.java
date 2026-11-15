package com.aiops.backend.controller;

import com.aiops.backend.redis.*;
import com.aiops.backend.service.ClusterPermissionService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class RedisStatusController {
  private final ResourceCache cache;
  private final NotificationEvents events;
  private final ClusterPermissionService access;
  public RedisStatusController(ResourceCache cache,NotificationEvents events,ClusterPermissionService access) {
    this.cache=cache; this.events=events; this.access=access;
  }
  @GetMapping("/api/redis/status") public Object status() {
    access.currentUser();
    return Map.of("cache",cache.stats(),"notifications",events.stats(),"counterScope","process");
  }
}
