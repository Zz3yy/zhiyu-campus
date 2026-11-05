import { test, expect, type Page } from '@playwright/test'
import { chatStream } from './sse'

const base = (process.env.E2E_API_BASE_URL ?? 'http://127.0.0.1:8080') + '/api/garden'
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/n2cAAAAASUVORK5CYII=', 'base64')
async function enter(page: Page) {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.route('**/api/garden/images/status', route => route.fulfill({ json: { data: { configured: true, maxBytes: 5242880, maxSide: 4096, maxPixels: 12000000 } } }))
  await page.route('**/api/garden/chat/status', route => route.fulfill({ json: { data: { configured: true, missingConfig: [] } } }))
  await page.goto('/')
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '进入我的花园' }).click()
  const token = await page.evaluate(() => localStorage.getItem('zhiyu.garden.token'))
  await page.getByRole('navigation').getByRole('button', { name: '树屋倾诉' }).click()
  return token
}
test('image needs independent consent, creates an editable draft and reaches chat only on explicit send', async ({ page }) => {
  await enter(page)
  let imageCalls = 0
  const chatBodies: Array<Record<string, unknown>> = []
  await page.route('**/api/garden/images/drafts', async route => {
    imageCalls++
    const body = route.request().postDataBuffer()!
    expect(body.toString('utf8')).toContain('name="image"')
    expect(body.toString('utf8')).toContain('name="consent"\r\n\r\ntrue')
    await route.fulfill({ json: { data: { text: '资料太多，不知道从哪里开始' } } })
  })
  await page.route('**/api/garden/chat/stream', async route => {
    chatBodies.push(route.request().postDataJSON())
    await route.fulfill({ status: 200, contentType: 'text/event-stream', body: chatStream({ text: '我们先看看哪一份资料最急。', source: 'local', notice: '', needHelp: false, phase: 'CLARIFY', activities: [] }) })
  })
  await page.getByRole('checkbox', { name: /使用豆包：/ }).check()
    const picker = page.getByLabel('选择复习卡点图片')
    await picker.setInputFiles({ name: 'note.png', mimeType: 'image/png', buffer: png })
    await expect(page.getByRole('img', { name: '待识别图片预览' })).toBeVisible()
    await expect(page.getByRole('button', { name: '识别图片文字' })).toBeDisabled()
    await page.getByRole('checkbox', { name: /同意将这张图片发送给豆包/ }).check()
    await page.getByRole('button', { name: '识别图片文字' }).click()
    await expect(page.getByLabel('待校对的文字草稿')).toHaveValue('资料太多，不知道从哪里开始')
    expect(imageCalls).toBe(1)
    expect(chatBodies).toHaveLength(0)
    await page.getByLabel('想说的话').fill('已有文字草稿')
    await page.getByRole('button', { name: '放入输入框' }).click()
    await expect(page.getByRole('alert')).toContainText('输入框里还有文字')
    await expect(page.getByLabel('想说的话')).toHaveValue('已有文字草稿')
    await page.getByLabel('想说的话').fill('')
    await page.getByRole('button', { name: '放入输入框' }).click()
    await expect(page.getByLabel('想说的话')).toHaveValue('资料太多，不知道从哪里开始')
    await expect(page.getByLabel('待校对的文字草稿')).toHaveCount(0)
    expect(chatBodies).toHaveLength(0)
    await page.getByRole('button', { name: '发送消息' }).click()
    await expect(page.locator('.tree-message.guide').last()).toContainText('哪一份资料最急')
    expect(chatBodies).toHaveLength(1)
  expect(chatBodies[0]).toMatchObject({ text: '资料太多，不知道从哪里开始', cloudConsent: true, history: [] })
})

test('invalid formats and oversized images are rejected before any provider request', async ({ page }) => {
  await enter(page)
  let calls = 0
  await page.route('**/api/garden/images/drafts', route => { calls++; return route.fulfill({ json: { data: { text: '不应请求' } } }) })
  const picker = page.getByLabel('选择复习卡点图片')
    await picker.setInputFiles({ name: 'notes.gif', mimeType: 'image/gif', buffer: Buffer.from('GIF89a') })
    await expect(page.getByRole('alert')).toContainText('请选择 JPG 或 PNG')
    await picker.setInputFiles({ name: 'large.jpg', mimeType: 'image/jpeg', buffer: Buffer.alloc(5 * 1024 * 1024 + 1) })
    await expect(page.getByRole('alert')).toContainText('不能超过 5 MB')
  expect(calls).toBe(0)
})

test('revoking image consent removes the selected photo and clearing an in-flight upload cancels its response', async ({ page }) => {
  await enter(page)
  let arrived = false
  await page.route('**/api/garden/images/drafts', async route => {
    arrived = true
    await new Promise(resolve => setTimeout(resolve, 250))
    try { await route.abort() } catch { /* The browser may have cancelled the upload first. */ }
  })
  const picker = page.getByLabel('选择复习卡点图片')
    await picker.setInputFiles({ name: 'note.png', mimeType: 'image/png', buffer: png })
    const consent = page.getByRole('checkbox', { name: /同意将这张图片发送给豆包/ })
    await consent.check()
    await page.getByRole('button', { name: '识别图片文字' }).click()
    await expect.poll(() => arrived).toBeTruthy()
    await consent.evaluate(element => (element as HTMLInputElement).click())
    await expect(page.getByRole('img', { name: '待识别图片预览' })).toHaveCount(0)
  await expect(page.getByLabel('待校对的文字草稿')).toHaveCount(0)
})
