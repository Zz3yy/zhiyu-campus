import { test, expect, type Page } from '@playwright/test'

// Exercise the real Web Audio/WAV encoder with a browser-provided synthetic microphone.
test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] } })
const base = (process.env.E2E_API_BASE_URL ?? 'http://127.0.0.1:8080') + '/api/garden'
async function enter(page: Page, configured = true) {
  await page.route('**/api/garden/speech/status', route => route.fulfill({ json: { data: { configured, maxSeconds: 30 } } }))
  await page.goto('/')
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '进入我的花园' }).click()
  await page.getByRole('navigation').getByRole('button', { name: '树屋倾诉' }).click()
  await expect(page.getByRole('region', { name: '语音倾诉' })).toBeVisible()
}
async function cleanup(page: Page) {
  const token = await page.evaluate(() => localStorage.getItem('zhiyu.garden.token'))
  expect((await page.request.delete(base, { headers: { Authorization: `Bearer ${token}` } })).ok()).toBeTruthy()
}
async function start(page: Page) {
  await page.getByRole('checkbox', { name: /同意将本次录音/ }).check()
  await page.getByRole('button', { name: '改用点击开始' }).click()
  await expect(page.getByRole('button', { name: '停止并转写' })).toBeEnabled()
  await expect(page.locator('#voice-status')).toContainText('已录 1 秒')
}

test('real recorder produces WAV; transcription needs review and independent send, then task confirmation', async ({ page }) => {
  let calls = 0
  await page.route('**/api/garden/speech/transcriptions', async route => {
    calls++
    const body = route.request().postDataBuffer()!
    const position = body.indexOf(Buffer.from('RIFF'))
    expect(position).toBeGreaterThan(0)
    expect(body.toString('utf8')).toContain('name="consent"\r\n\r\ntrue')
    expect(body.readUInt16LE(position + 22)).toBe(1)
    expect(body.readUInt32LE(position + 24)).toBe(16000)
    expect(body.readUInt16LE(position + 34)).toBe(16)
    const dataBytes = body.readUInt32LE(position + 40)
    expect(dataBytes).toBeGreaterThan(3200)
    expect(dataBytes).toBeLessThanOrEqual(960000)
    await route.fulfill({ json: { data: { text: '复习资料太多' } } })
  })
  await enter(page)
  try {
    await expect(page.getByRole('button', { name: '按住说话' })).toBeDisabled()
    await start(page)
    await expect(page.getByLabel('想说的话')).toBeDisabled()
    await page.getByRole('button', { name: '停止并转写' }).click()
    await expect(page.getByLabel('语音转写结果')).toHaveValue('复习资料太多')
    expect(calls).toBe(1)
    await expect(page.locator('.tree-message')).toHaveCount(0)
    await expect(page.getByRole('checkbox', { name: /使用豆包：/ })).not.toBeChecked()
    await page.getByLabel('语音转写结果').fill('复习资料太多，不知道从哪里开始')
    await page.getByRole('button', { name: '放入输入框' }).click()
    await expect(page.getByLabel('想说的话')).toHaveValue('复习资料太多，不知道从哪里开始')
    await expect(page.locator('.tree-message')).toHaveCount(0)
    await page.getByRole('button', { name: '发送消息' }).click()
    await expect(page.locator('.tree-message.guide')).toHaveCount(1)
    const token = await page.evaluate(() => localStorage.getItem('zhiyu.garden.token'))
    const state = await (await page.request.get(base, { headers: { Authorization: `Bearer ${token}` } })).json()
    expect(state.data.garden.tasks).toHaveLength(0)
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    await page.screenshot({ path: 'test-results/voice-mobile.png', fullPage: true })
  } finally { await cleanup(page) }
})

test('cancel, revoke consent and leave treehouse release microphone without uploading', async ({ page }) => {
  let calls = 0
  await page.route('**/api/garden/speech/transcriptions', async route => { calls++; await route.fulfill({ json: { data: { text: '不应转写' } } }) })
  await page.addInitScript(() => {
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
    const tracks: MediaStreamTrack[] = []
    ;(window as any).__voiceTracks = tracks
    navigator.mediaDevices.getUserMedia = async options => { const stream = await original(options); tracks.push(...stream.getTracks()); return stream }
  })
  await enter(page)
  try {
    await start(page)
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await expect.poll(() => page.evaluate(() => (window as any).__voiceTracks.every((track: MediaStreamTrack) => track.readyState === 'ended'))).toBeTruthy()
    await page.getByRole('button', { name: '改用点击开始' }).click()
    await expect(page.getByRole('button', { name: '停止并转写' })).toBeEnabled()
    await page.getByRole('checkbox', { name: /同意将本次录音/ }).uncheck()
    await expect(page.getByRole('button', { name: '按住说话' })).toBeDisabled()
    await start(page)
    await page.getByRole('navigation').getByRole('button', { name: '我的花园' }).click()
    await expect.poll(() => page.evaluate(() => (window as any).__voiceTracks.every((track: MediaStreamTrack) => track.readyState === 'ended'))).toBeTruthy()
    expect(calls).toBe(0)
  } finally { await cleanup(page) }
})

test('permission denial and missing provider retain text entry', async ({ page }) => {
  await page.addInitScript(() => { navigator.mediaDevices.getUserMedia = async () => { throw new DOMException('synthetic denial', 'NotAllowedError') } })
  await enter(page)
  try {
    await page.getByRole('checkbox', { name: /同意将本次录音/ }).check()
    await page.getByRole('button', { name: '改用点击开始' }).click()
    await expect(page.getByRole('alert')).toContainText('麦克风权限未开启')
    await expect(page.getByLabel('想说的话')).toBeEnabled()
    await page.getByLabel('想说的话').fill('保留我的文字')
    await page.getByRole('navigation').getByRole('button', { name: '我的花园' }).click()
    await page.route('**/api/garden/speech/status', route => route.fulfill({ json: { data: { configured: false } } }))
    await page.getByRole('navigation').getByRole('button', { name: '树屋倾诉' }).click()
    await expect(page.getByText('语音服务尚未配置，目前可以继续打字。')).toBeVisible()
    await expect(page.getByLabel('想说的话')).toBeEnabled()
  } finally { await cleanup(page) }
})

test('30 second limit stops held recording automatically and keeps microphone tracks ended', async ({ page }) => {
  test.setTimeout(60000)
  let duration = 0
  await page.route('**/api/garden/speech/transcriptions', async route => {
    const body = route.request().postDataBuffer()!
    const position = body.indexOf(Buffer.from('RIFF'))
    duration = body.readUInt32LE(position + 40) / 32000
    await route.fulfill({ json: { data: { text: '合成录音时长测试' } } })
  })
  await page.addInitScript(() => {
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
    ;(window as any).__voiceTracks = []
    navigator.mediaDevices.getUserMedia = async options => {
      const stream = await original(options)
      ;(window as any).__voiceTracks.push(...stream.getTracks())
      return stream
    }
  })
  await enter(page)
  try {
    await page.getByRole('checkbox', { name: /同意将本次录音/ }).check()
    const button = page.getByRole('button', { name: '按住说话' })
    await button.scrollIntoViewIfNeeded()
    const box = (await button.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await expect(page.getByLabel('语音转写结果')).toHaveValue('合成录音时长测试', { timeout: 35000 })
    await page.mouse.up()
    expect(duration).toBeGreaterThan(28)
    expect(duration).toBeLessThanOrEqual(30)
    expect(await page.evaluate(() => (window as any).__voiceTracks.every((track: MediaStreamTrack) => track.readyState === 'ended'))).toBeTruthy()
  } finally { await cleanup(page) }
})

test('cancel while microphone permission is pending releases late-arriving stream', async ({ page }) => {
  await page.addInitScript(() => {
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
    ;(window as any).__voiceTracks = []
    navigator.mediaDevices.getUserMedia = async options => {
      const stream = await original(options)
      ;(window as any).__voiceTracks.push(...stream.getTracks())
      await new Promise(resolve => setTimeout(resolve, 800))
      return stream
    }
  })
  await enter(page)
  try {
    await page.getByRole('checkbox', { name: /同意将本次录音/ }).check()
    await page.getByRole('button', { name: '改用点击开始' }).click()
    await expect(page.getByRole('button', { name: '按住说话' })).toHaveText('等待麦克风权限…')
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await expect.poll(() => page.evaluate(() => {
      const tracks = (window as any).__voiceTracks as MediaStreamTrack[]
      return tracks.length > 0 && tracks.every(track => track.readyState === 'ended')
    })).toBeTruthy()
    await expect(page.getByLabel('想说的话')).toBeEnabled()
  } finally { await cleanup(page) }
})

test('press-to-talk, keyboard recording and provider errors keep existing drafts', async ({ page }) => {
  let fail = true
  await page.route('**/api/garden/speech/transcriptions', route => route.fulfill(fail
    ? { status: 502, json: { message: '模拟转写失败，可以继续打字。' } }
    : { json: { data: { text: '我不想活了' } } }))
  await enter(page)
  try {
    await page.getByLabel('想说的话').fill('原有草稿')
    await page.getByRole('checkbox', { name: /同意将本次录音/ }).check()
    const button = page.getByRole('button', { name: '按住说话' })
    await button.scrollIntoViewIfNeeded()
    const box = (await button.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await expect(page.locator('#voice-status')).toContainText('已录 1 秒')
    await page.mouse.up()
    await expect(page.getByRole('alert')).toContainText('模拟转写失败')
    await expect(page.getByLabel('想说的话')).toHaveValue('原有草稿')
    fail = false
    await button.focus(); await page.keyboard.down('Space')
    await expect(page.locator('#voice-status')).toContainText('已录 1 秒')
    await page.keyboard.up('Space')
    await expect(page.getByLabel('语音转写结果')).toHaveValue('我不想活了')
    await page.getByRole('button', { name: '放入输入框' }).click()
    await expect(page.getByLabel('想说的话')).toHaveValue('原有草稿')
    await page.getByLabel('想说的话').fill('')
    await page.getByRole('button', { name: '放入输入框' }).click()
    await page.getByRole('button', { name: '发送消息' }).click()
    await expect(page.getByRole('dialog', { name: '此刻，先照顾你的安全' })).toBeVisible()
    await expect(page.getByRole('button', { name: /加入任务簿：/ })).toHaveCount(0)
  } finally { await cleanup(page) }
})
