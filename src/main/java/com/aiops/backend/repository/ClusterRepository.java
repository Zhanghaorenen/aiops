package com.aiops.backend.repository;
import com.aiops.backend.entity.ClusterEntity;
import com.aiops.backend.mapper.ClusterMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository public class ClusterRepository extends MapperRepository<ClusterEntity,ClusterMapper> {
 public static final String DEFAULT_CLUSTER_NAME="default-cluster";
 public ClusterRepository(ClusterMapper mapper){super(mapper,ClusterEntity::getId);}
 public Optional<ClusterEntity> findDefault(){return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ClusterEntity>().eq("cluster_name",DEFAULT_CLUSTER_NAME)));}
 public ClusterEntity requireDefault(){return findDefault().filter(c->"ACTIVE".equals(c.getStatus())).orElseThrow(()->new IllegalStateException("Active default-cluster is missing"));}
 public ClusterEntity lockDefault(){return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ClusterEntity>().eq("cluster_name",DEFAULT_CLUSTER_NAME).last("FOR UPDATE"))).orElseThrow(()->new IllegalStateException("default-cluster is missing"));}
}
