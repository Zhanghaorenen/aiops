package com.aiops.backend.service;

import com.aiops.backend.vo.ServerVO;
import java.util.List;

public interface ServerService {

    List<ServerVO> listServers();

    ServerVO getServerById(Long id);
}
