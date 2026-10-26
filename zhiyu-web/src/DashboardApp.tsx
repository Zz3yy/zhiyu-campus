import { useCallback, useEffect, useState } from 'react'
import {
  Activity,
  CalendarCheck2,
  Check,
  ChevronRight,
  CircleHelp,
  Cloud,
  CloudSun,
  HeartPulse,
  Leaf,
  LoaderCircle,
  MessageCircle,
  Plus,
  ShieldCheck,
  SkipForward,
  Sparkles,
  Sun,
  TrendingUp,
  Wind,
} from 'lucide-react'
import {
  completeTask,
  createTask,
  ensureAnonymousUser,
  getTrendSummary,
  listCheckins,
  listTasks,
  saveCheckin,
  skipTask,
  type AnonymousUser,
  type Checkin,
  type Task,
  type TrendSummary,
} from './api'

type NavKey = 'home' | 'checkin' | 'chat' | 'tasks' | 'trends'
type Notice = { kind: 'error' | 'success'; message: string } | null

const navItems: Array<{ key: NavKey; label: string; icon: typeof HeartPulse }> = [
  { key: 'home', label: '今日状态', icon: HeartPulse },
  { key: 'checkin', label: '情绪记录', icon: Sun },
  { key: 'chat', label: '找知愈聊聊', icon: MessageCircle },
  { key: 'tasks', label: '行动计划', icon: CalendarCheck2 },
  { key: 'trends', label: '我的趋势', icon: TrendingUp },
]

const moodLabels = ['很低落', '有些低落', '还不错', '挺好的', '状态很好']
const scoreLabels = ['很少', '较少', '一般', '较多', '很多']

function localDate() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function dateLabel(value: string) {
  const [, month, day] = value.split('-')
  return `${Number(month)} 月 ${Number(day)} 日`
}

function averageLabel(value: number) {
  return value > 0 ? value.toFixed(1) : '—'
}

export function DashboardApp() {
  const [active, setActive] = useState<NavKey>('home')
  const [user, setUser] = useState<AnonymousUser | null>(null)
  const [checkins, setCheckins] = useState<Checkin[]>([])
  const [tasks, setTasks] = useState<Task[]>([])
  const [trend, setTrend] = useState<TrendSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [notice, setNotice] = useState<Notice>(null)

  const refresh = useCallback(async () => {
    setRefreshing(true)
    try {
      const [nextCheckins, nextTasks, nextTrend] = await Promise.all([listCheckins(), listTasks(), getTrendSummary()])
      setCheckins(nextCheckins)
      setTasks(nextTasks)
      setTrend(nextTrend)
    } catch (error) {
      setNotice({ kind: 'error', message: error instanceof Error ? error.message : '暂时无法读取数据，请稍后再试。' })
    } finally {
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    ensureAnonymousUser()
      .then((nextUser) => {
        if (cancelled) return
        setUser(nextUser)
        return refresh()
      })
      .catch((error: unknown) => {
        if (!cancelled) setNotice({ kind: 'error', message: error instanceof Error ? error.message : '暂时无法进入匿名模式。' })
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [refresh])

  const latestCheckin = checkins[0] ?? null
  const navigate = (key: NavKey) => {
    setNotice(null)
    setActive(key)
  }

  if (loading) {
    return <div className="loading-screen"><LoaderCircle className="spin" size={25} /><span>正在准备你的空间…</span></div>
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">知</span><span>知愈校园</span></div>
        <p className="brand-subtitle">给自己一个被听见的空间</p>
        <nav aria-label="主导航">
          {navItems.map(({ key, label, icon: Icon }) => (
            <button key={key} className={`nav-item ${active === key ? 'is-active' : ''}`} onClick={() => navigate(key)}>
              <Icon size={18} strokeWidth={1.8} />
              <span>{label}</span>
            </button>
          ))}
        </nav>
        <div className="sidebar-footer"><ShieldCheck size={16} /><span>匿名模式已开启</span></div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div><p className="eyebrow">{dateLabel(localDate())} · {user?.nickname ?? '同学'}</p><h1>今天也照顾一下自己。</h1></div>
          <button className="icon-button" title="帮助与求助资源" aria-label="帮助与求助资源"><CircleHelp size={20} /></button>
        </header>

        {notice && <div className={`notice notice-${notice.kind}`} role="status">{notice.message}<button onClick={() => setNotice(null)} aria-label="关闭提示">×</button></div>}
        {refreshing && <div className="refresh-indicator"><LoaderCircle className="spin" size={14} /> 正在更新</div>}

        {active === 'home' && <Home latestCheckin={latestCheckin} trend={trend} tasks={tasks} navigate={navigate} />}
        {active === 'checkin' && <Checkin latestCheckin={latestCheckin} onSaved={async () => { await refresh(); navigate('home') }} setNotice={setNotice} />}
        {active === 'chat' && <Chat setNotice={setNotice} />}
        {active === 'tasks' && <Tasks tasks={tasks} onChanged={refresh} setNotice={setNotice} />}
        {active === 'trends' && <Trends checkins={checkins} trend={trend} />}
      </main>
    </div>
  )
}

function Home({ latestCheckin, trend, tasks, navigate }: { latestCheckin: Checkin | null; trend: TrendSummary | null; tasks: Task[]; navigate: (key: NavKey) => void }) {
  const todoTasks = tasks.filter((task) => task.status === 'TODO')
  const moodText = latestCheckin ? moodLabels[latestCheckin.moodScore - 1] : '还没有记录'

  return <div className="content-grid">
    <section className="hero-panel">
      <div>
        <span className="section-kicker">今日状态</span>
        <h2>{latestCheckin ? '谢谢你，愿意留意此刻。' : '慢一点，也是在前进。'}</h2>
        <p>{latestCheckin ? `你今天记录为“${moodText}”。如果愿意，可以再选一个容易开始的小行动。` : '花一分钟留意此刻的自己，知愈会帮你把感受整理成一个可以开始的小行动。'}</p>
        <button className="primary-button" onClick={() => navigate(latestCheckin ? 'tasks' : 'checkin')}>
          {latestCheckin ? '看看今天的行动' : '记录现在的状态'} <ChevronRight size={16} />
        </button>
      </div>
      <div className="hero-orbit"><HeartPulse size={46} strokeWidth={1.2} /><span>今天的心情<br /><strong>{moodText}</strong></span></div>
    </section>

    <section className="section-block"><div className="section-heading"><div><span className="section-kicker">小小行动</span><h3>今天适合做什么</h3></div><button className="text-button" onClick={() => navigate('tasks')}>查看全部 <ChevronRight size={15} /></button></div>
      {todoTasks.length === 0 ? <EmptyState icon={<Leaf size={19} />} title="还没有待完成的行动" action="去添加一个" onClick={() => navigate('tasks')} /> : <div className="activity-list">{todoTasks.slice(0, 3).map((task) => <ActivityCard key={task.id} task={task} onClick={() => navigate('tasks')} />)}</div>}
    </section>

    <section className="insight-row">
      <div className="insight-card"><span className="section-kicker">连续记录</span><strong>{trend?.streakDays ?? 0} 天</strong><span>{trend?.checkinCount ? '保持你的节奏' : '记录后会显示'}</span></div>
      <div className="insight-card"><span className="section-kicker">本周完成</span><strong>{trend?.completedTaskCount ?? 0} / {trend?.totalTaskCount ?? 0}</strong><span>个小行动</span></div>
      <div className="insight-card insight-card-accent"><Sparkles size={20} /><span>{todoTasks.length ? `还有 ${todoTasks.length} 个行动可以慢慢完成。` : '今天不需要解决所有事情，先选择一件容易开始的。'}</span></div>
    </section>
  </div>
}

function Checkin({ latestCheckin, onSaved, setNotice }: { latestCheckin: Checkin | null; onSaved: () => Promise<void>; setNotice: (notice: Notice) => void }) {
  const [mood, setMood] = useState<number | null>(latestCheckin?.moodScore ?? null)
  const [stress, setStress] = useState<number | null>(latestCheckin?.stressScore ?? null)
  const [sleepHours, setSleepHours] = useState(latestCheckin?.sleepHours?.toString() ?? '')
  const [note, setNote] = useState(latestCheckin?.note ?? '')
  const [saving, setSaving] = useState(false)

  async function submit() {
    if (!mood || !stress) {
      setNotice({ kind: 'error', message: '请先选择今天的心情和压力程度。' })
      return
    }
    setSaving(true)
    try {
      await saveCheckin({ recordDate: localDate(), moodScore: mood, stressScore: stress, sleepHours: sleepHours ? Number(sleepHours) : null, sleepQuality: null, energyScore: null, note: note.trim() || null })
      setNotice({ kind: 'success', message: '今天的状态已经保存。' })
      await onSaved()
    } catch (error) {
      setNotice({ kind: 'error', message: error instanceof Error ? error.message : '保存失败，请稍后再试。' })
    } finally {
      setSaving(false)
    }
  }

  return <section className="form-panel"><div className="section-kicker">情绪记录 · 约 1 分钟</div><h2>此刻的你，感觉怎么样？</h2><p className="muted">没有正确答案，只记录真实感受就好。{latestCheckin ? ' 今天已有记录，保存会更新它。' : ''}</p>
    <ScorePicker label="心情" values={moodLabels} value={mood} onChange={setMood} icons={[Cloud, Cloud, CloudSun, Sun, Sun]} />
    <ScorePicker label="压力" values={scoreLabels} value={stress} onChange={setStress} icons={[Leaf, Leaf, Wind, Activity, Sparkles]} />
    <label className="field-label" htmlFor="sleep-hours">昨晚睡了多久？<span>可选</span></label><div className="input-with-suffix"><input id="sleep-hours" className="number-input" type="number" min="0" max="24" step="0.5" value={sleepHours} onChange={(event) => setSleepHours(event.target.value)} placeholder="例如 7" /><span>小时</span></div>
    <label className="field-label" htmlFor="note">想补充些什么吗？<span>可选</span></label><textarea id="note" className="note-input" value={note} onChange={(event) => setNote(event.target.value)} placeholder="例如：今天有点担心明天的答辩……" />
    <div className="form-footer"><span className="privacy-note"><ShieldCheck size={15} />仅自己可见</span><button className="primary-button" disabled={saving} onClick={submit}>{saving ? '保存中…' : '保存记录'} <ChevronRight size={16} /></button></div>
  </section>
}

function ScorePicker({ label, values, value, onChange, icons }: { label: string; values: string[]; value: number | null; onChange: (value: number) => void; icons: Array<typeof Cloud> }) {
  return <div className="score-group"><div className="score-group-label">{label}</div><div className="mood-scale" role="radiogroup" aria-label={label}>{values.map((text, index) => { const Icon = icons[index]; const score = index + 1; return <button key={text} type="button" className={`mood-option ${value === score ? 'selected' : ''}`} onClick={() => onChange(score)} role="radio" aria-checked={value === score}><Icon size={23} strokeWidth={1.5} /><small>{text}</small></button> })}</div></div>
}

function Chat({ setNotice }: { setNotice: (notice: Notice) => void }) {
  const [messages, setMessages] = useState<Array<{ role: 'assistant' | 'user'; text: string }>>([{ role: 'assistant', text: '你好，我在这里听你说。最近有什么事情一直占据你的心思吗？' }])
  const [draft, setDraft] = useState('')

  function send() {
    const text = draft.trim()
    if (!text) return
    setMessages((current) => [...current, { role: 'user', text }])
    setDraft('')
    setMessages((current) => [...current, { role: 'assistant', text: '我已经收到你的分享。AI 支持对话接口正在接入中，你也可以先把这份感受记录下来，或从一个三分钟的小行动开始。' }])
    setNotice({ kind: 'success', message: '当前为演示模式，消息只保存在本页。' })
  }

  return <section className="chat-panel"><div className="chat-header"><div><div className="section-kicker">AI 支持对话</div><h2>找知愈聊聊</h2></div><span className="status-dot">演示模式</span></div><div className="chat-messages">{messages.map((message, index) => <div key={`${message.role}-${index}`} className={`message ${message.role}`}>{message.text}</div>)}</div><div className="chat-composer"><input value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') send() }} placeholder="说说你现在的感受……" aria-label="输入你的感受" /><button className="primary-button" onClick={send}>发送 <ChevronRight size={16} /></button></div><p className="chat-disclaimer">知愈提供基础心理支持，不替代专业诊断或紧急救援。</p></section>
}

function Tasks({ tasks, onChanged, setNotice }: { tasks: Task[]; onChanged: () => Promise<void>; setNotice: (notice: Notice) => void }) {
  const [draft, setDraft] = useState('')
  const [busyId, setBusyId] = useState<number | null>(null)

  async function addTask() {
    const title = draft.trim()
    if (!title) return
    try {
      await createTask(title)
      setDraft('')
      setNotice({ kind: 'success', message: '行动已加入今天的计划。' })
      await onChanged()
    } catch (error) {
      setNotice({ kind: 'error', message: error instanceof Error ? error.message : '添加失败，请稍后再试。' })
    }
  }

  async function updateTask(id: number, action: 'complete' | 'skip') {
    setBusyId(id)
    try {
      if (action === 'complete') await completeTask(id)
      else await skipTask(id)
      await onChanged()
    } catch (error) {
      setNotice({ kind: 'error', message: error instanceof Error ? error.message : '更新失败，请稍后再试。' })
    } finally {
      setBusyId(null)
    }
  }

  return <section className="form-panel"><div className="section-kicker">行动计划 · 今天</div><h2>给自己安排几个小动作</h2><p className="muted">完成不是义务，照顾自己的过程本身就值得被看见。</p><div className="task-create"><input value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') void addTask() }} placeholder="添加一个容易开始的行动" aria-label="行动名称" /><button className="icon-button filled" onClick={() => void addTask()} title="添加行动" aria-label="添加行动"><Plus size={18} /></button></div>
    {tasks.length === 0 ? <EmptyState icon={<CalendarCheck2 size={19} />} title="今天还没有行动" action="先添加一个" onClick={() => document.querySelector<HTMLInputElement>('.task-create input')?.focus()} /> : <div className="task-list">{tasks.map((task) => <TaskRow key={task.id} task={task} busy={busyId === task.id} onUpdate={updateTask} />)}</div>}
  </section>
}

function TaskRow({ task, busy, onUpdate }: { task: Task; busy: boolean; onUpdate: (id: number, action: 'complete' | 'skip') => Promise<void> }) {
  const done = task.status === 'DONE'
  const skipped = task.status === 'SKIPPED'
  return <div className={`task-item ${done ? 'is-done' : ''} ${skipped ? 'is-skipped' : ''}`}><span className={`task-status task-status-${task.status.toLowerCase()}`}>{done ? <Check size={14} /> : skipped ? <SkipForward size={14} /> : <span />}</span><span className="task-copy"><strong>{task.title}</strong><small>{done ? '已完成' : skipped ? '已跳过' : task.plannedAt ? new Date(task.plannedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '今天任意时间'}</small></span>{task.status === 'TODO' && <span className="task-actions"><button disabled={busy} className="task-action" onClick={() => void onUpdate(task.id, 'complete')} title="完成" aria-label={`完成：${task.title}`}><Check size={16} /></button><button disabled={busy} className="task-action" onClick={() => void onUpdate(task.id, 'skip')} title="跳过" aria-label={`跳过：${task.title}`}><SkipForward size={16} /></button></span>}</div>
}

function Trends({ checkins, trend }: { checkins: Checkin[]; trend: TrendSummary | null }) {
  return <section className="form-panel trends-panel"><div className="section-kicker">我的趋势 · 近 7 天</div><h2>看见自己的节奏</h2><p className="muted">趋势只用于帮助你观察变化，不代表任何诊断结论。</p><div className="trend-summary"><div><span>记录天数</span><strong>{trend?.checkinCount ?? 0}</strong></div><div><span>平均心情</span><strong>{averageLabel(trend?.averageMood ?? 0)}</strong></div><div><span>平均压力</span><strong>{averageLabel(trend?.averageStress ?? 0)}</strong></div><div><span>平均睡眠</span><strong>{trend?.averageSleepHours ? `${trend.averageSleepHours.toFixed(1)}h` : '—'}</strong></div></div><div className="trend-list">{checkins.length === 0 ? <EmptyState icon={<TrendingUp size={19} />} title="完成一次记录后，这里会出现趋势" /> : checkins.slice(0, 7).map((checkin) => <div className="trend-row" key={checkin.id}><span>{dateLabel(checkin.recordDate)}</span><span className="trend-bar"><i style={{ width: `${checkin.moodScore * 20}%` }} /></span><strong>{moodLabels[checkin.moodScore - 1]}</strong></div>)}</div></section>
}

function ActivityCard({ task, onClick }: { task: Task; onClick: () => void }) {
  return <button className="activity-card" onClick={onClick}><span className="activity-icon"><Wind size={19} /></span><span className="activity-copy"><strong>{task.title}</strong><small>{task.plannedAt ? '计划中的行动' : '今天任意时间'}</small></span><ChevronRight size={17} /></button>
}

function EmptyState({ icon, title, action, onClick }: { icon: React.ReactNode; title: string; action?: string; onClick?: () => void }) {
  return <div className="empty-state"><span className="empty-icon">{icon}</span><span>{title}</span>{action && onClick && <button className="text-button" onClick={onClick}>{action} <ChevronRight size={15} /></button>}</div>
}
