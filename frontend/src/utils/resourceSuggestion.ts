export interface ResourceSuggestion {
  id: string
  sourceRunId: string
  label: string
  source: 'CONFIRMED_PROFILE' | 'OBSERVED_RUN'
  confidence: string
  runCount: number
  sampleCount: number
  sampledAt: string | null
  suggested: Record<string, number | undefined>
  multipleGpus: boolean
  basis: string
  missingEvidence: string[]
  taskType?: string | null
}

const resourceFields = [
  'expectedVramMb',
  'expectedRamMb',
  'expectedCpuCores',
  'expectedDiskReadMbps',
  'expectedDiskWriteMbps',
] as const

/** 只复制有数值依据的资源字段，不把历史命令、数据集或未知值带入新计划。 */
export function resourceSuggestionPatch(source: ResourceSuggestion) {
  if (source.multipleGpus)
    throw new Error('该记录使用多张 GPU，当前单卡资源计划不能直接复用')
  const patch: Record<string, number | undefined> = {}
  for (const field of resourceFields) {
    const value = source.suggested[field]
    patch[field] =
      typeof value === 'number' && Number.isFinite(value) && value > 0
        ? value
        : undefined
  }
  const gpu = source.suggested.gpuIndex
  if (typeof gpu === 'number' && Number.isInteger(gpu) && gpu >= 0)
    patch.gpuIndex = gpu
  return patch
}

export function workloadQuestion(
  serverId: number,
  row: { runId?: string | null; pid?: number; snapshotId?: number },
) {
  const identity = row.runId
    ? `运行 ID ${row.runId}`
    : `PID ${row.pid}，快照 ID ${row.snapshotId}`
  return `分析服务器 ${serverId} 上已运行的 GPU 任务（${identity}）。请查询实际资源及压力信号，说明可能的瓶颈；有授权历史画像时评估能否再运行同类任务，证据不足时明确说明。`
}

export function confidenceText(value?: string) {
  return value === 'STATISTICAL'
    ? '有匹配历史'
    : value === 'LOW_CONFIDENCE'
      ? '样本不足，仅供参考'
      : value === 'ESTIMATE'
        ? '估算参考'
        : '证据不足'
}
