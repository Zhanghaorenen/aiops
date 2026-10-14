package com.aiops.backend.controller;

import com.aiops.backend.service.ServerService;
import com.aiops.backend.vo.ServerVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService serverService;

    public ServerController(ServerService serverService) {
        this.serverService = serverService;
    }

    @GetMapping
    public List<ServerVO> listServers() {
        return serverService.listServers();
    }

    @GetMapping("/{id}")
    public ServerVO getServerById(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return serverService.getServerById(id);
    }
}
