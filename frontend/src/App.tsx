import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Empty, Form, Input, Layout, List, message, Modal, Skeleton, Space, Statistic, Tag, Typography } from 'antd'
import { ArrowLeftOutlined, CopyOutlined, DesktopOutlined, KeyOutlined, ReloadOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import type { ECharts } from 'echarts/core'
import { api, errorMessage, type LoginResponse, type MetricSample, type Server } from './api'

const { Header, Content, Sider } = Layout
const { Title, Text } = Typography
const TOKEN_KEY = 'aiops.accessToken'

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY))
  const [servers, setServers] = useState<Server[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [samples, setSamples] = useState<MetricSample[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [loginBusy, setLoginBusy] = useState(false)
  const [enrollmentToken, setEnrollmentToken] = useState<{ token: string; expiresAt: string } | null>(null)
  const [tokenBusy, setTokenBusy] = useState(false)
  const [username, setUsername] = useState('')
  const [messageApi, contextHolder] = message.useMessage()
  const chartElement = useRef<HTMLDivElement>(null)

  const selected = servers.find((server) => server.id === selectedId) ?? null

  const signOut = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY)
    setToken(null)
    setServers([])
    setSamples([])
    setSelectedId(null)
    setUsername('')
  }, [])

  useEffect(() => {
    const onUnauthorized = () => signOut()
    window.addEventListener('aiops:unauthorized', onUnauthorized)
    return () => window.removeEventListener('aiops:unauthorized', onUnauthorized)
  }, [signOut])

  const loadServers = useCallback(async (keepSelection = true) => {
    setLoading(true)
    setError('')
    try {
      const { data } = await api.get<Server[]>('/api/servers')
      setServers(data)
      setSelectedId((current) => {
        if (keepSelection && current && data.some((server) => server.id === current)) return current
        return data[0]?.id ?? null
      })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }, [])

  const loadHistory = useCallback(async (id: number) => {
    const to = new Date()
    const from = new Date(to.getTime() - 6 * 60 * 60 * 1000)
    try {
      const { data } = await api.get<MetricSample[]>(`/api/servers/${id}/metrics`, {
        params: { from: from.toISOString(), to: to.toISOString(), limit: 500 },
      })
      setSamples(data)
    } catch (err) {
      setError(errorMessage(err))
      setSamples([])
    }
  }, [])

  useEffect(() => {
    if (token) void loadServers(false)
  }, [token, loadServers])

  useEffect(() => {
    if (!token) return
    void api.get<{ username: string }>('/api/auth/me').then(({ data }) => setUsername(data.username)).catch(() => undefined)
  }, [token])

  useEffect(() => {
    if (!token || selectedId === null) {
      setSamples([])
      return
    }
    void loadHistory(selectedId)
    const timer = window.setInterval(() => {
      void loadServers()
      void loadHistory(selectedId)
    }, 30000)
    return () => window.clearInterval(timer)
  }, [token, selectedId, loadHistory, loadServers])

  const chartOption = useMemo(() => ({
    color: ['#07877c', '#d68a24', '#566b8d'],
    tooltip: { trigger: 'axis' },
    legend: { top: 4, right: 8, textStyle: { color: '#626c78' } },
    grid: { left: 42, right: 20, top: 42, bottom: 34 },
    xAxis: { type: 'time', axisLabel: { color: '#7b8490' }, axisLine: { lineStyle: { color: '#d8dde2' } } },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: '#7b8490', formatter: '{value}%' }, splitLine: { lineStyle: { color: '#edf0f2' } } },
    series: [
      { name: 'CPU', type: 'line', smooth: true, showSymbol: false, data: samples.map((x) => [x.collectedAt, x.cpuPercent]) },
      { name: '内存', type: 'line', smooth: true, showSymbol: false, data: samples.map((x) => [x.collectedAt, x.memoryPercent]) },
      { name: '磁盘', type: 'line', smooth: true, showSymbol: false, data: samples.map((x) => [x.collectedAt, x.diskPercent]) },
    ],
  }), [samples])

  useEffect(() => {
    if (!selectedId || !samples.length || !chartElement.current) return
    let chart: ECharts | undefined
    let disposed = false
    void import('./chart').then(({ init }) => {
      if (disposed || !chartElement.current) return
      chart = init(chartElement.current)
      chart.setOption(chartOption)
    })
    const resize = () => chart?.resize()
    window.addEventListener('resize', resize)
    return () => {
      disposed = true
      window.removeEventListener('resize', resize)
      chart?.dispose()
    }
  }, [selectedId, samples.length, chartOption])

  async function login(values: { username: string; password: string }) {
    setLoginBusy(true)
    try {
      const { data } = await api.post<LoginResponse>('/api/auth/login', values)
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      setToken(data.accessToken)
      setUsername(values.username)
      messageApi.success('登录成功')
    } catch (err) {
      messageApi.error(errorMessage(err))
    } finally {
      setLoginBusy(false)
    }
  }

  async function createEnrollmentToken() {
    setTokenBusy(true)
    try {
      const { data } = await api.post<{ token: string; expiresAt: string }>('/api/agent/enrollment-tokens')
      setEnrollmentToken(data)
    } catch (err) {
      messageApi.error(errorMessage(err))
    } finally {
      setTokenBusy(false)
    }
  }

  if (!token) {
    return <>
      {contextHolder}
      <main className="login-page">
        <section className="login-panel">
          <div className="brand-mark"><SafetyCertificateOutlined /></div>
          <Text className="eyebrow">AIOPS · SERVER OPERATIONS</Text>
          <Title level={2}>登录运维平台</Title>
          <Text type="secondary">使用管理员账户继续</Text>
          <Form layout="vertical" onFinish={login} requiredMark={false} className="login-form">
            <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input size="large" prefix={<UserOutlined />} autoComplete="username" />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password size="large" autoComplete="current-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" block loading={loginBusy}>登录</Button>
          </Form>
        </section>
      </main>
    </>
  }

  const onlineCount = servers.filter((server) => server.status === 'ONLINE').length
  return <Layout className="app-shell">
    {contextHolder}
    <Header className="topbar">
      <div className="brand"><span className="brand-mark small"><SafetyCertificateOutlined /></span><span>AIOps <Text type="secondary">运维平台</Text></span></div>
      <Space>
        <Button icon={<KeyOutlined />} onClick={() => void createEnrollmentToken()} loading={tokenBusy}>新建 Agent 令牌</Button>
        <Text className="user-label"><UserOutlined /> {username || '已登录'}</Text>
        <Button icon={<ArrowLeftOutlined />} onClick={signOut}>退出</Button>
      </Space>
    </Header>
    <Modal title="Agent 注册令牌" open={!!enrollmentToken} onCancel={() => setEnrollmentToken(null)} footer={null} destroyOnHidden>
      {enrollmentToken && <Space direction="vertical" size={12} className="token-dialog">
        <Alert type="warning" showIcon message="令牌只显示这一次，30 分钟后失效且只能注册一个 Agent。" />
        <Input value={enrollmentToken.token} readOnly addonAfter={<Button type="text" icon={<CopyOutlined />} aria-label="复制注册令牌" title="复制注册令牌" onClick={() => { void navigator.clipboard.writeText(enrollmentToken.token); messageApi.success('已复制') }} />} />
        <Text type="secondary">过期时间：{formatTime(enrollmentToken.expiresAt)}</Text>
      </Space>}
    </Modal>
    <Layout className="body-layout">
      <Sider width={300} theme="light" className="server-sider">
        <div className="sider-heading">
          <div><Text className="eyebrow">资源</Text><Title level={4}>服务器</Title></div>
          <Button aria-label="刷新服务器" title="刷新服务器" icon={<ReloadOutlined />} onClick={() => void loadServers()} loading={loading} />
        </div>
        <div className="server-count"><span>{servers.length} 台主机</span><span className="online-count">{onlineCount} 在线</span></div>
        {error && <Alert className="sider-error" type="error" showIcon message={error} />}
        {loading && servers.length === 0 ? <Skeleton active paragraph={{ rows: 4 }} /> : <List
          className="server-list"
          dataSource={servers}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已接入服务器" /> }}
          renderItem={(server) => <List.Item className={`server-item ${selectedId === server.id ? 'selected' : ''}`} onClick={() => setSelectedId(server.id)}>
            <div className="server-icon"><DesktopOutlined /></div>
            <div className="server-meta"><Text strong>{server.name}</Text><Text type="secondary">{server.hostname}</Text></div>
            <span className={`status-dot ${server.status === 'ONLINE' ? 'online' : ''}`} title={server.status} />
          </List.Item>}
        />}
      </Sider>
      <Content className="content-area">
        {!selected ? <div className="empty-detail"><Empty description="选择服务器以查看监控数据" /></div> : <>
          <div className="page-heading">
            <div><Text className="eyebrow">服务器详情</Text><Title level={3}>{selected.name}</Title><Text type="secondary">{selected.hostname} · {selected.ip ?? 'IP 未上报'}</Text></div>
            <Tag color={selected.status === 'ONLINE' ? 'success' : 'default'}>{selected.status === 'ONLINE' ? '在线' : '离线'}</Tag>
          </div>
          {error && <Alert type="warning" showIcon message={error} closable onClose={() => setError('')} />}
          <div className="stats-grid">
            <Card><Statistic title="CPU 使用率" value={selected.cpuUsage ?? 0} precision={1} suffix="%" valueStyle={{ color: '#07877c' }} /></Card>
            <Card><Statistic title="内存使用率" value={selected.memoryUsage ?? 0} precision={1} suffix="%" valueStyle={{ color: '#d68a24' }} /></Card>
            <Card><Statistic title="磁盘使用率" value={selected.diskUsage ?? 0} precision={1} suffix="%" valueStyle={{ color: '#566b8d' }} /></Card>
          </div>
          <Card className="chart-card" title="资源趋势" extra={<Text type="secondary">最近 6 小时</Text>}>
            {samples.length ? <div ref={chartElement} className="trend-chart" /> : <div className="chart-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无历史采样" /></div>}
          </Card>
          <Card className="detail-card" title="主机信息">
            <div className="detail-grid">
              <Info label="操作系统" value={[selected.osName, selected.osVersion].filter(Boolean).join(' ') || '未上报'} />
              <Info label="架构" value={selected.architecture || '未上报'} />
              <Info label="CPU 核心" value={selected.cpuCores ? `${selected.cpuCores} 核` : '未上报'} />
              <Info label="内存总量" value={selected.memoryBytes ? formatBytes(selected.memoryBytes) : '未上报'} />
              <Info label="最后心跳" value={selected.lastSeenAt ? formatTime(selected.lastSeenAt) : '尚无心跳'} />
              <Info label="最新采样" value={selected.latestMetricAt ? formatTime(selected.latestMetricAt) : '暂无数据'} />
            </div>
          </Card>
        </>}
      </Content>
    </Layout>
  </Layout>
}

function Info({ label, value }: { label: string; value: string }) {
  return <div className="info-item"><Text type="secondary">{label}</Text><Text>{value}</Text></div>
}

function formatBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1 }
  return `${value.toFixed(1)} ${units[unit]}`
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value))
}
