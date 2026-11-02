const RATE = 16000
const MAX_SECONDS = 30

/** PCM WAV keeps the provider contract independent of browser-specific MediaRecorder codecs. */
function wav(chunks: Float32Array[], inputRate: number): Blob {
  const length = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const input = new Float32Array(length)
  let offset = 0
  for (const chunk of chunks) { input.set(chunk, offset); offset += chunk.length }
  const count = Math.min(RATE * MAX_SECONDS, Math.floor(length * RATE / inputRate))
  if (count < RATE / 10) throw new Error('录音太短，请说完一句话再试。')
  const buffer = new ArrayBuffer(44 + count * 2)
  const view = new DataView(buffer)
  const label = (at: number, text: string) => [...text].forEach((char, i) => view.setUint8(at + i, char.charCodeAt(0)))
  label(0, 'RIFF'); view.setUint32(4, buffer.byteLength - 8, true); label(8, 'WAVE'); label(12, 'fmt ')
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true)
  view.setUint32(24, RATE, true); view.setUint32(28, RATE * 2, true)
  view.setUint16(32, 2, true); view.setUint16(34, 16, true); label(36, 'data'); view.setUint32(40, count * 2, true)
  for (let i = 0; i < count; i++) {
    const from = Math.floor(i * inputRate / RATE)
    const to = Math.min(length, Math.max(from + 1, Math.floor((i + 1) * inputRate / RATE)))
    let sample = 0
    for (let index = from; index < to; index++) sample += input[index]
    sample = Math.max(-1, Math.min(1, sample / (to - from)))
    view.setInt16(44 + i * 2, Math.round(sample * (sample < 0 ? 32768 : 32767)), true)
  }
  return new Blob([buffer], { type: 'audio/wav' })
}

export class VoiceRecorder {
  private stream?: MediaStream
  private context?: AudioContext
  private source?: MediaStreamAudioSourceNode
  private node?: AudioWorkletNode
  private chunks: Float32Array[] = []
  private cancelled = false
  private stopped = false
  private sampleCount = 0
  private resolveStop?: () => void

  static supported() {
    return window.isSecureContext && !!navigator.mediaDevices?.getUserMedia && !!window.AudioContext && !!window.AudioWorkletNode
  }

  async start(onLimit: () => void) {
    if (!VoiceRecorder.supported()) throw new Error('当前浏览器无法录音，请使用 HTTPS 或本机地址，也可以继续打字。')
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }, video: false })
    this.stream = stream
    if (this.cancelled) { this.release(); return false }
    try {
      const context = new AudioContext({ sampleRate: RATE })
      this.context = context
      await context.audioWorklet.addModule(new URL('./voice-capture.worklet.js', import.meta.url))
      if (this.cancelled) { this.release(); return false }
      this.node = new AudioWorkletNode(context, 'zhiyu-voice-capture', { channelCount: 1, numberOfOutputs: 1, outputChannelCount: [1] })
      this.node.port.onmessage = ({ data }) => {
        if (this.cancelled) return
        if (data.type === 'samples' && data.chunk instanceof Float32Array) {
          this.sampleCount += data.chunk.length
          if (this.sampleCount <= context.sampleRate * MAX_SECONDS) this.chunks.push(data.chunk)
        } else if (data.type === 'stopped') {
          this.stopped = true
          this.resolveStop?.()
          if (data.limit) onLimit()
        }
      }
      this.source = context.createMediaStreamSource(stream)
      this.source.connect(this.node)
      this.node.connect(context.destination)
      await context.resume()
      if (this.cancelled) { this.release(); return false }
      return true
    } catch (error) { this.release(); throw error }
  }

  async finish(): Promise<Blob> {
    if (!this.node || !this.context || this.cancelled) throw new Error('录音已取消，请重新录制。')
    const rate = this.context.sampleRate
    try {
      if (!this.stopped) await new Promise<void>((resolve, reject) => {
        const timer = window.setTimeout(() => reject(new Error('录音未能完成，请重新录制。')), 1500)
        this.resolveStop = () => { window.clearTimeout(timer); resolve() }
        this.node!.port.postMessage('stop')
      })
      if (this.cancelled) throw new Error('录音已取消。')
      return wav(this.chunks, rate)
    } finally { this.release() }
  }

  cancel() { this.cancelled = true; this.resolveStop?.(); this.release() }

  private release() {
    this.stream?.getTracks().forEach(track => track.stop())
    this.node?.disconnect(); this.source?.disconnect()
    this.node?.port.close()
    void this.context?.close().catch(() => {})
    this.chunks = []
  }
}
