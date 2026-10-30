export function chatStream(reply: Record<string, unknown>) {
  const chunks: string[] = []
  const text = String(reply.text ?? '')
  const points = Array.from(text)
  for (let index = 0; index < points.length; index += 16) chunks.push(points.slice(index, index + 16).join(''))
  const event = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
  return event('status', { message: '正在整理回应。' })
    + chunks.map(chunk => event('delta', { text: chunk })).join('')
    + event('complete', reply)
}
