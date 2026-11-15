"""Deterministic display quantities. Original observations are never rewritten."""
from decimal import Decimal, ROUND_HALF_UP
import math

def number(value):
    return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value)

def rounded(value):
    return float(Decimal(str(value)).quantize(Decimal('0.01'),rounding=ROUND_HALF_UP))

def capacity(value):
    if not number(value) or value < 0:return None
    return {unit:rounded(Decimal(str(value))/scale) for unit,scale in
            [('MB',10**6),('MiB',2**20),('GB',10**9),('GiB',2**30)]}

def percent(used,total):
    return rounded(Decimal(str(used))/Decimal(str(total))*100) if number(used) and number(total) and total>0 and used>=0 else None

def gpu_evidence(gpu):
    return {**gpu, 'capacity_display':{k:capacity(gpu.get('gpu_memory_'+k)) for k in ['total','used','free']},
            'gpu_memory_capacity_usage_percent':percent(gpu.get('gpu_memory_used'),gpu.get('gpu_memory_total')),
            'metric_semantics':'capacity usage=used/total; memory_utilization is memory-controller activity; utilization is whole GPU compute activity'}

def result_summary(name,value):
    if name=='get_gpu_processes' and isinstance(value,list):
        indices=sorted({x['gpu_index'] for x in value})
        return {'gpu_process_count':len(value),'process_counts_by_gpu':{str(i):sum(x['gpu_index']==i for x in value) for i in indices}}
    if name=='get_active_workloads' and isinstance(value,list):
        resources=[x.get('resources',{}) for x in value]
        sums={k:sum(x[k] for x in resources) if resources and all(number(x.get(k)) for x in resources) else None for k in ['cpuPercent','rssEstimateBytes','gpuMemoryBytes']}
        return {'workload_count':len(value),'observed_totals':sums,'rss_display':capacity(sums['rssEstimateBytes']),
                'gpu_memory_display':capacity(sums['gpuMemoryBytes']),'cpu_core_equivalents':rounded(sums['cpuPercent']/100) if sums['cpuPercent'] is not None else None,
                'limitations':'CPU process percentage may exceed 100; RSS upper estimate includes shared pages; memory discrepancies do not establish their cause'}
    return None
