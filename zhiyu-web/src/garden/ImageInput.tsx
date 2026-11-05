import { useEffect, useRef, useState } from 'react'
import { ImagePlus, LoaderCircle, RotateCcw, ScanText, X } from 'lucide-react'
import { request, transcribeImage } from './api'

const MAX_BYTES = 5 * 1024 * 1024
const MAX_SIDE = 4096
const MAX_PIXELS = 12_000_000
type Stage = 'idle' | 'recognizing'

export function ImageInput({ disabled, onUse, onBusyChange }: { disabled: boolean; onUse: (text: string) => boolean; onBusyChange: (busy: boolean) => void }) {
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState('')
  const [consent, setConsent] = useState(false)
  const [configured, setConfigured] = useState<boolean | null>(null)
  const [stage, setStage] = useState<Stage>('idle')
  const [text, setText] = useState('')
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const upload = useRef<AbortController | null>(null)
  const alive = useRef(true)
  const picker = useRef<HTMLInputElement>(null)
  const lock = useRef(false)
  const selection = useRef(0)
  const decoding = useRef<{ image: HTMLImageElement; url: string } | null>(null)
  const busyCallback = useRef(onBusyChange)
  busyCallback.current = onBusyChange

  function releaseDecode() {
    if (!decoding.current) return
    decoding.current.image.onload = null; decoding.current.image.onerror = null
    decoding.current.image.removeAttribute('src'); URL.revokeObjectURL(decoding.current.url); decoding.current = null
  }

  useEffect(() => {
    alive.current = true
    request<{ configured: boolean }>('/images/status').then(value => { if (alive.current) setConfigured(value.configured) })
      .catch(() => { if (alive.current) setConfigured(false) })
    return () => { alive.current = false; selection.current++; releaseDecode(); upload.current?.abort(); busyCallback.current(false) }
  }, [])
  useEffect(() => {
    if (!file) { setPreview(''); return }
    const url = URL.createObjectURL(file)
    setPreview(url)
    return () => URL.revokeObjectURL(url)
  }, [file])

  function choose(next: File | undefined) {
    if (!next) return
    const id = ++selection.current
    releaseDecode(); setFile(null); setConsent(false); setError(''); setText(''); setNotice('')
    if (!['image/jpeg', 'image/png'].includes(next.type)) { setFile(null); setConsent(false); setError('请选择 JPG 或 PNG 图片。'); return }
    if (next.size > MAX_BYTES) { setFile(null); setConsent(false); setError('图片不能超过 5 MB。'); return }
    const previewUrl = URL.createObjectURL(next)
    const image = new Image()
    decoding.current = { image, url: previewUrl }
    image.onload = () => {
      URL.revokeObjectURL(previewUrl)
      decoding.current = null
      if (!alive.current || id !== selection.current) return
      if (image.naturalWidth > MAX_SIDE || image.naturalHeight > MAX_SIDE || image.naturalWidth * image.naturalHeight > MAX_PIXELS) {
        setFile(null); setConsent(false); setError('图片尺寸过大，请先裁剪卡点部分（最长边 4096 像素，总像素不超过 1200 万）。'); return
      }
      setFile(next); setConsent(false)
    }
    image.onerror = () => { URL.revokeObjectURL(previewUrl); decoding.current = null; if (!alive.current || id !== selection.current) return; setFile(null); setConsent(false); setError('无法读取这张图片，请重新选择完整的 JPG 或 PNG。') }
    image.src = previewUrl
  }
  function clear() {
    selection.current++; releaseDecode()
    upload.current?.abort(); upload.current = null; lock.current = false
    busyCallback.current(false)
    setFile(null); setText(''); setConsent(false); setStage('idle'); setNotice('图片已移除；已发送给服务商的内容无法撤回。'); setError('')
    if (picker.current) picker.current.value = ''
  }
  async function recognize() {
    if (!file || !consent || lock.current || disabled) return
    lock.current = true; busyCallback.current(true); setText(''); setStage('recognizing'); setError(''); setNotice('正在整理图片中的文字，仅生成待校对草稿。')
    const controller = new AbortController(); upload.current = controller
    try {
      const result = await transcribeImage(file, controller.signal)
      if (!alive.current || controller.signal.aborted) return
      setText(result); setNotice('草稿生成了，请核对和修改；文字尚未发送给聊天助手。')
    } catch (e) {
      if (alive.current && !controller.signal.aborted) { setNotice(''); setError((e as Error).message) }
    } finally {
      if (upload.current === controller) {
        upload.current = null; lock.current = false
        if (alive.current) { setStage('idle'); busyCallback.current(false) }
      }
    }
  }
  return <section className="image-input" aria-label="图片识别复习卡点">
    <div className="image-intro"><ImagePlus size={16}/><span>拍下复习卡点，少打几行字</span><small>JPG / PNG · 最多 5 MB</small></div>
    <p className="image-note">可上传手写压力纸条或复习卡点图片。只整理清晰文字，不解题、不诊断；看图结果需要你校对。</p>
    <input ref={picker} className="sr-only" type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" disabled={disabled || stage !== 'idle'} aria-label="选择复习卡点图片" onChange={e=>choose(e.target.files?.[0])}/>
    {!file ? <button type="button" className="g-button secondary image-pick" disabled={disabled || configured === false || configured === null} onClick={()=>picker.current?.click()}><ImagePlus size={17}/>{configured === null ? '正在检查图片服务' : configured ? '选择一张卡点图片' : '图片识别尚未配置'}</button> : <div className="image-preview">{preview && <img src={preview} alt="待识别图片预览"/>}<div><strong>{file.name}</strong><small>{(file.size / 1024).toFixed(0)} KB · 仅本次请求使用</small></div><button type="button" className="g-icon" aria-label="移除图片" title="移除图片" disabled={stage !== 'idle'} onClick={clear}><X size={16}/></button><button type="button" className="g-text" disabled={disabled || stage !== 'idle'} onClick={()=>picker.current?.click()}><RotateCcw size={15}/>更换</button></div>}
    {configured === false && <p className="image-note">当前图片识别暂不可用，可以继续打字。</p>}
    {file && <label className="consent image-consent"><input type="checkbox" checked={consent} disabled={disabled} onChange={e=>{setConsent(e.target.checked);if(!e.target.checked)clear()}}/>同意将这张图片发送给豆包，仅提取文字生成草稿；是否把文字发送给聊天助手由我另行决定。</label>}
    {file && <div className="image-controls"><button type="button" className="g-button" disabled={disabled || !consent || stage !== 'idle'} onClick={()=>void recognize()}>{stage === 'recognizing' ? <><LoaderCircle size={16} className="spin"/>正在识别</> : <><ScanText size={16}/>识别图片文字</>}</button>{stage === 'recognizing' && <button type="button" className="g-text" onClick={clear}><X size={15}/>取消</button>}</div>}
    {notice && <p className="image-note" role="status">{notice}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    {text && <div className="image-review"><label htmlFor="image-draft">待校对的文字草稿</label><textarea id="image-draft" value={text} maxLength={1000} disabled={disabled || stage !== 'idle'} onChange={e=>setText(e.target.value)}/><div><button type="button" className="g-button secondary" disabled={disabled || stage !== 'idle' || !text.trim()} onClick={()=>{if(onUse(text.trim())){setText('');setNotice('文字已放入输入框，请检查后点击发送。')}}}>放入输入框</button><button type="button" className="g-text" disabled={stage !== 'idle'} onClick={()=>{setText('');setNotice('已丢弃识别草稿。')}}>丢弃草稿</button></div></div>}
    <p className="image-note">应用不持久保存图片。发送给服务商后无法撤回，留存以方舟服务条款为准。文字经你发送后进入当前选择的聊天模式。</p>
  </section>
}
