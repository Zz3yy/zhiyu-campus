import { useState, type ReactNode } from 'react'
import { Activity, CalendarCheck2, ChevronRight, CircleHelp, HeartPulse, MessageCircle, Moon, ShieldCheck, Sparkles, SunMedium } from 'lucide-react'

type NavKey = 'home' | 'checkin' | 'chat' | 'tasks'

const navItems: Array<{ key: NavKey; label: string; icon: typeof HeartPulse }> = [
  { key: 'home', label: '今日状态', icon: HeartPulse },
  { key: 'checkin', label: '情绪记录', icon: SunMedium },
  { key: 'chat', label: '找知愈聊聊', icon: MessageCircle },
  { key: 'tasks', label: '行动计划', icon: CalendarCheck2 },
]

export function App() {
  const [active, setActive] = useState<NavKey>('home')
  const [mood, setMood] = useState(3)
  const [saved, setSaved] = useState(false)

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">知</span><span>知愈校园</span></div>
        <p className="brand-subtitle">给自己一个被听见的空间</p>
        <nav aria-label="主导航">
          {navItems.map(({ key, label, icon: Icon }) => (
            <button key={key} className={`nav-item ${active === key ? 'is-active' : ''}`} onClick={() => setActive(key)}>
              <Icon size={18} strokeWidth={1.8} />
              <span>{label}</span>
            </button>
          ))}
        </nav>
        <div className="sidebar-footer"><ShieldCheck size={16} /><span>匿名模式已开启</span></div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div><p className="eyebrow">星期一 · 10 月 14 日</p><h1>早上好，今天也照顾一下自己。</h1></div>
          <button className="icon-button" title="帮助与求助资源" aria-label="帮助与求助资源"><CircleHelp size={20} /></button>
        </header>

        {active === 'home' && <Home mood={mood} setActive={setActive} />}
        {active === 'checkin' && <Checkin mood={mood} setMood={setMood} saved={saved} setSaved={setSaved} />}
        {active === 'chat' && <Chat />}
        {active === 'tasks' && <Tasks />}
      </main>
    </div>
  )
}

function Home({ mood, setActive }: { mood: number; setActive: (key: NavKey) => void }) {
  return <div className="content-grid">
    <section className="hero-panel">
      <div><span className="section-kicker">今日状态</span><h2>慢一点，也是在前进。</h2><p>花一分钟留意此刻的自己，知愈会帮你把感受整理成一个可以开始的小行动。</p><button className="primary-button" onClick={() => setActive('checkin')}>记录现在的状态 <ChevronRight size={16} /></button></div>
      <div className="hero-orbit"><HeartPulse size={46} strokeWidth={1.2} /><span>今天的心情<br /><strong>{['很低落', '有些低落', '还不错', '挺好的', '状态很好'][mood - 1]}</strong></span></div>
    </section>
    <section className="section-block"><div className="section-heading"><div><span className="section-kicker">小小行动</span><h3>今天适合做什么</h3></div><button className="text-button" onClick={() => setActive('tasks')}>查看全部 <ChevronRight size={15} /></button></div><div className="activity-list"><ActivityCard icon={<Moon />} title="睡前 3 分钟呼吸" meta="放松 · 3 分钟" /><ActivityCard icon={<SunMedium />} title="到户外走一走" meta="轻运动 · 15 分钟" /><ActivityCard icon={<MessageCircle />} title="给信任的人发个消息" meta="连接 · 5 分钟" /></div></section>
    <section className="insight-row"><div className="insight-card"><span className="section-kicker">连续记录</span><strong>3 天</strong><span>保持你的节奏</span></div><div className="insight-card"><span className="section-kicker">本周完成</span><strong>4 / 7</strong><span>个小行动</span></div><div className="insight-card insight-card-accent"><Sparkles size={20} /><span>今天不需要解决所有事情，先选择一件容易开始的。</span></div></section>
  </div>
}

function Checkin({ mood, setMood, saved, setSaved }: { mood: number; setMood: (v: number) => void; saved: boolean; setSaved: (v: boolean) => void }) {
  return <section className="form-panel"><div className="section-kicker">情绪记录 · 约 1 分钟</div><h2>此刻的你，感觉怎么样？</h2><p className="muted">没有正确答案，只记录真实感受就好。</p><div className="mood-scale" role="radiogroup" aria-label="心情评分">{['很低落', '有些低落', '还不错', '挺好的', '状态很好'].map((label, index) => <button key={label} className={`mood-option ${mood === index + 1 ? 'selected' : ''}`} onClick={() => setMood(index + 1)} role="radio" aria-checked={mood === index + 1}><span>{['☁', '◒', '◑', '◕', '☀'][index]}</span><small>{label}</small></button>)}</div><label className="field-label" htmlFor="note">想补充些什么吗？<span>可选</span></label><textarea id="note" className="note-input" placeholder="例如：今天有点担心明天的答辩……" /><div className="form-footer"><span className="privacy-note"><ShieldCheck size={15} />仅自己可见</span><button className="primary-button" onClick={() => setSaved(true)}>{saved ? '已保存' : '保存记录'} <ChevronRight size={16} /></button></div></section>
}

function Chat() {
  return <section className="chat-panel"><div className="chat-header"><div><div className="section-kicker">AI 支持对话</div><h2>找知愈聊聊</h2></div><span className="status-dot">在线</span></div><div className="chat-messages"><div className="message assistant">你好，我在这里听你说。最近有什么事情一直占据你的心思吗？</div><div className="message user">最近考试压力有点大，晚上也睡不好。</div><div className="message assistant">听起来你同时承受着考试压力和睡眠不足，这确实会让人很疲惫。我们可以先从一个很小的动作开始：今晚睡前做 3 分钟呼吸练习，好吗？</div></div><div className="chat-composer"><input placeholder="说说你现在的感受……" /><button className="primary-button">发送 <ChevronRight size={16} /></button></div><p className="chat-disclaimer">知愈提供基础心理支持，不替代专业诊断或紧急救援。</p></section>
}

function Tasks() {
  return <section className="form-panel"><div className="section-kicker">行动计划 · 今天</div><h2>给自己安排几个小动作</h2><p className="muted">完成不是义务，照顾自己的过程本身就值得被看见。</p><div className="task-list"><label className="task-item"><input type="checkbox" /><span><strong>3 分钟呼吸练习</strong><small>今晚 22:00 · 放松</small></span></label><label className="task-item"><input type="checkbox" /><span><strong>到户外走一走</strong><small>今天任意时间 · 15 分钟</small></span></label><label className="task-item"><input type="checkbox" /><span><strong>记录今天的心情</strong><small>睡前 · 情绪记录</small></span></label></div></section>
}

function ActivityCard({ icon, title, meta }: { icon: ReactNode; title: string; meta: string }) {
  return <button className="activity-card"><span className="activity-icon">{icon}</span><span className="activity-copy"><strong>{title}</strong><small>{meta}</small></span><ChevronRight size={17} /></button>
}
