package com.aiops.backend.repository;

import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.mapper.UserMapper;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Repository
public class UserRepository extends MapperRepository<UserEntity, UserMapper> {
    public UserRepository(UserMapper mapper) {
        super(mapper, UserEntity::getId);
    }

    public Optional<UserEntity> findByUsername(String username) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<UserEntity>().eq("username", username)));
    }
}
