package com.aiops.backend.service;
import com.aiops.backend.entity.ClusterMemberEntity;
import com.aiops.backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** Deployment-only bootstrap, deliberately has no public HTTP endpoint. */
@Service public class ClusterBootstrapService {
 private final ClusterRepository clusters;private final ClusterMemberRepository members;private final UserRepository users;
 public ClusterBootstrapService(ClusterRepository c,ClusterMemberRepository m,UserRepository u){clusters=c;members=m;users=u;}
 @Transactional public boolean bootstrap(Long confirmedUserId){
  if(confirmedUserId==null || confirmedUserId<=0)throw new IllegalArgumentException("Set AIOPS_CLUSTER_BOOTSTRAP_USER_ID to a verified existing user ID");
  var cluster=clusters.lockDefault();
  if(cluster.getBootstrapCompletedAt()!=null)return false;
  if(!"ACTIVE".equals(cluster.getStatus()))throw new IllegalStateException("Default cluster is not ACTIVE");
  var user=users.findById(confirmedUserId).filter(u->"ACTIVE".equals(u.getStatus())).orElseThrow(()->new IllegalStateException("Bootstrap user is missing or disabled"));
  if(members.countForCluster(cluster.getId())!=0)throw new IllegalStateException("Existing members require manual bootstrap review");
  members.save(new ClusterMemberEntity(cluster.getId(),user.getId(),"ADMIN"));
  cluster.completeBootstrap(user.getId());clusters.save(cluster);return true;
 }
}
