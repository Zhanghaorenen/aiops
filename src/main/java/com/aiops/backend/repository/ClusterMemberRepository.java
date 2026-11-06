package com.aiops.backend.repository;
import com.aiops.backend.entity.ClusterMemberEntity;
import com.aiops.backend.mapper.ClusterMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository public class ClusterMemberRepository extends MapperRepository<ClusterMemberEntity,ClusterMemberMapper> {
 public ClusterMemberRepository(ClusterMemberMapper mapper){super(mapper,ClusterMemberEntity::getId);}
 public Optional<ClusterMemberEntity> findMember(Long cluster,Long user){return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ClusterMemberEntity>().eq("cluster_id",cluster).eq("user_id",user)));}
 public long countForCluster(Long cluster){return mapper.selectCount(new QueryWrapper<ClusterMemberEntity>().eq("cluster_id",cluster));}
}
