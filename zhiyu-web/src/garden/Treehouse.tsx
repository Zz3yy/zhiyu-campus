import { useEffect, useRef, useState } from 'react'
import { ArrowRight, Leaf, ShieldCheck, Wind } from 'lucide-react'
import { Activity, Garden, request } from './api'
import { Keeper, plantInfo } from './Art'
type Phase = 'CLARIFY' | 'RECOMMEND' | 'PAUSE' | 'SAFETY' | 'EXPLAIN'
type RetrievalTrace = { trigger:string; result:{ mode:string; status:string; filters:{timeLimit:number|null;emotion:string|null;scene:string|null;cognitiveLoad:string|null}; hits:{citation:Citation;vectorSimilarity:number;bm25:number;fusionScore:number}[] } }
function KnowledgeTrace({trace}:{trace:RetrievalTrace[]}) {
  if (!trace.length) return null
  const scenes:Record<string,string>={dormitory:'宿舍',library:'图书馆',classroom:'教室',commute:'通勤'}
  const loads:Record<string,string>={very_low:'极低',low:'低',medium:'中'}
  const emotions:Record<string,string>={anxiety:'焦虑',procrastination:'拖延',frustration:'挫败',confusion:'迷茫'}
  const statuses:Record<string,string>={NO_APPROVED_CONTENT:'暂无已审核内容，启用本地兜底回复',NO_MATCH:'未找到符合本轮条件的知识，尝试重新检索或使用本地兜底',UNAVAILABLE:'知识检索暂不可用，启用本地兜底回复'}
  return <details className="knowledge-sources rag-trace"><summary>查看知识依据 (RAG)</summary>
    <p>以下为实际检索记录；命中不等于被引用，引用请见来源与审核状态。分数用于排序，不表示建议有效性的概率。</p>
    {trace.map(({trigger,result},index)=><section key={index} aria-label={`第 ${index+1} 次检索`}>
      <strong>{index+1}. {trigger==='initial'?'初始检索':trigger==='action_search'?'行动依据检索':'Agent 动态检索'}</strong>
      <p>{result.mode.includes('pgvector')?'pgvector':'内存'} · BM25 + 词面向量 · RRF 融合排序</p>
      <small>筛选：{[result.filters.timeLimit?`不超过 ${result.filters.timeLimit} 分钟`:null,result.filters.scene?scenes[result.filters.scene]:null,result.filters.cognitiveLoad?`负荷不高于${loads[result.filters.cognitiveLoad]}`:null,result.filters.emotion?emotions[result.filters.emotion]:null].filter(Boolean).join(' / ')||'未限定时间、场景或负荷'}</small>
      <p>{result.status==='MATCHED'?`已检索 ${result.hits.length} 条知识`:statuses[result.status]??'本轮未使用检索依据'}</p>
      {result.hits.map(hit=><article key={hit.citation.id}>
        <strong>{hit.citation.title}</strong><small className="rag-id">{hit.citation.id}</small>
        <p>词面向量相似度 {hit.vectorSimilarity.toFixed(3)} · BM25 {hit.bm25.toFixed(3)} · RRF {hit.fusionScore.toFixed(4)}</p>
        <p>{hit.citation.content}</p>
        <small>{hit.citation.sourceRelation}</small>
        <p>{/^https:\/\//.test(hit.citation.source)?<a href={hit.citation.source} target="_blank" rel="noopener noreferrer">{hit.citation.sourceLocator||'查看参考资料'}</a>:hit.citation.source}</p>
        <small>审核状态：{hit.citation.reviewStatus==='APPROVED'?'已审阅':'待审阅'} · {hit.citation.reviewer} {hit.citation.reviewedAt}</small>
      </article>)}
    </section>)}
  </details>
}
function SourceLabels({citations,activitySources}:{citations:Citation[];activitySources:Citation[]}) {
  const items = [...citations, ...activitySources.filter(item=>!citations.some(c=>c.id===item.id))]
  if (!items.length) return null
  return <details className="knowledge-sources"><summary>来源与审核状态（{items.length}）</summary>{items.map(item=><div key={item.id}>
    <p>{citations.some(c=>c.id===item.id)?'本轮引用':'行动来源'}：{item.title}</p>
    <small>审核状态：{item.reviewStatus==='APPROVED'?'已审阅':item.reviewStatus==='REJECTED'?'不予采用':'待审阅'} · 版本 {item.version}{item.reviewStatus==='APPROVED'?` · ${item.reviewer} / ${item.reviewedAt}`:''}</small>
    <p>{/^https:\/\//.test(item.source)?<a href={item.source} target="_blank" rel="noopener noreferrer">查看参考资料</a>:item.source}</p>
    <small>{item.boundary}</small>
  </div>)}</details>
}
type Citation = { id:string; title:string; source:string; content:string; sourceLocator?:string; sourceRelation?:string; boundary:string; version:string; reviewStatus:'PENDING'|'APPROVED'|'REJECTED'; reviewer:string; reviewedAt:string }
type Reply = { text: string; source: string; notice: string; needHelp: boolean; activities: Activity[]; phase?: Phase; recommendationReason?: string; toolTrace?: string[]; confirmationToken?: string; citations?:Citation[]; activitySources?:Citation[]; retrievalStatus?:string; retrievalTrace?:RetrievalTrace[] }
type Message = { role: 'user' | 'assistant'; text: string; notice?: string; activities?: Activity[]; phase?: Phase; recommendationReason?: string; toolTrace?: string[]; confirmationToken?: string; citations?:Citation[]; activitySources?:Citation[]; retrievalStatus?:string; retrievalTrace?:RetrievalTrace[] }
export function Treehouse({garden,busy,onHelp,onBreathe,onSelect,onAdd}:{garden:Garden;busy:boolean;onHelp:()=>void;onBreathe:()=>void;onSelect:(id:string)=>void;onAdd:(activity:Activity,entryId?:string,confirmationToken?:string)=>Promise<Garden|null>}) {
  const latest=garden.entries[garden.entries.length-1]
  const [targetId,setTargetId]=useState(latest?.id??'')
  const target=garden.entries.find(entry=>entry.id===targetId)??latest
  const [adding,setAdding]=useState(false); const addLock=useRef(false)
  const [feedback,setFeedback]=useState('')
  const alreadyAdded=(activity:Activity)=>garden.tasks.some(task=>task.status==='TODO'&&task.activityId===activity.id&&(task.entryId??undefined)===target?.id)
  async function add(activity:Activity,confirmationToken?:string) {
    if(addLock.current||busy||safetyPaused||alreadyAdded(activity))return
    confirmationToken ??= messages.slice().reverse().find(message=>message.role==='assistant'&&message.activities?.some(candidate=>candidate.id===activity.id))?.confirmationToken
    addLock.current=true;setAdding(true);setFeedback('');setError('')
    try {
      const result=await onAdd(activity,target?.id,confirmationToken)
      if(result&&alive.current)setFeedback(target?'已加入任务簿，完成后会照顾这株'+plantInfo(target.plant).name+'。':'已加入任务簿。这个行动未关联植物。')
    } catch(e) {if(alive.current)setError((e as Error).message)}
    finally {addLock.current=false;if(alive.current)setAdding(false)}
  }
  const [draft,setDraft]=useState(''); const [messages,setMessages]=useState<Message[]>([])
  const [configured,setConfigured]=useState(false); const [missingConfig,setMissingConfig]=useState<string[]>([]); const [cloud,setCloud]=useState(false); const [context,setContext]=useState(false)
  const [shareFeedback,setShareFeedback]=useState(false); const [safetyPaused,setSafetyPaused]=useState(false)
  const [pending,setPending]=useState(false); const [typing,setTyping]=useState(false); const [visibleLength,setVisibleLength]=useState(0); const [error,setError]=useState(''); const [statusKnown,setStatusKnown]=useState(false)
  const lock=useRef(false); const alive=useRef(true); const bottom=useRef<HTMLDivElement>(null)
  useEffect(()=>{ alive.current=true; request<{configured:boolean;missingConfig?:string[]}>('/chat/status').then(status=>{if(alive.current){setConfigured(status.configured);setMissingConfig(status.missingConfig??[]);setStatusKnown(true)}}).catch(()=>{if(alive.current)setError('暂时无法获取豆包状态，请稍后重新打开树屋。')});return()=>{alive.current=false} },[])
  useEffect(()=>{bottom.current?.scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth',block:'nearest'})},[messages,pending])
  const lastMessage=messages[messages.length-1]
  const lastAssistant=lastMessage?.role==='assistant'?lastMessage:undefined
  useEffect(()=>{
    if(!lastAssistant){setTyping(false);setVisibleLength(0);return}
    if(matchMedia('(prefers-reduced-motion: reduce)').matches){setTyping(false);setVisibleLength(lastAssistant.text.length);return}
    setTyping(true);setVisibleLength(0)
    const timer=window.setInterval(()=>setVisibleLength(length=>{
      const next=Math.min(lastAssistant.text.length,length+3)
      if(next>=lastAssistant.text.length){window.clearInterval(timer);setTyping(false)}
      return next
    }),18)
    return()=>window.clearInterval(timer)
  },[lastAssistant])
  useEffect(()=>{if(!pending&&!typing)bottom.current?.scrollIntoView({behavior:'instant',block:'nearest'})},[pending,typing,visibleLength])
  async function send(text:string) {
    if(!text.trim()||lock.current||safetyPaused)return
    lock.current=true;setPending(true);setError('')
    try {
      const result=await request<Reply>('/chat','POST',{text:text.trim(),cloudConsent:cloud,includeGardenContext:cloud&&context,includeActionFeedback:cloud&&shareFeedback,entryId:target?.id,history:messages.slice(-12).map(({role,text,activities})=>({role,text,activityId:role==='assistant'&&activities?.[0]?.id.startsWith('exam_')?activities[0].id:undefined}))})
      if(!alive.current)return
      setMessages(previous=>[...previous,{role:'user',text:text.trim()},{role:'assistant',text:result.text,notice:result.notice,activities:result.activities,phase:result.phase,recommendationReason:result.recommendationReason,toolTrace:result.toolTrace,confirmationToken:result.confirmationToken,citations:result.citations,activitySources:result.activitySources,retrievalStatus:result.retrievalStatus,retrievalTrace:result.retrievalTrace}]);setDraft('')
      if(result.needHelp){setSafetyPaused(true);onHelp()}
    } catch(e) {if(alive.current){setError((e as Error).message);setDraft(text)}}
    finally{lock.current=false;if(alive.current)setPending(false)}
  }
  return <section className="treehouse"><header><div><Leaf size={20}/><h2>树屋倾诉</h2></div><span>{!statusKnown?'正在检查服务':configured?(cloud?'豆包 · 复习启动助手':'自助引导 · 可启用豆包'):`豆包待配置${missingConfig.length?`：${missingConfig.join('、')}`:''}`}</span></header>
    <div className="chat-consent"><label className="consent"><input type="checkbox" disabled={!configured||pending} checked={cloud} onChange={e=>{setCloud(e.target.checked);setContext(false);setShareFeedback(false);setMessages([]);setDraft('');setFeedback('')}}/>使用豆包：同意将当前消息及本次对话发送给火山引擎方舟。</label><label className="consent"><input type="checkbox" disabled={!cloud||pending} checked={context} onChange={e=>{setContext(e.target.checked);setMessages([]);setDraft('');setFeedback('')}}/>同时提供最近三天的情绪诱因与未完成任务，帮助知知理解我。</label><label className="consent"><input type="checkbox" disabled={!cloud||pending} checked={shareFeedback} onChange={e=>{setShareFeedback(e.target.checked);setMessages([]);setDraft('');setFeedback('')}}/>允许使用近期行动反馈：将近七天最多 8 条复习行动的类型、完成/跳过、难度反馈与帮助评分发送给豆包。不含植物笔记和自定义任务。</label><p>切换选项会清空本页对话。关闭后不再发送新内容，不能撤回此前已发送的数据。服务商留存以你的方舟服务条款为准。本地引导也会参考这些复习行动反馈，但不外发。</p></div>
    <div className="treehouse-target">{target?<><label htmlFor="treehouse-plant">推荐行动关联的植物</label><select id="treehouse-plant" value={target.id} disabled={busy||adding} onChange={e=>{setTargetId(e.target.value);setFeedback('')}}>{[...garden.entries].reverse().map(entry=><option key={entry.id} value={entry.id}>{plantInfo(entry.plant).name} · {entry.date} · {entry.note.slice(0,30)||'这份感受'}</option>)}</select><p className="muted-copy">完成行动后，这株植物会成长；跳过不会扣减成长。此选择只用于关联任务。</p></>:<p className="muted-copy">还没有情绪植物。推荐行动会独立加入任务簿；播种后，可以为植物添加行动。</p>}</div>
    {feedback&&<p className="form-feedback" role="status">{feedback}</p>}
    {error&&<p className="g-error" role="alert">{error}</p>}
    <div className="treehouse-messages" aria-live="polite">{messages.length===0?<div className="treehouse-empty"><Keeper pose="listen"/><h3>我是守园人知知。<br/>这次复习，最难开始的是哪一步？</h3><p>{cloud?'先了解卡点，再推荐一个复习小行动；是否加入任务由你决定。':'当前提供固定自助引导，不调用外部模型。'}</p><div className="scenario-buttons">{['考前有点慌','学不进去了','资料太多，不知道从哪开始','担心学不会'].map(text=><button key={text} disabled={pending||safetyPaused} onClick={()=>void send(text)}>{text}<ArrowRight size={14}/></button>)}</div></div>:messages.map((message,i)=>{const isLatest=i===messages.length-1&&message.role==='assistant';return <div className={`tree-message ${message.role==='user'?'user':'guide'}`} key={i}>{message.role==='assistant'&&<Keeper pose="listen"/>}<div>{message.phase&&<small className="reply-source">{{EXPLAIN:'有依据的知识说明',CLARIFY:'先了解卡点',RECOMMEND:'一个复习小行动',PAUSE:'暂不安排任务',SAFETY:'先照顾安全'}[message.phase]}</small>}<p>{isLatest?message.text.slice(0,visibleLength):message.text}</p>{message.role==='assistant'&&<><KnowledgeTrace trace={message.retrievalTrace??[]} /><SourceLabels citations={message.citations??[]} activitySources={message.activitySources??[]} /></>}{message.notice&&<small className="reply-source">{message.notice}</small>}{message.toolTrace && message.toolTrace.length > 0 && <small className="reply-source">工作流：{message.toolTrace.join(' → ')}</small>}{!safetyPaused&&message.activities?.map(activity=><article key={activity.id} className="study-action-card" aria-label="复习行动建议"><header><strong>{activity.title}</strong><span>约 {activity.minutes} 分钟</span></header><p>{activity.instructions}</p>{message.recommendationReason&&<p className="study-action-reason">为什么推荐：{message.recommendationReason}</p>}{isLatest?<><button className="g-button secondary" disabled={pending||typing||busy||adding||alreadyAdded(activity)} onClick={()=>void add(activity)}>{alreadyAdded(activity)?'已加入':`加入任务簿：${activity.title}`}</button><small>点击后才创建任务，你也可以继续聊或换一种。</small></>:<small>先前的建议 · 以最新回复为准</small>}</article>)}</div></div>})}{(pending||typing)&&<div role="status" className="typing-status"><span>{pending?(cloud?'知知正在整理回应':'正在准备引导'):'知知正在回应'}</span><i/><i/><i/></div>}<div ref={bottom}/></div>
    <div className="treehouse-actions"><button className="g-text" onClick={onHelp}>查看求助方式</button><button className="g-text" onClick={onBreathe}><Wind size={17}/>先一起呼吸两分钟</button>{!safetyPaused&&garden.entries.length>0&&<button className="g-text" onClick={()=>onSelect(garden.entries[garden.entries.length-1].id)}>打开最近植物的练习<ArrowRight size={15}/></button>}</div>
    {safetyPaused&&<p className="form-feedback" role="status">本次对话的普通复习建议已暂停，请先查看现实求助方式。没有自动通知任何人。</p>}
    <div className="chat-compose-dock"><div className="quick-emotions">{['不知道从哪里开始','资料太多','担心学不会','想先休息'].map(text=><button key={text} disabled={pending||typing||safetyPaused} onClick={()=>void send(text)}>{text}</button>)}</div><form className="tree-composer" onSubmit={e=>{e.preventDefault();void send(draft)}}><label className="sr-only" htmlFor="tree-draft">想说的话</label><input id="tree-draft" disabled={pending||typing||safetyPaused} value={draft} maxLength={1000} onChange={e=>setDraft(e.target.value)} placeholder="说说复习卡点，或你愿意先花几分钟…"/><button className="g-button" disabled={!draft.trim()||pending||typing||safetyPaused} aria-label="发送消息">发送<ArrowRight size={16}/></button></form><p className="treehouse-privacy"><ShieldCheck size={13}/>本应用不持久保存聊天正文，离开树屋清空页面记录。发送的内容经过后端处理；启用豆包后还会发送到服务商。不是紧急求助通道。</p></div></section>
}
