from datetime import datetime,timezone
from .agents.monitoring_agent import MONITORING_TOOLS
from .agents.workload_agent import WORKLOAD_TOOLS
from .agents.knowledge_agent import KNOWLEDGE_TOOLS
from .schemas import Finding
TOOLS={"monitoring":MONITORING_TOOLS,"workload":WORKLOAD_TOOLS,"knowledge":KNOWLEDGE_TOOLS}

def validate(findings,evidence):
    index={e.evidence_id:e for e in evidence}
    for item in evidence:
        if item.source_tool not in TOOLS[item.source_agent]:
            item.status="UNAVAILABLE"
            item.limitations.append("证据工具来源不符合领域白名单")
        elif item.category=="document" or item.source_agent=="knowledge":
            item.category="document"
            item.status="REFERENCE"
        if item.status=="FRESH":
            try:
                at=datetime.fromisoformat(item.observed_at.replace("Z","+00:00"))
                if at.tzinfo is None: at=at.replace(tzinfo=timezone.utc)
                age=(datetime.now(timezone.utc)-at).total_seconds()
                if not -30<=age<=90: item.status="STALE"
            except (ValueError,AttributeError): item.status="UNKNOWN"
    conflicts=set()
    seen={}
    for item in evidence:
        identity=(item.source_tool,item.observed_at,item.data.get("server_id",item.data.get("serverId")),
                  item.data.get("gpuIndex",item.data.get("gpu_id")),item.data.get("runId"))
        if item.observed_at and identity in seen and seen[identity].data!=item.data:
            conflicts.update((item.evidence_id,seen[identity].evidence_id))
        seen[identity]=item
    result=[]
    for original in findings:
        finding=original.model_copy(deep=True)
        refs=finding.supporting_evidence_ids+finding.refuting_evidence_ids
        reasons=[]
        if any(i in conflicts for i in refs): reasons.append("同源、同对象、同采样时间的证据存在差异")
        if any(i not in index for i in refs): reasons.append("引用不存在的证据")
        if set(finding.supporting_evidence_ids)&set(finding.refuting_evidence_ids):
            reasons.append("同一证据同时被标为支持与反对，需要核对解释")
        support=[index[i] for i in finding.supporting_evidence_ids if i in index]
        if finding.finding_type=="observation" and (not support or any(e.status!="FRESH" for e in support)):
            reasons.append("缺少新鲜监控事实，静态参考和未知数据不能确认实时状态")
        if finding.finding_type=="observation" and any(w in finding.statement for w in ("根因","导致","证明竞争")):
            reasons.append("因果解释仍需验证")
        if not support and finding.finding_type!="recommendation": reasons.append("缺少支持证据")
        if reasons:
            finding.finding_type="hypothesis"
            finding.uncertainty="未验证："+ "；".join(reasons)
        result.append(finding)
    return result
