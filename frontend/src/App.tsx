import { useCallback, useEffect, useState } from 'react'
import { Alert, App as AntApp, Button, ConfigProvider, Descriptions, Empty, Form, Input, Menu, Modal, Progress, Segmented, Select, Space, Spin, Switch, Table, Tabs, Tag, Tooltip } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { ApiOutlined, ArrowLeftOutlined, ArrowRightOutlined, CheckCircleOutlined, CopyOutlined, DashboardOutlined, DatabaseOutlined, DesktopOutlined, DownloadOutlined, ExperimentOutlined, KeyOutlined, LogoutOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, SearchOutlined, SettingOutlined, UserOutlined } from '@ant-design/icons'
import { api, errorMessage, type LoginResponse, type MetricSample, type Server } from './api'
import TrendChart from './TrendChart'
import { demoHistory, demoServers } from './demo'

const TOKEN_KEY = 'aiops.accessToken'
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未上报'
const percent = (value: number | null) => value == null ? '—' : `${value.toFixed(1)}%`
const bytes = (value: number | null) => value == null ? '—' : value >= 1024 ** 3 ? `${(value / 1024 ** 3).toFixed(1)} GB` : `${(value / 1024 ** 2).toFixed(1)} MB`
const status = (server: Server) => <Tag color={server.status === 'ONLINE' ? 'success' : 'default'}>{server.status === 'ONLINE' ? '在线' : '离线'}</Tag>

export default function App() {
  return <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#087f78', borderRadius: 6, fontFamily: '"Segoe UI", "Microsoft YaHei", sans-serif' } }}><AntApp><Console /></AntApp></ConfigProvider>
}

function Console() {
  const { message } = AntApp.useApp()
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY))
  const [demo, setDemo] = useState(false)
  const [route, setRoute] = useState(location.hash.slice(1) || 'overview')
  const [servers, setServers] = useState<Server[]>([])
  const [username, setUsername] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [updatedAt, setUpdatedAt] = useState<string | null>(null)
  const [loginBusy, setLoginBusy] = useState(false)
  const [enrollment, setEnrollment] = useState<{ token: string; expiresAt: string } | null>(null)
  const [tokenBusy, setTokenBusy] = useState(false)
  const [confirmToken, setConfirmToken] = useState(false)
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('ALL')
  const active = !!token || demo
  const page = route.split('/')[0]
  const selectedId = Number(route.split('/')[1]) || null
  const selected = servers.find(s => s.id === selectedId)

  const navigate = (value: string) => { location.hash = value; setRoute(value) }
  const signOut = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY)
    setToken(null); setDemo(false); setServers([]); setUsername(''); setEnrollment(null); setError(''); setUpdatedAt(null)
    location.hash = 'overview'; setRoute('overview')
  }, [])
  useEffect(() => {
    const onHash = () => setRoute(location.hash.slice(1) || 'overview')
    const onUnauthorized = () => { signOut(); message.warning('登录已失效，请重新登录') }
    window.addEventListener('hashchange', onHash)
    window.addEventListener('aiops:unauthorized', onUnauthorized)
    return () => { window.removeEventListener('hashchange', onHash); window.removeEventListener('aiops:unauthorized', onUnauthorized) }
  }, [signOut, message])

  useEffect(() => {
    if (!token || demo) return
    const controller = new AbortController()
    void api.get<{ username: string }>('/api/auth/me', { signal: controller.signal })
      .then(({ data }) => setUsername(data.username)).catch(() => undefined)
    return () => controller.abort()
  }, [token, demo])

  useEffect(() => {
    if (!active) return
    const controller = new AbortController()
    let busy = false
    async function load() {
      if (busy) return
      busy = true; setLoading(true)
      try {
        const data = demo ? demoServers() : (await api.get<Server[]>('/api/servers', { signal: controller.signal })).data
        if (!controller.signal.aborted) { setServers(data); setUpdatedAt(new Date().toISOString()); setError('') }
      } catch (err) { if (!controller.signal.aborted) setError(errorMessage(err)) }
      finally { busy = false; if (!controller.signal.aborted) setLoading(false) }
    }
    void load()
    const timer = autoRefresh ? window.setInterval(() => void load(), 30000) : undefined
    return () => { controller.abort(); if (timer) clearInterval(timer) }
  }, [active, token, demo, autoRefresh, revision])

  async function login(values: { username: string; password: string }) {
    setLoginBusy(true)
    try {
      const { data } = await api.post<LoginResponse>('/api/auth/login', values)
      localStorage.setItem(TOKEN_KEY, data.accessToken); setToken(data.accessToken); setUsername(values.username); setDemo(false)
      navigate('overview')
    } catch (err) { message.error(errorMessage(err)) }
    finally { setLoginBusy(false) }
  }
  async function issueToken() {
    setConfirmToken(false); setTokenBusy(true)
    try { setEnrollment((await api.post('/api/agent/enrollment-tokens')).data); message.success('已签发注册令牌') }
    catch (err) { message.error(errorMessage(err)) }
    finally { setTokenBusy(false) }
  }
  async function copy(value: string) {
    try { await navigator.clipboard.writeText(value); message.success('已复制') }
    catch { message.error('复制失败，请手动选择文本复制') }
  }

  if (!active) return <main className="login-page"><section className="login-panel">
    <div className="login-brand"><SafetyCertificateOutlined /><span>AIOps<span className="brand-caption">运维平台</span></span></div>
    <h1>登录工作台</h1><p className="muted">连接你的基础设施，查看实时运行状态</p>
    <Form layout="vertical" requiredMark={false} onFinish={login}>
      <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}><Input prefix={<UserOutlined />} autoComplete="username" size="large" /></Form.Item>
      <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}><Input.Password prefix={<KeyOutlined />} autoComplete="current-password" size="large" /></Form.Item>
      <Button type="primary" block size="large" htmlType="submit" loading={loginBusy}>登录</Button>
    </Form>
    <div className="login-divider"><span>或</span></div>
    <Button block size="large" icon={<ExperimentOutlined />} onClick={() => { setDemo(true); setUsername('演示访客'); navigate('overview') }}>进入演示环境</Button>
    <p className="login-note">演示环境使用示例数据，无需连接数据库</p>
  </section><span className="login-footer">AIOps Agent Platform · Infrastructure Operations</span></main>

  const online = servers.filter(s => s.status === 'ONLINE').length
  const average = (key: 'cpuUsage' | 'memoryUsage') => {
    const available = servers.filter(s => s.status === 'ONLINE' && s[key] != null)
    return available.length ? available.reduce((sum, s) => sum + s[key]!, 0) / available.length : null
  }
  const attention = servers.filter(s => s.status !== 'ONLINE' || [s.cpuUsage, s.memoryUsage, s.diskUsage].some(v => v != null && v >= 80))
  const titles: Record<string, string> = { overview: '运行总览', servers: selectedId ? '服务器详情' : '服务器资源', access: 'Agent 接入', settings: '账户设置' }
  const filtered = servers.filter(s => (filter === 'ALL' || s.status === filter) && `${s.name} ${s.hostname} ${s.ip || ''} ${s.osName || ''}`.toLowerCase().includes(search.toLowerCase()))

  const columns = [
    { title: '服务器', dataIndex: 'name', key: 'name', sorter: (a: Server, b: Server) => a.name.localeCompare(b.name), render: (_: string, s: Server) => <button className="host-link" onClick={() => navigate(`servers/${s.id}`)}><span className="host-symbol"><DesktopOutlined /></span><span><strong>{s.name}</strong><small>{s.hostname}</small></span></button> },
    { title: '状态', key: 'status', render: (_: unknown, s: Server) => status(s) },
    { title: 'IP 地址', dataIndex: 'ip', render: (v: string) => <span className="mono">{v || '—'}</span> },
    ...(['cpuUsage', 'memoryUsage', 'diskUsage'] as const).map((key, i) => ({ title: ['CPU', '内存', '磁盘'][i], key, sorter: (a: Server, b: Server) => (a[key] ?? -1) - (b[key] ?? -1), render: (_: unknown, s: Server) => <Usage value={s[key]} stale={s.status !== 'ONLINE'} /> })),
    { title: '最后心跳', dataIndex: 'lastSeenAt', render: (v: string) => <span className="muted compact">{time(v)}</span> },
    { title: '', key: 'action', render: (_: unknown, s: Server) => <Tooltip title="查看详情"><Button icon={<ArrowRightOutlined />} aria-label={`查看 ${s.name}`} onClick={() => navigate(`servers/${s.id}`)} /></Tooltip> },
  ]

  return <div className="console-shell">
    <aside className="navigation">
      <a className="brand" href="#overview"><SafetyCertificateOutlined /><span>AIOps<small>运维工作台</small></span></a>
      <div className="nav-caption">工作空间</div>
      <Menu selectedKeys={[page]} onClick={({ key }) => navigate(key)} items={[
        { key: 'overview', icon: <DashboardOutlined />, label: '运行总览' },
        { key: 'servers', icon: <DesktopOutlined />, label: '服务器资源' },
        { key: 'access', icon: <ApiOutlined />, label: 'Agent 接入' },
        { key: 'settings', icon: <SettingOutlined />, label: '账户设置' },
      ]} />
      <div className="nav-footer"><span className={`connection-dot ${error ? 'bad' : ''}`} />{demo ? '演示工作空间' : error ? '连接异常' : '本地工作空间'}<small>Agent Platform / V0.1</small></div>
    </aside>
    <div className="workspace">
      <header className="topbar"><span className="breadcrumb">工作空间 <span>/</span> {titles[page] || '运行总览'}</span><Space wrap size={12}>{demo && <Tag color="gold">演示数据</Tag>}<span className="user-label"><UserOutlined /> {username || '管理员'}</span><Tooltip title="退出登录"><Button icon={<LogoutOutlined />} aria-label="退出登录" onClick={signOut} /></Tooltip></Space></header>
      <main className="content">
        {demo && <Alert className="demo-banner" type="info" showIcon icon={<ExperimentOutlined />} message="演示环境 · 当前为示例数据" action={<Button size="small" onClick={signOut}>登录真实环境</Button>} />}
        <div className="page-heading"><div><div className="eyebrow">INFRASTRUCTURE / {page.toUpperCase()}</div><h1>{titles[page] || '运行总览'}</h1><p className="muted">{page === 'overview' ? '基础设施状态与资源使用情况' : page === 'servers' ? '主机资产、运行状态与历史指标' : page === 'access' ? '注册节点并连接 Python Agent' : '管理当前账户与登录凭证'}</p></div><Space wrap>
          {['overview', 'servers'].includes(page) && <><label className="refresh-control"><Switch size="small" checked={autoRefresh} onChange={setAutoRefresh} />自动刷新</label><Tooltip title="刷新数据"><Button icon={<ReloadOutlined />} loading={loading} aria-label="刷新数据" onClick={() => setRevision(x => x + 1)} /></Tooltip></>}
          {page !== 'settings' && page !== 'access' && <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('access')}>接入服务器</Button>}
        </Space></div>
        {error && <Alert type="error" showIcon className="error-banner" message="数据加载失败" description={error} action={<Button onClick={() => setRevision(x => x + 1)}>重试</Button>} />}

        {page === 'overview' && <>
          <div className="summary-grid"><Summary label="服务器总量" value={servers.length} suffix="台" icon={<DesktopOutlined />} note={`${servers.length - online} 台离线`} /><Summary label="在线服务器" value={online} suffix="台" icon={<CheckCircleOutlined />} note={servers.length ? `在线率 ${(online / servers.length * 100).toFixed(0)}%` : '等待 Agent 接入'} /><Summary label="平均 CPU" value={average('cpuUsage')?.toFixed(1) ?? '—'} suffix="%" icon={<DashboardOutlined />} note="在线主机最新采样" /><Summary label="平均内存" value={average('memoryUsage')?.toFixed(1) ?? '—'} suffix="%" icon={<DatabaseOutlined />} note="在线主机最新采样" /></div>
          <div className="section-heading"><h2>资源概况</h2><Button type="link" onClick={() => navigate('servers')}>全部服务器 <ArrowRightOutlined /></Button></div>
          <Table rowKey="id" columns={columns.filter(c => !['action', 'diskUsage'].includes(c.key || ''))} dataSource={servers.slice(0, 5)} loading={loading} pagination={false} scroll={{ x: 800 }} locale={{ emptyText: <NoHosts onConnect={() => navigate('access')} /> }} />
          <div className="overview-lower"><section><div className="section-heading"><h2>CPU 使用率排行</h2><span className="muted compact">在线主机</span></div>{servers.filter(s => s.status === 'ONLINE' && s.cpuUsage != null).sort((a, b) => b.cpuUsage! - a.cpuUsage!).slice(0, 5).map((s, i) => <button key={s.id} className="rank-row" onClick={() => navigate(`servers/${s.id}`)}><span className="rank-number">0{i + 1}</span><span>{s.name}</span><Progress percent={s.cpuUsage!} showInfo={false} strokeColor={s.cpuUsage! >= 80 ? '#d39429' : '#087f78'} /><strong>{percent(s.cpuUsage)}</strong></button>)}{!servers.some(s => s.status === 'ONLINE' && s.cpuUsage != null) && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无在线采样" />}</section><section><div className="section-heading"><h2>需要关注 <Tag>{attention.length}</Tag></h2><Tooltip title="离线主机，或任一资源使用率达到 80%"><span className="muted compact">状态与资源检查</span></Tooltip></div>{attention.map(s => <button className="attention-row" key={s.id} onClick={() => navigate(`servers/${s.id}`)}><span className={`connection-dot ${s.status === 'ONLINE' ? 'warm' : 'bad'}`} /><span><strong>{s.name}</strong><small>{s.status !== 'ONLINE' ? `主机离线 · 最后心跳 ${time(s.lastSeenAt)}` : `资源使用率较高 · CPU ${percent(s.cpuUsage)} / 内存 ${percent(s.memoryUsage)} / 磁盘 ${percent(s.diskUsage)}`}</small></span><ArrowRightOutlined /></button>)}{!attention.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无需要关注的主机" />}</section></div>
        </>}

        {page === 'servers' && !selectedId && <><div className="table-toolbar"><Space wrap><Input prefix={<SearchOutlined />} placeholder="搜索名称、IP、操作系统" value={search} allowClear onChange={e => setSearch(e.target.value)} className="search-input" aria-label="搜索服务器" /><Select aria-label="服务器状态" value={filter} onChange={setFilter} options={[{ value: 'ALL', label: '全部状态' }, { value: 'ONLINE', label: '在线' }, { value: 'OFFLINE', label: '离线' }]} /></Space><span className="muted compact">共 {filtered.length} 台服务器</span></div><Table rowKey="id" columns={columns} dataSource={filtered} loading={loading} scroll={{ x: 1050 }} pagination={{ defaultPageSize: 10, showSizeChanger: true, hideOnSinglePage: false }} locale={{ emptyText: servers.length ? <Empty description="没有匹配的服务器" /> : <NoHosts onConnect={() => navigate('access')} /> }} /></>}
        {page === 'servers' && selectedId && (selected ? <ServerDetail server={selected} demo={demo} revision={revision} autoRefresh={autoRefresh} onBack={() => navigate('servers')} /> : <Spin spinning={loading}><Empty description={error ? '暂时无法加载服务器' : '服务器不存在或尚未接入'}><Button onClick={() => navigate('servers')}>返回列表</Button></Empty></Spin>)}
        {page === 'access' && <AccessPanel demo={demo} enrollment={enrollment} busy={tokenBusy} onCreate={() => enrollment ? setConfirmToken(true) : void issueToken()} onCopy={copy} servers={servers} onSelect={id => navigate(`servers/${id}`)} />}
        {page === 'settings' && <Account username={username} demo={demo} onChanged={signOut} />}
        {!['overview', 'servers', 'access', 'settings'].includes(page) && <Empty description="页面不存在"><Button onClick={() => navigate('overview')}>返回总览</Button></Empty>}
        <footer className="content-footer"><span>AIOps Agent Platform</span><span>{updatedAt ? `数据更新于 ${time(updatedAt)}` : '等待首次同步'}{autoRefresh && ' · 每 30 秒刷新'}</span></footer>
      </main>
    </div>
    <Modal open={confirmToken} title="签发新的注册令牌？" okText="签发新令牌" cancelText="取消" onOk={() => void issueToken()} onCancel={() => setConfirmToken(false)}>当前令牌将不再显示，请确认已经保存。已签发的令牌在过期或被使用前仍然有效。</Modal>
  </div>
}

function Summary({ label, value, suffix, icon, note }: { label: string; value: string | number; suffix: string; icon: React.ReactNode; note: string }) {
  return <div className="summary-item"><div><span className="muted">{label}</span><span className="summary-icon">{icon}</span></div><strong>{value}<small>{suffix}</small></strong><span className="muted compact">{note}</span></div>
}
function Usage({ value, stale }: { value: number | null; stale?: boolean }) {
  return <Tooltip title={stale ? '离线主机的最后一次采样' : undefined}><div className={`usage ${stale ? 'stale' : ''}`}><span>{percent(value)}</span><Progress percent={value ?? 0} showInfo={false} strokeColor={value != null && value >= 80 ? '#d39429' : '#087f78'} /></div></Tooltip>
}
function NoHosts({ onConnect }: { onConnect: () => void }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有服务器接入"><Button type="primary" icon={<PlusOutlined />} onClick={onConnect}>接入第一台服务器</Button></Empty>
}

function ServerDetail({ server, demo, revision, autoRefresh, onBack }: { server: Server; demo: boolean; revision: number; autoRefresh: boolean; onBack: () => void }) {
  const [hours, setHours] = useState(6)
  const [samples, setSamples] = useState<MetricSample[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const { message } = AntApp.useApp()
  useEffect(() => {
    const controller = new AbortController()
    let busy = false
    setSamples([])
    async function load() {
      if (busy) return
      busy = true; setLoading(true)
      const to = new Date()
      try {
        const data = demo ? demoHistory(server, hours) : (await api.get<MetricSample[]>(`/api/servers/${server.id}/metrics`, { signal: controller.signal, params: { from: new Date(to.getTime() - hours * 3600000).toISOString(), to: to.toISOString(), limit: 1440 } })).data
        if (!controller.signal.aborted) { setSamples(data); setError('') }
      } catch (err) { if (!controller.signal.aborted) setError(errorMessage(err)) }
      finally { busy = false; if (!controller.signal.aborted) setLoading(false) }
    }
    void load()
    const timer = autoRefresh ? setInterval(() => void load(), 30000) : undefined
    return () => { controller.abort(); if (timer) clearInterval(timer) }
  }, [server.id, demo, hours, revision, autoRefresh])
  const latest = samples[samples.length - 1]
  function exportCsv() {
    if (!samples.length) return
    const csv = '\ufeff时间,CPU(%),内存(%),磁盘(%)\r\n' + samples.map(s => `${s.collectedAt},${s.cpuPercent},${s.memoryPercent},${s.diskPercent}`).join('\r\n')
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8;' }))
    const link = document.createElement('a'); link.href = url; link.download = `metrics-${server.id}-${hours}h.csv`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    message.success('指标已导出')
  }
  return <>
    <div className="detail-heading"><Space wrap><Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回列表</Button><h2>{server.name}</h2>{status(server)}</Space><span className="mono muted">{server.ip || 'IP 未上报'}</span></div>
    {server.status !== 'ONLINE' && <Alert className="error-banner" type="warning" showIcon message={`服务器已离线，以下为历史采样。最后心跳：${time(server.lastSeenAt)}`} />}
    <div className="metric-strip">{(['cpuUsage', 'memoryUsage', 'diskUsage'] as const).map((key, i) => <div key={key}><span className="muted">{['CPU 使用率', '内存使用率', '磁盘使用率'][i]}</span><strong>{percent(server[key])}</strong><Progress percent={server[key] ?? 0} showInfo={false} strokeColor={['#087f78', '#4974c5', '#c58a28'][i]} /><small className="muted">{i === 0 ? `${server.cpuCores ?? '—'} 核 · ${server.architecture || '—'}` : i === 1 ? `内存总量 ${bytes(server.memoryBytes)}` : latest ? `${bytes(latest.diskUsedBytes)} / ${bytes(latest.diskTotalBytes)}` : '等待容量数据'}</small></div>)}</div>
    <div className="section-heading"><h2>资源趋势</h2><Space wrap><Segmented value={hours} onChange={v => setHours(Number(v))} options={[{ label: '1 小时', value: 1 }, { label: '6 小时', value: 6 }, { label: '24 小时', value: 24 }]} /><Tooltip title="导出指标 CSV"><Button icon={<DownloadOutlined />} disabled={!samples.length} aria-label="导出指标 CSV" onClick={exportCsv} /></Tooltip></Space></div>
    {error && <Alert type="error" message={error} className="error-banner" />}
    <div className="chart-surface"><TrendChart samples={samples} loading={loading} />{samples.length > 0 && <p className="muted compact">{samples.length} 条采样 · {time(samples[0].collectedAt)} 至 {time(samples[samples.length - 1].collectedAt)}{samples.length >= 1440 && ' · 已达单次查询上限，可缩短时间范围查看更新的采样'}</p>}</div>
    <Tabs items={[{ key: 'host', label: '主机信息', children: <Descriptions bordered column={{ xs: 1, sm: 2, lg: 3 }} items={[
      { key: 'hostname', label: '主机名', children: server.hostname }, { key: 'os', label: '操作系统', children: `${server.osName || '—'} ${server.osVersion || ''}` },
      { key: 'arch', label: '架构', children: server.architecture || '—' }, { key: 'cores', label: 'CPU 核心', children: server.cpuCores ?? '—' },
      { key: 'memory', label: '内存总量', children: bytes(server.memoryBytes) }, { key: 'id', label: '节点 ID', children: server.id },
      { key: 'ip', label: 'IP 地址', children: server.ip || '—' }, { key: 'heartbeat', label: '最后心跳', children: time(server.lastSeenAt) }, { key: 'sample', label: '最新采样', children: time(server.latestMetricAt) },
    ]} /> }, { key: 'samples', label: `采样记录 (${samples.length})`, children: <Table rowKey="id" size="small" dataSource={[...samples].reverse()} scroll={{ x: 600 }} pagination={{ pageSize: 10 }} columns={[
      { title: '采集时间', dataIndex: 'collectedAt', render: time }, ...(['cpuPercent', 'memoryPercent', 'diskPercent'] as const).map((key, i) => ({ title: ['CPU', '内存', '磁盘'][i], dataIndex: key, render: percent })),
    ]} /> }]} />
  </>
}

function AccessPanel({ demo, enrollment, busy, onCreate, onCopy, servers, onSelect }: { demo: boolean; enrollment: { token: string; expiresAt: string } | null; busy: boolean; onCreate: () => void; onCopy: (text: string) => void; servers: Server[]; onSelect: (id: number) => void }) {
  const [os, setOs] = useState('Windows')
  const [backend, setBackend] = useState(import.meta.env.VITE_AGENT_BACKEND_URL || import.meta.env.VITE_API_BASE_URL || `http://${location.hostname}:8080`)
  const expired = enrollment ? new Date(enrollment.expiresAt).getTime() < Date.now() : false
  const token = enrollment?.token || '<注册令牌>'
  const escapedBackend = backend.replace(/'/g, "'\"'\"'")
  const psBackend = backend.replace(/'/g, "''")
  const command = os === 'Windows' ? `cd agent\npy -m venv .venv\n.\\.venv\\Scripts\\python.exe -m pip install -r requirements.txt\n$env:AIOPS_BACKEND_URL = '${psBackend}'\n$env:AIOPS_ENROLLMENT_TOKEN = '${token}'\n.\\.venv\\Scripts\\python.exe -m aiops_agent` : `cd agent\npython3 -m venv .venv\n.venv/bin/python -m pip install -r requirements.txt\nexport AIOPS_BACKEND_URL='${escapedBackend}'\nexport AIOPS_ENROLLMENT_TOKEN='${token}'\n.venv/bin/python -m aiops_agent`
  return <>
    {demo && <Alert type="warning" showIcon className="error-banner" message="签发令牌需要登录真实环境。下方可预览接入步骤与命令。" />}
    <section className="access-step"><div className="step-marker">01</div><div className="step-body"><h2>签发注册令牌</h2><p className="muted">每台服务器使用一个令牌，有效期 30 分钟，成功注册后即失效。</p><Button type="primary" icon={<KeyOutlined />} loading={busy} disabled={demo} onClick={onCreate}>{enrollment ? '签发新令牌' : '生成注册令牌'}</Button>
      {enrollment && <div className="token-block"><Input.TextArea value={enrollment.token} readOnly autoSize aria-label="注册令牌" /><Space wrap><Button icon={<CopyOutlined />} onClick={() => onCopy(enrollment.token)}>复制令牌</Button><Tag color={expired ? 'error' : 'success'}>{expired ? '已过期' : '已签发'}</Tag><span className="muted compact">过期时间 {time(enrollment.expiresAt)}</span></Space></div>}</div></section>
    <section className="access-step"><div className="step-marker">02</div><div className="step-body"><h2>在目标服务器启动 Agent</h2><div className="command-toolbar"><Segmented options={['Windows', 'Linux / macOS']} value={os} onChange={v => setOs(String(v))} /><Tooltip title="复制启动命令"><Button icon={<CopyOutlined />} aria-label="复制启动命令" onClick={() => onCopy(command)} /></Tooltip></div><Form layout="vertical"><Form.Item label="后端地址"><Input value={backend} onChange={e => setBackend(e.target.value)} placeholder="http://服务器IP:8080" /></Form.Item></Form><pre className="command-block">{command}</pre><p className="muted compact">从项目根目录执行。目标主机需要 Python 3.10+，且能够访问上方后端地址。</p></div></section>
    <section className="access-step"><div className="step-marker">03</div><div className="step-body"><h2>确认节点在线</h2><p className="muted">注册成功后，节点会自动出现在服务器列表中。Agent 默认每 15 秒采集一次，工作台每 30 秒同步。</p>{servers.length ? <Table rowKey="id" size="small" dataSource={servers} pagination={{ pageSize: 5, hideOnSinglePage: true }} scroll={{ x: 450 }} columns={[{ title: '已接入节点', dataIndex: 'name', render: (name: string, s: Server) => <Button type="link" onClick={() => onSelect(s.id)}>{name}</Button> }, { title: '状态', render: (_: unknown, s: Server) => status(s) }, { title: '最后心跳', dataIndex: 'lastSeenAt', render: time }]} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="等待第一个 Agent 上线" />}</div></section>
  </>
}

function Account({ username, demo, onChanged }: { username: string; demo: boolean; onChanged: () => void }) {
  const { message } = AntApp.useApp()
  const [busy, setBusy] = useState(false)
  async function changePassword(values: { currentPassword: string; newPassword: string }) {
    setBusy(true)
    try { await api.post('/api/auth/change-password', { currentPassword: values.currentPassword, newPassword: values.newPassword }); message.success('密码已更新，请重新登录'); onChanged() }
    catch (err) { message.error(errorMessage(err)) }
    finally { setBusy(false) }
  }
  return <div className="settings-content"><div className="section-heading"><h2>当前账户</h2><Tag color={demo ? 'gold' : 'success'}>{demo ? '演示访客' : '已登录'}</Tag></div><Descriptions items={[{ key: 'name', label: '用户名', children: username || '—' }]} /><div className="section-heading"><h2>修改密码</h2></div>{demo && <Alert type="info" message="演示环境不支持修改账户密码" className="error-banner" />}<Form layout="vertical" onFinish={changePassword} disabled={demo} requiredMark={false}>
    <Form.Item label="当前密码" name="currentPassword" rules={[{ required: true, message: '请输入当前密码' }]}><Input.Password autoComplete="current-password" /></Form.Item>
    <Form.Item label="新密码" name="newPassword" rules={[{ required: true, message: '请输入新密码' }, { min: 12, max: 72, message: '密码长度须为 12 至 72 个字符' }]}><Input.Password autoComplete="new-password" /></Form.Item>
    <Form.Item label="确认新密码" name="confirmPassword" dependencies={['newPassword']} rules={[{ required: true, message: '请确认新密码' }, ({ getFieldValue }) => ({ validator: (_, value) => !value || value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')) })]}><Input.Password autoComplete="new-password" /></Form.Item>
    <Button type="primary" htmlType="submit" icon={<KeyOutlined />} loading={busy}>更新密码</Button>
  </Form></div>
}
