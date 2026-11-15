import hashlib
import json

def merge(results):
    index=[]
    by_signature={}
    remap={}
    for result in results.values():
        for item in result.evidence:
            signature=hashlib.sha256(json.dumps([item.source_tool,item.observed_at,item.status,item.data],
                sort_keys=True,ensure_ascii=False,default=str).encode()).hexdigest()
            if signature in by_signature:
                remap[item.evidence_id]=by_signature[signature]
            else:
                by_signature[signature]=item.evidence_id
                index.append(item)
    for result in results.values():
        for finding in result.findings:
            finding.supporting_evidence_ids=[remap.get(i,i) for i in finding.supporting_evidence_ids]
            finding.refuting_evidence_ids=[remap.get(i,i) for i in finding.refuting_evidence_ids]
    return index
