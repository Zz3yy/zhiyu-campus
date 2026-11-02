// The worklet records only while the user explicitly starts the microphone.
class VoiceCapture extends AudioWorkletProcessor {
  constructor() {
    super()
    this.buffer = new Float32Array(2048)
    this.offset = 0
    this.samples = 0
    this.stopped = false
    this.port.onmessage = () => this.finish(false)
  }
  flush() {
    if (!this.offset) return
    const chunk = this.buffer.slice(0, this.offset)
    this.port.postMessage({ type: 'samples', chunk }, [chunk.buffer])
    this.offset = 0
  }
  finish(limit) {
    if (this.stopped) return
    this.stopped = true
    this.flush()
    this.port.postMessage({ type: 'stopped', limit })
  }
  process(inputs) {
    if (this.stopped) return false
    const channel = inputs[0]?.[0]
    if (!channel) return true
    for (const sample of channel) {
      if (this.samples >= sampleRate * 30) { this.finish(true); return false }
      this.buffer[this.offset++] = sample
      this.samples++
      if (this.offset === this.buffer.length) this.flush()
    }
    // The output stays silent; the microphone is never played through speakers.
    return true
  }
}
registerProcessor('zhiyu-voice-capture', VoiceCapture)
