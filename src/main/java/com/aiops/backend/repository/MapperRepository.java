package com.aiops.backend.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.annotation.Transactional;

/** Synchronous mapper operations; entity changes require an explicit save. */
@Transactional(readOnly = true)
public abstract class MapperRepository<T, M extends BaseMapper<T>> {
    protected final M mapper;
    private final Function<T, Long> entityId;

    protected MapperRepository(M mapper, Function<T, Long> entityId) {
        this.mapper = mapper;
        this.entityId = entityId;
    }

    @Transactional
    public T save(T entity) {
        Objects.requireNonNull(entity, "entity");
        if (entityId.apply(entity) == null) {
            mapper.insert(entity);
        } else if (mapper.updateById(entity) == 0 && mapper.selectById(entityId.apply(entity)) == null) {
            throw new EmptyResultDataAccessException("Entity no longer exists: " + entityId.apply(entity), 1);
        }
        return entity;
    }

    public Optional<T> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(Objects.requireNonNull(id, "id")));
    }

    public boolean existsById(Long id) {
        return findById(id).isPresent();
    }

    public List<T> findAll() {
        return mapper.selectList(new QueryWrapper<T>().orderByAsc("id"));
    }

    public long count() {
        return mapper.selectCount(new QueryWrapper<T>());
    }

    @Transactional
    public void delete(T entity) {
        deleteById(Objects.requireNonNull(entityId.apply(entity), "entity.id"));
    }

    @Transactional
    public void deleteById(Long id) {
        mapper.deleteById(Objects.requireNonNull(id, "id"));
    }

    @Transactional
    public void deleteAll() {
        mapper.delete(new QueryWrapper<T>());
    }

    protected static int boundedLimit(int requested, int maximum) {
        return Math.min(Math.max(requested, 1), maximum);
    }
}
