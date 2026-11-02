import { useEffect, useRef, useState } from 'react'
import { LoaderCircle, Mic, Square, X } from 'lucide-react'
import { request, transcribeVoice } from './api'
import { VoiceRecorder } from './voice-recorder'

type Stage = 'idle' | 'permission' | 'recording' | 'transcribing'
type Props = { disabled: boolean; onBusyChange: (busy: boolean) => void; onUse: (text: string) => boolean }

export function VoiceInput({ disabled, onBusyChange, onUse }: Props) {
  const [configured, setConfigured] = useState<boolean | null>(null)
  const [consent, setConsent] = useState(false)
  const [stage, setStage] = useState<Stage>('idle')
  const [seconds, setSeconds] = useState(0)
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const recorder = useRef<VoiceRecorder | null>(null)
  const upload = useRef<AbortController | null>(null)
  const alive = useRef(true)
  const run = useRef(0)
  const active = useRef<Stage>('idle')
  const press = useRef<number | null>(null)
  const heldKey = useRef(false)
  const busyCallback = useRef(onBusyChange)
  busyCallback.current = onBusyChange
  const supported = VoiceRecorder.supported()

  function transition(next: Stage) {
    active.current = next
    setStage(next)
    busyCallback.current(next !== 'idle')
  }
  function cancel() {
    run.current++
    recorder.current?.cancel(); recorder.current = null
    upload.current?.abort(); upload.current = null
    press.current = null
    heldKey.current = false
    if (alive.current) { transition('idle'); setNotice('本次录音或转写已取消。'); setError('') }
  }
  useEffect(() => {
    alive.current = true
    request<{ configured: boolean }>('/speech/status').then(value => { if (alive.current) setConfigured(value.configured) })
      .catch(() => { if (alive.current) setError('暂时无法检查语音服务，可以继续打字。') })
    return () => {
      alive.current = false; run.current++
      recorder.current?.cancel(); upload.current?.abort()
      busyCallback.current(false)
    }
  }, [])
  useEffect(() => { if (disabled && active.current !== 'idle') cancel() }, [disabled])
  useEffect(() => {
    const interrupt = () => { if (active.current !== 'idle' && document.hidden) cancel() }
    const leave = () => { if (active.current !== 'idle') cancel() }
    document.addEventListener('visibilitychange', interrupt)
    window.addEventListener('pagehide', leave)
    return () => { document.removeEventListener('visibilitychange', interrupt); window.removeEventListener('pagehide', leave) }
  }, [])
  useEffect(() => {
    if (stage !== 'recording') return
    const start = performance.now()
    const timer = window.setInterval(() => {
      const elapsed = Math.min(30, Math.floor((performance.now() - start) / 1000))
      setSeconds(elapsed)
      if (elapsed >= 30) void finish()
    }, 200)
    return () => window.clearInterval(timer)
  }, [stage])

  async function start() {
    if (disabled || !configured || !supported || !consent || active.current !== 'idle') return
    const id = ++run.current
    const capture = new VoiceRecorder()
    recorder.current = capture
    setError(''); setNotice(''); setSeconds(0); transition('permission')
    try {
      const started = await capture.start(() => { if (run.current === id) void finish() })
      if (started && alive.current && run.current === id) transition('recording')
    } catch (error) {
      if (!alive.current || run.current !== id) return
      recorder.current = null; transition('idle')
      setError(error instanceof DOMException && error.name === 'NotAllowedError'
        ? '麦克风权限未开启，可以在浏览器中允许后重试，也可以继续打字。'
        : error instanceof DOMException && error.name === 'NotFoundError'
          ? '没有找到可用麦克风，可以连接麦克风后重试，或继续打字。'
          : '录音未能开始，请检查麦克风后重试，也可以继续打字。')
    }
  }
  async function finish() {
    if (active.current !== 'recording' || !recorder.current) return
    press.current = null; heldKey.current = false
    const id = run.current
    const capture = recorder.current
    transition('transcribing')
    setNotice('正在转写，文字确认后才会进入对话。')
    try {
      const audio = await capture.finish()
      if (!alive.current || id !== run.current) return
      recorder.current = null
      const controller = new AbortController(); upload.current = controller
      const result = await transcribeVoice(audio, controller.signal)
      if (!alive.current || id !== run.current) return
      setText(result); setNotice('转写可能有误，请检查文字。尚未发送给聊天助手。')
    } catch (error) {
      if (alive.current && id === run.current) { setNotice(''); setError((error as Error).message) }
    } finally {
      if (alive.current && id === run.current) { recorder.current = null; upload.current = null; transition('idle') }
    }
  }
  const unavailable = disabled || !consent || !configured || !supported
  return <section className="voice-input" aria-label="语音倾诉">
    <div className="voice-intro"><Mic size={16}/><span>不想打字，也可以说一句</span><small>最多 30 秒</small></div>
    <label className="consent voice-consent"><input type="checkbox" checked={consent} disabled={disabled}
      onChange={event => { setConsent(event.target.checked); if (!event.target.checked) { cancel(); setText('') } }}/>
      同意将本次录音发送给火山引擎语音识别，仅用于转文字；是否将文字发送给豆包另行选择。</label>
    {!supported ? <p className="voice-note">当前浏览器无法录音。请使用支持录音的浏览器与 HTTPS 或本机地址，也可以继续打字。</p>
      : configured === false ? <p className="voice-note">语音服务尚未配置，目前可以继续打字。</p>
      : configured === null ? <p className="voice-note">正在检查语音服务。</p> : null}
    <div className="voice-controls">
      <button type="button" className={`voice-hold ${stage === 'recording' ? 'recording' : ''}`}
        disabled={unavailable || stage === 'transcribing'} aria-label="按住说话" aria-describedby="voice-status"
        onContextMenu={event => event.preventDefault()}
        onPointerDown={event => {
          if (event.button !== 0 || active.current !== 'idle') return
          event.preventDefault(); press.current = event.pointerId; event.currentTarget.setPointerCapture(event.pointerId); void start()
        }}
        onPointerUp={event => {
          if (press.current !== event.pointerId) return
          press.current = null
          if (active.current === 'permission') cancel()
          else void finish()
        }}
        onPointerCancel={() => { if (press.current !== null) cancel() }}
        onLostPointerCapture={() => { if (press.current !== null) cancel() }}
        onKeyDown={event => { if ((event.key === ' ' || event.key === 'Enter') && !event.repeat) { event.preventDefault(); heldKey.current = true; void start() } }}
        onKeyUp={event => { if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); heldKey.current = false; if (active.current === 'permission') cancel(); else void finish() } }}
        onBlur={() => { if (press.current !== null || heldKey.current) cancel() }}>
        {stage === 'transcribing' ? <LoaderCircle size={17} className="spin"/> : <Mic size={17}/>}
        {stage === 'recording' ? `松开转写 · ${seconds} 秒` : stage === 'permission' ? '等待麦克风权限…' : stage === 'transcribing' ? '正在转写…' : '按住说话'}
      </button>
      <button type="button" className="g-text" disabled={unavailable || stage === 'permission' || stage === 'transcribing'}
        onClick={() => { if (active.current === 'recording') void finish(); else void start() }}>
        {stage === 'recording' ? <><Square size={14}/>停止并转写</> : '改用点击开始'}</button>
      {stage !== 'idle' && <button type="button" className="g-text" onClick={cancel}><X size={15}/>取消</button>}
    </div>
    <p id="voice-status" className="voice-note" role="status">{stage === 'recording' ? `录音中，已录 ${seconds} 秒；30 秒时自动停止并转写。` : notice || '录音只在主动操作时开启；转写后可校对，确认发送前不会进入对话。'}</p>
    {error && <p className="error-text" role="alert">{error}</p>}
    {text && <div className="voice-review"><label htmlFor="voice-transcript">语音转写结果</label>
      <textarea id="voice-transcript" value={text} maxLength={1000} disabled={disabled || stage !== 'idle'} onChange={event => setText(event.target.value)}/>
      <div><button type="button" className="g-button secondary" disabled={disabled || stage !== 'idle' || !text.trim()}
        onClick={() => { if (onUse(text.trim())) { setText(''); setNotice('文字已放入输入框，请检查后点击发送。') } }}>放入输入框</button>
        <button type="button" className="g-text" disabled={stage !== 'idle'} onClick={() => { setText(''); setNotice('已丢弃这段转写文字。') }}>丢弃文字</button></div>
    </div>}
    <p className="voice-note">本应用不持久保存录音。转写请求发出后，取消不能撤回服务商已接收的音频；服务商留存以实际条款为准。</p>
  </section>
}
