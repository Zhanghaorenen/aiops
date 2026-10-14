package com.aiops.backend.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aiops.backend.service.HealthService;
@RestController  
public class HealthController {
    
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/api/health")
    public Map<String,String> health(){
        return healthService.getHealthStatus();
    }
}
