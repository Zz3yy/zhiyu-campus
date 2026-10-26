import { createContext, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { ArrowLeft, ArrowRight, BookOpen, Check, ChevronRight, CircleHelp, Flower2, Home, Leaf, LoaderCircle, MessageCircle, Plus, ShieldCheck, Sprout, Trash2, Wind, X } from 'lucide-react'
import { Activity, ApiError, Entry, Garden, Reflection, Snapshot, Thought, dateInZone, enterGarden, forgetSession, hasSession, longestStreak, needsHelp, request, stage, streak, timezone, weekStart } from './api'
import { Keeper, Landscape, Plant, plantInfo, plants } from './Art'
import './garden.css'
import { Treehouse } from './Treehouse'

type Page = 'home' | 'seed' | 'chat' | 'tasks' | 'report'
const ErrorContext = createContext('')
const pages: { key: Page; label: string; icon: typeof Home }[] = [{ key:'home',label:'我的花园',icon:Home },{key:'seed',label:'播种情绪',icon:Sprout},{key:'chat',label:'树屋倾诉',icon:MessageCircle},{key:'tasks',label:'任务簿',icon:BookOpen},{key:'report',label:'成长报告',icon:Flower2}]
const emptyThought: Thought = { situation:'',automaticThought:'',evidenceFor:'',evidenceAgainst:'',balancedThought:'' }
const greeting = () => { const hour = new Date().getHours(); return hour < 8 ? '早呀，今天的花园醒了吗？' : hour >= 22 ? '今天的情绪，都安顿好了吗？' : hour < 12 ? '早上好，来花园坐一会。' : hour < 18 ? '午后好，给心情留一点空间。' : '晚上好，把今天轻轻放下来。' }

export function GardenApp() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [page,setPage] = useState<Page>('home')
  const [loading,setLoading] = useState(hasSession())
  const [error,setError] = useState('')
  const [busy,setBusy] = useState(false)
  const lock = useRef(false)
  const [selected,setSelected] = useState<string | null>(null)
  const [help,setHelp] = useState(false)
  const [settings,setSettings] = useState(false)
  const [breathing,setBreathing] = useState(false)
  const [planted,setPlanted] = useState<string | null>(null)
  const [reflectionDismissed,setReflectionDismissed] = useState(false)
  const [now,setNow] = useState(new Date())
  const [accepted,setAccepted] = useState(false)

  async function load() {
    setLoading(true); setError('')
    try { setSnapshot(await request<Snapshot>()) }
    catch (e) { if(e instanceof ApiError && e.status===401) { forgetSession(); setSnapshot(null) }; setError((e as Error).message) }
    finally { setLoading(false) }
  }
  useEffect(() => { if(hasSession()) void load(); const timer=window.setInterval(()=>setNow(new Date()),60000); return ()=>window.clearInterval(timer) },[])
  useEffect(() => { if(!planted) return; const timer=window.setTimeout(()=>setPlanted(null),1500); return ()=>window.clearTimeout(timer) },[planted])

  async function mutate(path: string, method: string, body?: unknown): Promise<Garden | null> {
    if(lock.current) return null
    lock.current=true; setBusy(true); setError('')
    try {
      const garden = await request<Garden>(path,method,body)
      setSnapshot(current=>current?{...current,garden}:null)
      return garden
    } catch(e) { setError((e as Error).message); return null }
    finally { lock.current=false; setBusy(false) }
  }
  async function enter(event: FormEvent) {
    event.preventDefault(); if(!accepted || lock.current) return
    lock.current=true; setBusy(true); setError('')
    try { await enterGarden(); await load() } catch(e) { setError((e as Error).message) }
    finally { lock.current=false; setBusy(false) }
  }

  const garden = snapshot?.garden
  const today = dateInZone(now)
  const selectedEntry = garden?.entries.find(e=>e.id===selected)
  const pending = garden?.entries.find(e=>!e.reflection && e.date<dateInZone(now,e.timezone))
  const navigate = (key: Page) => { setPage(key); setError(''); window.scrollTo({top:0,behavior:'instant'}) }

  if(loading) return <div className="garden-loading"><Keeper pose="listen"/><LoaderCircle className="spin"/><p>正在打开你的花园…</p></div>
  if(!snapshot || !garden) return <main className="welcome-page"><div className="welcome-land"><Landscape/><Keeper/><div><span className="overline">知愈校园 · 情绪花园</span><h1>每一种心情，<br/>都有生长的地方。</h1><p>种下一份感受，照顾一个小行动。<br/>不用马上变好，先从看见自己开始。</p></div></div><form className="welcome-form" onSubmit={enter}><Sprout size={30}/><h2>给自己留一座小花园</h2><p>这是融合 CBT 自助练习的校园情绪支持工具，不提供诊断、治疗或紧急救援。</p><ul><li>植物、日记和任务由本应用后端保存。当前浏览器保存匿名访问凭证，请勿在公用设备上留下会话。</li><li>树屋默认提供自助引导。单独同意启用豆包后，消息将通过后端发送给火山引擎方舟；花园上下文另行授权。</li><li>你可以在花园中删除植物，或删除全部花园数据。请尽量不填写姓名、学号等身份信息。</li></ul><label className="consent"><input type="checkbox" checked={accepted} onChange={e=>setAccepted(e.target.checked)}/>我已阅读以上说明，同意创建匿名花园。</label>{error && <p role="alert" className="error-text">{error}</p>}<button className="g-button" disabled={!accepted || busy}>{busy?'正在准备…':'进入我的花园'}<ArrowRight size={17}/></button>{hasSession() && <button type="button" className="g-text" onClick={()=>void load()}>重试打开已有花园</button>}<button type="button" className="g-text" onClick={()=>setHelp(true)}>我现在需要现实中的帮助</button></form>{help && <Help onClose={()=>setHelp(false)}/>}</main>

  const countToday = garden.entries.filter(e=>e.date===today).length
  const todo = garden.tasks.filter(t=>t.status==='TODO')
  const currentStreak = streak(garden.entries,today)
  const unlocked = longestStreak(garden.entries)

  async function addActivity(entry: Entry, activity: Activity) { await mutate('/tasks','POST',{entryId:entry.id,activityId:activity.id}) }

  return <ErrorContext.Provider value={error}><div className="garden-app">
    <aside className="g-sidebar"><button className="g-brand" onClick={()=>navigate('home')}><span><Sprout size={24}/></span><div>知愈校园<small>情 绪 花 园</small></div></button><nav aria-label="主导航">{pages.map(({key,label,icon:Icon})=><button key={key} className={page===key?'active':''} aria-current={page===key?'page':undefined} onClick={()=>navigate(key)}><Icon size={20}/><span>{label}</span>{key===page && <i/>}</button>)}</nav><div className="sidebar-keeper"><Keeper pose="care"/><p>慢慢来，<br/>生长有自己的节奏。</p></div><button className="privacy-link" onClick={()=>setSettings(true)}><ShieldCheck size={17}/><span>隐私与我的数据</span></button></aside>
    <main className="g-main"><header className="g-header"><div><span className="overline">{new Intl.DateTimeFormat('zh-CN',{month:'long',day:'numeric',weekday:'long'}).format(now)}</span><h1>{page==='home'?greeting():pages.find(p=>p.key===page)?.label}</h1></div><button className="help-button" onClick={()=>setHelp(true)}><CircleHelp size={18}/><span>寻求帮助</span></button></header>
      {snapshot.storageMode==='memory' && <div className="storage-note"><ShieldCheck size={15}/>当前是内存演示模式：刷新页面可继续，后端重启会清空。请使用非敏感的演示内容。</div>}
      {error && <div className="g-error" role="alert"><span>{error}</span><button aria-label="关闭提示" onClick={()=>setError('')}><X size={17}/></button></div>}
      {page==='home' && <>
        <section className="garden-scene"><Landscape/><div className="scene-copy"><span className="overline">我的一方小天地</span><h2>所有情绪，<br/>都值得被接纳。</h2><p>{countToday?`今天，你已安放了 ${countToday} 份感受。`:'今天的花园，等你种下第一份感受。'}</p><button className="g-button" onClick={()=>navigate('seed')}><Sprout size={18}/>去花园播种<ArrowRight size={17}/></button></div><button className="scene-treehouse" onClick={()=>navigate('chat')} aria-label="走进树屋倾诉">树屋倾诉<ChevronRight size={14}/></button><div className="scene-plants">{garden.entries.slice(-8).map((entry,i)=><button key={entry.id} className={`scene-plant ${planted===entry.id?'just-planted':''}`} style={{left:`${9+(i%4)*21}%`,bottom:`${i<4?5:57}px`,zIndex:i<4?3:2}} onClick={()=>setSelected(entry.id)} aria-label={`查看${entry.date}的${plantInfo(entry.plant).name}，${plantInfo(entry.plant).emotion}`}><Plant kind={entry.plant} stage={stage(entry,garden.tasks)}/><span>{plantInfo(entry.plant).name}</span></button>)}</div><Keeper className="scene-keeper" pose={planted?'celebrate':'welcome'}/>{garden.entries.length===0 && <div className="scene-empty"><Sprout size={22}/><span>这里会慢慢长出你的故事</span></div>}{unlocked>=3 && <span className="dew" aria-label="已解锁晨露">晨露已点亮</span>}{unlocked>=7 && <span className="butterfly" aria-label="已解锁蝴蝶">୨୧</span>}</section>
        <section className="garden-stats" aria-label="花园今日概览"><div><span>今日播种</span><strong>{countToday}<small> 株</small></strong></div><div><span>待完成微行动</span><strong>{todo.length}<small> 件</small></strong></div><div><span>连续记录</span><strong>{currentStreak}<small> 天</small></strong></div><p><Leaf size={20}/>没有需要赶上的进度，<br/>每一次回来，都算数。</p></section>
        {pending && !reflectionDismissed && <section className="reflection-banner"><Plant kind={pending.plant} stage={stage(pending,garden.tasks)}/><div><h3>去看看先前的情绪植物吧</h3><p>{pending.date} 的{plantInfo(pending.plant).name}，现在是什么感觉？</p></div><button className="g-button secondary" onClick={()=>setSelected(pending.id)}>轻轻回看<ArrowRight size={15}/></button><button className="g-icon" aria-label="本次暂不提醒" onClick={()=>setReflectionDismissed(true)}><X size={17}/></button></section>}
        <section className="g-section"><div className="section-title"><div><span className="overline">一点点，就很好</span><h2>今天，照顾自己的小事</h2></div><button className="g-text" onClick={()=>navigate('tasks')}>打开任务簿<ArrowRight size={16}/></button></div>{todo.length ? <div className="mini-tasks">{todo.slice(0,3).map(task=><button key={task.id} onClick={()=>navigate('tasks')}><span className="task-leaf"><Leaf size={20}/></span><div><strong>{task.title}</strong><small>{task.entryId?'来自你的情绪植物':'自己安排的小行动'}</small></div><ChevronRight size={17}/></button>)}</div> : <div className="g-empty"><Sprout size={27}/><p>花园里还没有小任务，去种一株情绪植物吧。</p><button className="g-text" onClick={()=>navigate('seed')}>播种一份感受<ArrowRight size={15}/></button></div>}</section>
        {garden.entries.length>0 && <section className="g-section"><div className="section-title"><h2>花园里的故事</h2><span className="muted-copy">点击植物，回看成长日记</span></div><div className="entry-shelf">{[...garden.entries].reverse().map(entry=><button key={entry.id} onClick={()=>setSelected(entry.id)}><Plant kind={entry.plant} stage={stage(entry,garden.tasks)}/><strong>{plantInfo(entry.plant).name}</strong><span>{plantInfo(entry.plant).emotion} · {entry.date.slice(5)}</span></button>)}</div></section>}
      </>}
      {page==='seed' && <SeedForm busy={busy} onHelp={()=>setHelp(true)} onBack={()=>navigate('home')} onSeed={async body=>{ const result=await mutate('/entries','POST',body); if(result){ const entry=result.entries.find(e=>e.requestId===body.requestId)!; setPlanted(entry.id); navigate('home'); setSelected(entry.id) } }}/ >}
      {page==='tasks' && <TaskBook garden={garden} activities={snapshot.activities} busy={busy} onAdd={(title)=>mutate('/tasks','POST',{title})} onChange={(id,status,helpfulScore,feedback)=>mutate(`/tasks/${id}`,'PUT',{status,helpfulScore,feedback})} onSelect={setSelected} onBreathe={()=>setBreathing(true)} onSeed={()=>navigate('seed')}/>}
      {page==='report' && <Report garden={garden} today={today} onSelect={setSelected}/>}
      {page==='chat' && <Treehouse garden={garden} busy={busy} onAdd={(activity,entryId,confirmationToken)=>mutate(activity.id.startsWith('exam_')?'/chat/tools/create_task':'/tasks','POST',{activityId:activity.id,entryId,confirmationToken})} onHelp={()=>setHelp(true)} onBreathe={()=>setBreathing(true)} onSelect={setSelected}/>}
      <footer className="g-footer"><Leaf size={14}/><span>知愈校园 · 情绪支持与自助练习，不替代专业诊疗</span><button className="g-text" onClick={()=>setSettings(true)}>隐私与我的数据</button></footer>
    </main>
    {selectedEntry && !planted && <EntryDialog key={selectedEntry.id} entry={selectedEntry} garden={garden} activities={snapshot.activities} busy={busy} onClose={()=>setSelected(null)} onThought={value=>mutate(`/entries/${selectedEntry.id}/thought`,'PUT',value)} onReflect={value=>mutate(`/entries/${selectedEntry.id}/reflection`,'POST',value)} onAdd={activity=>addActivity(selectedEntry,activity)} onBreathe={()=>setBreathing(true)} onHelp={()=>setHelp(true)} onDelete={async()=>{const value=await mutate(`/entries/${selectedEntry.id}`,'DELETE'); if(value)setSelected(null)}}/>}
    {help && <Help onClose={()=>setHelp(false)}/>}
    {breathing && <Breathing onClose={()=>setBreathing(false)}/>}
    {settings && <Dialog title="隐私与我的数据" onClose={()=>setSettings(false)}><p>这个花园由当前浏览器中的匿名凭证访问。花园内容保存在{snapshot.storageMode==='memory'?'服务器内存中，服务重启会清空':'本应用数据库中'}；请保管好当前设备。</p><p>树屋聊天正文不写入本应用数据库，离开页面清空页面记录。启用豆包后消息会发送给火山引擎方舟，服务商留存以实际条款为准；删除花园不能撤回已发送的数据。旧版记录未自动转换为植物。</p><p>删除全部数据会永久删除当前花园的植物、日记、任务和访问凭证，无法撤销。</p><DeleteGarden busy={busy} onDelete={async()=>{ if(lock.current)return; lock.current=true;setBusy(true);try{await request('','DELETE');forgetSession();setSnapshot(null);setSettings(false);setAccepted(false)}catch(e){setError((e as Error).message)}finally{lock.current=false;setBusy(false)} }}/></Dialog>}
  </div></ErrorContext.Provider>
}

function SeedForm({busy,onSeed,onBack,onHelp}:{busy:boolean;onSeed:(body:{requestId:string;plant:string;intensity:number;note:string;timezone:string})=>Promise<void>;onBack:()=>void;onHelp:()=>void}) {
  const [kind,setKind]=useState<Entry['plant'] | null>(null)
  const [note,setNote]=useState(''); const [intensity,setIntensity]=useState(3)
  const [requestId] = useState(()=>crypto.randomUUID())
  return <section className="seed-page"><div className="seed-intro"><div><span className="overline">觉察，是生长的开始</span><h2>此刻的心情，像哪一株植物？</h2><p>没有好坏之分。选择最接近的一种，稍后也可以再种一株。</p></div><Keeper pose="listen"/></div><div className="plant-picker" role="group" aria-label="选择情绪植物">{plants.map(plant=><button key={plant.kind} aria-pressed={kind===plant.kind} className={kind===plant.kind?'chosen':''} onClick={()=>setKind(plant.kind)}><Plant kind={plant.kind}/><span className="plant-name">{plant.name}<small>{plant.emotion}</small></span><p>{plant.description}</p>{kind===plant.kind && <span className="chosen-check"><Check size={15}/></span>}</button>)}</div>{kind && <form className="seed-form" onSubmit={e=>{e.preventDefault();if(needsHelp(note))onHelp();void onSeed({requestId,plant:kind,intensity,note,timezone:timezone()})}}><label htmlFor="seed-note">发生了什么，让你有这样的感受？<small>可以不写 · 最多 1000 字</small></label><textarea id="seed-note" value={note} onChange={e=>setNote(e.target.value)} maxLength={1000} placeholder="例如：明天要上台汇报，担心自己讲不好。"/><Intensity value={intensity} onChange={setIntensity}/><div className="form-actions"><button type="button" className="g-text" onClick={onBack}>先回花园</button><button className="g-button" disabled={busy}>{busy?<LoaderCircle className="spin" size={17}/>:<Sprout size={18}/>}种下{plantInfo(kind).name}</button></div></form>}</section>
}

function Intensity({value,onChange,label='这份感受有多强烈？'}:{value:number;onChange:(value:number)=>void;label?:string}) {
  return <fieldset className="intensity"><legend>{label}</legend><div>{[1,2,3,4,5].map(number=><button key={number} type="button" aria-pressed={number===value} onClick={()=>onChange(number)} className={number===value?'selected':''}>{number}</button>)}</div><p><span>1 · 很轻微</span><span>5 · 很强烈</span></p></fieldset>
}

function Dialog({title,onClose,children}:{title:string;onClose:()=>void;children:ReactNode}) {
  const error=useContext(ErrorContext)
  const ref=useRef<HTMLDialogElement>(null)
  useEffect(()=>{const dialog=ref.current;dialog?.showModal();return()=>dialog?.close()},[])
  return <dialog ref={ref} className="g-dialog" aria-label={title} onCancel={e=>{e.preventDefault();onClose()}}><header><h2>{title}</h2><button autoFocus className="g-icon" aria-label="关闭窗口" onClick={onClose}><X size={20}/></button></header>{error && <p className="error-text" role="alert">{error}</p>}{children}</dialog>
}

function EntryDialog({entry,garden,activities,busy,onClose,onThought,onReflect,onAdd,onBreathe,onHelp,onDelete}:{entry:Entry;garden:Garden;activities:Activity[];busy:boolean;onClose:()=>void;onThought:(v:Thought)=>Promise<Garden|null>;onReflect:(v:Reflection)=>Promise<Garden|null>;onAdd:(a:Activity)=>Promise<void>;onBreathe:()=>void;onHelp:()=>void;onDelete:()=>Promise<void>}) {
  const [tab,setTab]=useState<'diary'|'actions'|'reflect'>('diary')
  const [thought,setThought]=useState<Thought>(entry.thought??{...emptyThought,situation:entry.note})
  const [saved,setSaved]=useState(false)
  const [deleting,setDeleting]=useState(false)
  const [intensity,setIntensity]=useState(entry.reflection?.intensity??entry.intensity)
  const [change,setChange]=useState(entry.reflection?.change??'')
  const [note,setNote]=useState(entry.reflection?.note??'')
  const info=plantInfo(entry.plant)
  const canReflect=entry.date<dateInZone(new Date(),entry.timezone)
  const [alternate,setAlternate]=useState(false)
  const recommendations=activities.filter(a=>a.plants.includes(entry.plant)).slice(alternate?2:0,alternate?4:2)
  return <Dialog title={`${info.name}的成长日记`} onClose={onClose}><div className="entry-heading"><Plant kind={entry.plant} stage={stage(entry,garden.tasks)}/><div><span className="overline">{entry.date} · {info.emotion}</span><h3>{entry.note||'这份感受，已经被好好安放。'}</h3><p>当时的感受强度 {entry.intensity}/5 · {['刚刚萌芽','长出新叶','慢慢舒展'][stage(entry,garden.tasks)]}</p></div></div><div className="entry-tabs">{(['diary','actions','reflect'] as const).map((key,i)=><button key={key} className={tab===key?'active':''} onClick={()=>setTab(key)}>{['觉察日记','照顾它的小行动','回看与复盘'][i]}</button>)}</div>
    {tab==='diary' && <form className="diary-form" onSubmit={async e=>{e.preventDefault();if(needsHelp(Object.values(thought).join(' ')))onHelp();const result=await onThought(thought);if(result)setSaved(true)}}><p className="guide-question">{info.question}</p><p className="muted-copy">参考 CBT 思维记录，一次只写一点也可以。所有问题都可跳过。</p>{([{key:'situation',label:'当时发生的事',placeholder:'只描述你观察到的事实。'},{key:'automaticThought',label:'脑中闪过的想法',placeholder:'例如：如果讲不好，大家会觉得我不行。'}] as const).map(field=><label key={field.key}>{field.label}<textarea maxLength={1000} value={thought[field.key]} placeholder={field.placeholder} onChange={e=>{setSaved(false);setThought({...thought,[field.key]:e.target.value})}}/></label>)}<details><summary>愿意的话，再看一看这个想法</summary>{([{key:'evidenceFor',label:'有哪些事实支持这个想法？'},{key:'evidenceAgainst',label:'有没有不完全符合它的事实？'},{key:'balancedThought',label:'一个更平衡、也更照顾自己的理解'}] as const).map(field=><label key={field.key}>{field.label}<textarea maxLength={1000} value={thought[field.key]} onChange={e=>{setSaved(false);setThought({...thought,[field.key]:e.target.value})}}/></label>)}</details><a className="source-link" href="https://www.nhs.uk/mental-health/self-help/guides-tools-and-activities/thought-record/" target="_blank" rel="noreferrer">方法参考：NHS 思维记录</a><div className="form-actions"><button type="button" className="g-text" onClick={()=>setTab('actions')}>暂时不写，选个小行动<ArrowRight size={15}/></button><button disabled={busy} className="g-button">{saved?'已保存':'保存日记'}</button></div><p className="form-feedback" role="status">{saved?'这段日记已保存。你可以继续选择小行动。':''}</p></form>}
    {tab==='actions' && <div className="recommendations"><p>选择一件愿意做的小事。完成后，这株植物会舒展一点；跳过也不会让它枯萎。</p>{recommendations.map(activity=><ActivityCard key={activity.id} activity={activity} added={garden.tasks.some(t=>t.status==='TODO'&&t.entryId===entry.id&&t.activityId===activity.id)} busy={busy} onAdd={()=>void onAdd(activity)} onBreathe={onBreathe}/>)}{activities.filter(a=>a.plants.includes(entry.plant)).length>2 && <button className="g-text" onClick={()=>setAlternate(!alternate)}>换一组小行动</button>}<p className="muted-copy">活动为自助内容草案，尚未经专业人员逐项审阅。来源介绍一般方法，不证明本应用具有治疗效果。</p></div>}
    {tab==='reflect' && <form className="diary-form" onSubmit={async e=>{e.preventDefault();if(needsHelp(note))onHelp();await onReflect({intensity,change,note,date:dateInZone()})}}><h3>{canReflect?'现在的你，感觉怎么样？':'明天，再来看看这份感受。'}</h3><p>好一些、没有变化、或更难受，都可以如实记录。</p>{canReflect && <><div className="reflection-options">{[['BETTER','好一些'],['SAME','差不多'],['HARDER','更难受'],['UNSURE','暂不评价']].map(([value,label])=><button type="button" key={value} aria-pressed={change===value} className={change===value?'selected':''} onClick={()=>setChange(value)}>{label}</button>)}</div><Intensity value={intensity} onChange={setIntensity} label="现在，同一份感受的强度"/><label>想留给自己的一句话<small>可选</small><textarea value={note} maxLength={1000} onChange={e=>setNote(e.target.value)}/></label><button className="g-button" disabled={busy||!change}>保存复盘</button></>}{entry.reflection && <div className="reflection-result" role="status"><strong>复盘已保存 · {entry.reflection.date}</strong><span>个人感受强度：{entry.intensity}/5 → {entry.reflection.intensity}/5</span><svg viewBox="0 0 240 80" role="img" aria-label={`感受强度从${entry.intensity}到${entry.reflection.intensity}`}><path d={`M20 ${75-entry.intensity*12} L220 ${75-entry.reflection.intensity*12}`} stroke="#628b70" strokeWidth="3" fill="none"/><circle cx="20" cy={75-entry.intensity*12} r="5" fill="#a8b899"/><circle cx="220" cy={75-entry.reflection.intensity*12} r="5" fill="#628b70"/></svg><small>两次个人记录，不代表诊断或疗效判断。</small></div>}</form>}
    <div className="entry-bottom">{deleting?<><span>删除植物及关联日记、行动？无法撤销。</span><button className="danger-button" disabled={busy} onClick={()=>void onDelete()}>确认删除</button><button className="g-text" onClick={()=>setDeleting(false)}>取消</button></>:<button className="g-text" onClick={()=>setDeleting(true)}><Trash2 size={14}/>删除这株植物</button>}</div>
  </Dialog>
}

function ActivityCard({activity,added,busy,onAdd,onBreathe}:{activity:Activity;added:boolean;busy:boolean;onAdd:()=>void;onBreathe:()=>void}) {
  return <article className="activity-recommendation"><div><span className="method-label">{activity.method} · {activity.minutes} 分钟</span><h3>{activity.title}</h3><p>{activity.instructions}</p>{activity.source.startsWith('https://')&&<a href={activity.source} target="_blank" rel="noreferrer" className="source-link">查看方法参考</a>}</div><div className="activity-buttons">{activity.id==='breathe' && <button className="g-button secondary" onClick={onBreathe}><Wind size={16}/>呼吸陪伴</button>}<button className="g-button secondary" disabled={added||busy} onClick={onAdd}>{added?<Check size={16}/>:<Plus size={16}/ >}{added?'已加入':'加入任务簿'}</button></div></article>
}

function TaskBook({garden,activities,busy,onAdd,onChange,onSelect,onBreathe,onSeed}:{garden:Garden;activities:Activity[];busy:boolean;onAdd:(title:string)=>Promise<Garden|null>;onChange:(id:string,status:string,helpful?:number,feedback?:'TOO_HARD'|'NOT_HELPFUL')=>Promise<Garden|null>;onSelect:(id:string)=>void;onBreathe:()=>void;onSeed:()=>void}) {
  const [draft,setDraft]=useState(''); const [completing,setCompleting]=useState<string|null>(null); const [score,setScore]=useState<number|undefined>()
  return <section className="task-book"><div className="page-intro"><h2>做一点小事，给自己一点照顾。</h2><p>可以完成，也可以跳过。这里没有欠下的任务。</p></div>{[{label:'来自情绪植物',tasks:garden.tasks.filter(t=>!!t.entryId)},{label:'我的自定义',tasks:garden.tasks.filter(t=>!t.entryId)}].map(group=><section className="g-section" key={group.label}><h3>{group.label}</h3>{!group.tasks.length?<div className="g-empty"><Leaf size={24}/><p>{group.label==='我的自定义'?'把你愿意做的小事写在下面。':'种下植物后，可以为它选择两个微行动。'}</p>{group.label!=='我的自定义' && <button className="g-text" onClick={onSeed}>去播种<ArrowRight size={15}/></button>}</div>:<div className="book-list">{[...group.tasks].reverse().map(task=>{const activity=activities.find(a=>a.id===task.activityId);const entry=garden.entries.find(e=>e.id===task.entryId);return <article className={`book-task ${task.status.toLowerCase()}`} key={task.id}><div className="book-task-head"><span className="task-leaf">{task.status==='DONE'?<Check size={20}/>:<Leaf size={20}/>}</span><div><h3>{task.title}</h3><span className="muted-copy">{task.status==='TODO'?`${activity?activity.minutes+' 分钟 · ':''}待开始`:task.status==='DONE'?'已完成 · 谢谢你照顾自己':task.feedback==='TOO_HARD'?'已记录：太难，可用于调整后续建议':task.feedback==='NOT_HELPFUL'?'已记录：没有帮助，可用于调整后续建议':'已跳过 · 下次再试也可以'}</span></div>{task.entryId && <button className="g-text" onClick={()=>onSelect(task.entryId!)}>看植物<ChevronRight size={15}/></button>}</div>{entry && <p className="muted-copy">{plantInfo(entry.plant).name} · {entry.date} · {['刚刚萌芽','长出新叶','慢慢舒展'][stage(entry,garden.tasks)]}</p>}{activity && <p>{activity.instructions}</p>}{task.status==='TODO' && <div className="task-controls">{activity?.id==='breathe' && <button className="g-text" onClick={onBreathe}><Wind size={16}/>开始呼吸</button>}<button className="g-text" disabled={busy} onClick={()=>void onChange(task.id,'SKIPPED')}>今天先跳过</button><button className="g-text" disabled={busy} onClick={()=>void onChange(task.id,'SKIPPED',undefined,'TOO_HARD')}>太难</button><button className="g-text" disabled={busy} onClick={()=>void onChange(task.id,'SKIPPED',undefined,'NOT_HELPFUL')}>没有帮助</button><button className="g-button secondary" disabled={busy} onClick={()=>{setCompleting(task.id);setScore(undefined)}}><Check size={16}/>完成行动</button></div>}{task.helpfulScore && <small>主观帮助度：{task.helpfulScore}/5</small>}</article>})}</div>}</section>)}<form className="custom-task" onSubmit={async e=>{e.preventDefault();if(draft.trim()&&await onAdd(draft.trim()))setDraft('')}}><label htmlFor="custom-task">给自己安排一件小事</label><div><input id="custom-task" maxLength={120} value={draft} onChange={e=>setDraft(e.target.value)} placeholder="例如：午休时离开屏幕两分钟"/><button className="g-button" disabled={busy||!draft.trim()}><Plus size={17}/>添加</button></div></form>{completing && <Dialog title="这个小行动，帮到你了吗？" onClose={()=>setCompleting(null)}><p>反馈是可选的。没有帮助也可以如实记录。</p><div className="reflection-options">{[1,2,3,4,5].map(value=><button key={value} className={score===value?'selected':''} onClick={()=>setScore(value)} aria-pressed={score===value}>{value}</button>)}</div><p className="muted-copy">1 没有帮助 · 5 很有帮助</p><button className="g-button" disabled={busy} onClick={async()=>{if(await onChange(completing,'DONE',score))setCompleting(null)}}>确认完成{score?'并保存反馈':''}</button></Dialog>}</section>
}

function Report({garden,today,onSelect}:{garden:Garden;today:string;onSelect:(id:string)=>void}) {
  const from=weekStart(today); const entries=garden.entries.filter(e=>e.date>=from&&e.date<=today)
  const done=garden.tasks.filter(t=>t.status==='DONE' && t.completedAt && dateInZone(new Date(t.completedAt))>=from && dateInZone(new Date(t.completedAt))<=today)
  const distribution=plants.map(p=>({...p,count:entries.filter(e=>e.plant===p.kind).length}))
  const days=Array.from({length:7},(_,index)=>{const date=new Date(from+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+index);const key=date.toISOString().slice(0,10);return {date:key,label:new Intl.DateTimeFormat('zh-CN',{weekday:'short',timeZone:'UTC'}).format(date),entries:entries.filter(entry=>entry.date===key)}})
  const [selectedDate,setSelectedDate]=useState(today)
  const selectedDay=days.find(day=>day.date===selectedDate)??days[6]
  const growth=days.map(day=>day.entries.reduce((sum,entry)=>sum+stage(entry,garden.tasks),0))
  const maxGrowth=Math.max(1,...growth)
  const points=growth.map((value,index)=>`${36+index*64},${112-(value/maxGrowth)*82}`).join(' ')
  const recordedDays=days.filter(day=>day.entries.length).length
  return <section className="report-page"><div className="report-intro"><div><span className="overline">{from} 至 {today} · 近七天</span><h2>看见生长，也看见自己。</h2><p>{entries.length?`这七天，你种下了 ${entries.length} 株情绪植物。每一株，都是一份被认真对待的感受。`:'你的花园还在等待第一份感受。记录之后，这里会慢慢长出故事。'}</p></div><Keeper pose="care"/></div><div className="report-counts"><div><strong>{recordedDays}<small> / 7 天</small></strong><span>本周有记录的日子</span></div><div><strong>{done.length}<small> 件</small></strong><span>本周完成的微行动</span></div><div><strong>{streak(garden.entries,today)}<small> 天</small></strong><span>连续记录</span></div></div>
    <section className="g-section report-trend"><div className="section-title"><div><h3>植物成长曲线</h3><p className="muted-copy">每日记录植物的成长阶段合计；空白日按零展示。</p></div></div>{entries.length?<svg viewBox="0 0 460 142" role="img" aria-label="近七天每日植物成长阶段趋势图" className="growth-chart"><path className="chart-grid" d="M30 30H440M30 71H440M30 112H440"/><polyline points={points} fill="none" stroke="#638b70" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"/>{growth.map((value,index)=><g key={days[index].date}><circle cx={36+index*64} cy={112-(value/maxGrowth)*82} r="5"/><text x={36+index*64} y="135" textAnchor="middle">{days[index].label}</text></g>)}</svg>:<div className="g-empty"><Sprout size={25}/><p>有了第一份植物记录后，这里会显示真实的成长变化。</p></div>}</section>
    <section className="g-section"><h3>每日植物记录</h3><p className="muted-copy">颜色表示植物类型，深浅表示记录时的情绪强度。选择一天查看记录。</p><div className="week-garden" role="group" aria-label="近七天每日植物记录">{days.map(day=><button key={day.date} className={`garden-day ${selectedDate===day.date?'selected':''} ${day.entries.length?'has-entry':''}`} onClick={()=>setSelectedDate(day.date)} aria-pressed={selectedDate===day.date} aria-label={`${day.date}，${day.entries.length?`${day.entries.length} 株植物`:'没有记录'}`}><span>{day.label}</span><div className="day-plants">{day.entries.length?day.entries.slice(0,5).map(entry=><i key={entry.id} title={`${plantInfo(entry.plant).name}，强度 ${entry.intensity}`} style={{'--plant-color':plantInfo(entry.plant).color,'--plant-opacity':.38+entry.intensity*.12} as React.CSSProperties}/>):<i className="empty-day"/>}</div><small>{day.date.slice(5)}</small></button>)}</div><div className="day-detail" aria-live="polite"><div><strong>{selectedDay.date}</strong><span>{selectedDay.entries.length?`${selectedDay.entries.length} 株植物`:'没有记录'}</span></div>{selectedDay.entries.length?<div className="day-detail-entries">{selectedDay.entries.map(entry=><button key={entry.id} onClick={()=>onSelect(entry.id)}><Plant kind={entry.plant} stage={stage(entry,garden.tasks)}/><span><strong>{plantInfo(entry.plant).name} · {plantInfo(entry.plant).emotion}</strong><small>情绪强度 {entry.intensity} · {['刚刚萌芽','长出新叶','慢慢舒展'][stage(entry,garden.tasks)]}</small></span><ChevronRight size={17}/></button>)}</div>:<p className="muted-copy">这一天没有留下植物记录。</p>}</div></section>
    <section className="g-section"><h3>这一周的花园分布</h3><p className="muted-copy">植物种类是情绪分类，没有好坏排名。以下占比按本周播种次数计算。</p><div className="distribution">{distribution.map(p=>{const share=entries.length?Math.round(p.count/entries.length*100):0;return <div key={p.kind}><div className={`distribution-plot ${p.count?'':'no-plants'}`}><Plant kind={p.kind} stage={p.count?2:0}/>{p.count>1 && <Plant kind={p.kind} stage={1}/ >}{p.count>2 && <Plant kind={p.kind} stage={0}/>}</div><strong>{p.name} · {p.emotion}</strong><span>{p.count} 株 · {share}%</span><div className="plant-share" role="progressbar" aria-label={`${p.name}占比`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={share}><i style={{width:`${share}%`,background:p.color}}/></div></div>})}</div>{entries.length>0 && <p className="report-reading">{distribution.filter(p=>p.count).map(p=>`${p.count} 株${p.name}`).join('，')}。这些是你主动留下的记录，不能代表全部心情，也不推断它们的原因。</p>}</section>
    <section className="g-section"><h3>回看后的变化</h3>{entries.filter(e=>e.reflection).length===0?<div className="g-empty"><BookOpen size={25}/><p>完成一次次日复盘后，你的成长轨迹会出现在这里。</p></div>:<div className="report-reflections">{entries.filter(e=>e.reflection).map(entry=><button key={entry.id} onClick={()=>onSelect(entry.id)}><Plant kind={entry.plant} stage={stage(entry,garden.tasks)}/><div><strong>{plantInfo(entry.plant).name} · {entry.date.slice(5)}</strong><p>感受强度 {entry.intensity} → {entry.reflection!.intensity}</p><span>{entry.reflection!.note||'已经给先前的自己留下一次回应。'}</span></div><ChevronRight size={18}/></button>)}</div>}</section></section>
}

function Breathing({onClose}:{onClose:()=>void}) {
  const [running,setRunning]=useState(false); const [seconds,setSeconds]=useState(0)
  useEffect(()=>{if(!running)return;const timer=window.setInterval(()=>setSeconds(value=>{if(value>=119){setRunning(false);return 120}return value+1}),1000);return()=>window.clearInterval(timer)},[running])
  const inhale=seconds%10<4
  return <Dialog title="和知知，慢慢呼吸" onClose={onClose}><p>轻柔吸气，慢慢呼气。无需憋气或追赶节奏；不舒服时，随时停止并恢复自然呼吸。</p><div className={`breathing-orbit ${running?(inhale?'inhale':'exhale'):''}`}><span>{seconds===120?'练习结束':running?(inhale?'轻轻吸气':'慢慢呼气'):'按自己的节奏'}<small>{Math.floor(seconds/60)}:{String(seconds%60).padStart(2,'0')} / 2:00</small></span></div><div className="breathing-controls"><button className="g-button" onClick={()=>{if(seconds===120)setSeconds(0);setRunning(!running)}}>{seconds===120?'再来一次':running?'暂停':'开始'}</button><button className="g-text" onClick={onClose}>停止并返回</button></div><p className="muted-copy">这是一段可选的放松练习，不是治疗，也不替代现实中的帮助。练习结束不会自动完成任务。</p></Dialog>
}

function Help({onClose}:{onClose:()=>void}) {
  return <Dialog title="此刻，先照顾你的安全" onClose={onClose}><p>如果你已经受伤、可能立即伤害自己或他人，或处于紧急危险中，请立即联系当地紧急服务，并请身边的人陪伴你。</p><div className="help-resources"><a href="tel:120"><strong>120</strong><span>中国大陆 · 医疗急救</span></a><a href="tel:110"><strong>110</strong><span>中国大陆 · 紧急报警</span></a></div><p>如果不在中国大陆，请联系所在地紧急号码。可以走到安全、有人陪伴的地方，并联系你信任的朋友、家人或老师。</p><h3>需要校园支持时</h3><p>请通过学校官网或学生事务部门查找心理中心的正式预约方式。本项目尚未配置你所在学校的联系方式，也没有真人值班或自动报警服务。</p><p className="muted-copy">本工具不能可靠识别所有风险。无论是否出现系统提示，你都可以主动寻求帮助。</p></Dialog>
}

function DeleteGarden({busy,onDelete}:{busy:boolean;onDelete:()=>Promise<void>}) {
  const [confirm,setConfirm]=useState(false)
  return <div className="delete-garden"><label className="consent"><input type="checkbox" checked={confirm} onChange={e=>setConfirm(e.target.checked)}/>我确认永久删除当前花园及其全部内容。</label><button className="danger-button" disabled={!confirm||busy} onClick={()=>void onDelete()}><Trash2 size={16}/>删除全部花园数据</button></div>
}



