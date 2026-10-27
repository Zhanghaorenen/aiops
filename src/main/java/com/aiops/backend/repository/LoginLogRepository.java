package com.aiops.backend.repository;

import com.aiops.backend.entity.LoginLogEntity;
import com.aiops.backend.mapper.LoginLogMapper;
import org.springframework.stereotype.Repository;


@Repository
public class LoginLogRepository extends MapperRepository<LoginLogEntity, LoginLogMapper> {
    public LoginLogRepository(LoginLogMapper mapper) {
        super(mapper, LoginLogEntity::getId);
    }
}
