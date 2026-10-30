import { test, expect, type Page } from '@playwright/test'
import { chatStream } from './sse'

const base=(process.env.E2E_API_BASE_URL ?? 'http://127.0.0.1:8080')+'/api/garden'
async function enter(page: Page) {
  await page.emulateMedia({reducedMotion:'reduce'})
  await page.goto('/')
  await page.getByRole('checkbox').check()
  await page.getByRole('button',{name:'进入我的花园'}).click()
  await expect(page.locator('.garden-scene')).toBeVisible()
  const token=await page.evaluate(()=>localStorage.getItem('zhiyu.garden.token'))
  return {Authorization:`Bearer ${token}`}
}
async function openChat(page: Page) {
  await page.getByRole('navigation').getByRole('button',{name:'树屋倾诉'}).click()
}
async function send(page: Page,text: string) {
  await expect(page.getByLabel('想说的话')).toBeEnabled()
  await page.getByLabel('想说的话').fill(text)
  await page.getByRole('button',{name:'发送消息'}).click()
  await expect(page.locator('.tree-message.user').last()).toHaveText(text)
  await expect(page.getByLabel('想说的话')).toBeEnabled()
}

test('study loop clarifies, requires confirmation, adapts feedback and allows an explicit repeat',async({page})=>{
  test.setTimeout(90000) // Multiple complete conversations plus browser teardown.
  const headers=await enter(page)
  const read=async()=> (await (await page.request.get(base,{headers})).json()).data.garden
  try {
    await openChat(page)
    await send(page,'合成测试：复习资料太多')
    await expect(page.getByRole('button',{name:/加入任务簿：/})).toHaveCount(0)
    expect((await read()).tasks).toHaveLength(0)
    await send(page,'不知道从哪里开始')
    await expect(page.getByRole('button',{name:/加入任务簿：/})).toHaveCount(1)
    expect((await read()).tasks).toHaveLength(0)
    await page.getByRole('button',{name:'加入任务簿：打开资料，只看复习目录',exact:true}).click()
    await expect(page.getByRole('button',{name:'已加入',exact:true})).toBeDisabled()
    await page.getByRole('navigation').getByRole('button',{name:'任务簿',exact:true}).click()
    await page.getByRole('button',{name:'太难',exact:true}).click()
    await expect(page.getByText('已记录：太难，可用于调整后续建议')).toBeVisible()
    await openChat(page)
    await send(page,'我还想开始复习')
    await send(page,'不知道从哪里开始')
    const open=page.getByRole('button',{name:'加入任务簿：只打开一份复习资料',exact:true})
    await expect(open).toBeVisible()
    await expect(page.locator('.tree-message.guide').last()).toContainText('降低门槛')
    await open.click()
    await page.getByRole('navigation').getByRole('button',{name:'任务簿',exact:true}).click()
    await page.getByRole('button',{name:'完成行动',exact:true}).click()
    await page.getByRole('dialog').getByRole('button',{name:'5',exact:true}).click()
    await page.getByRole('dialog').getByRole('button',{name:'确认完成并保存反馈',exact:true}).click()
    await expect(page.getByText('主观帮助度：5/5')).toBeVisible()
    await openChat(page)
    await send(page,'继续复习')
    await send(page,'还是上次，再做一次')
    await expect(open).toBeEnabled()
    await open.click()
    await expect(page.getByRole('button',{name:'已加入',exact:true})).toBeDisabled()
    const tasks=(await read()).tasks
    expect(tasks).toHaveLength(3)
    expect(tasks.filter((t:{status:string})=>t.status==='TODO')).toHaveLength(1)
    await send(page,'想先休息')
    await expect(page.getByRole('button',{name:/加入任务簿：/})).toHaveCount(0)
    await expect(page.locator('.tree-message.guide').last()).toContainText('不会自动添加任务')
    await page.setViewportSize({width:390,height:844})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
    await page.getByRole('button',{name:'查看求助方式'}).click()
    await expect(page.getByRole('dialog',{name:'此刻，先照顾你的安全'})).toBeVisible()
    await page.getByRole('dialog').getByRole('button',{name:'关闭窗口'}).click()
    await page.screenshot({path:'test-results/study-agent-mobile.png',fullPage:true})
  } finally {expect((await page.request.delete(base,{headers})).ok()).toBeTruthy()}
})

test('cloud, garden and feedback consents are independent and revocation clears history',async({page})=>{
  const headers=await enter(page)
  const bodies:Array<Record<string,unknown>>=[]
  await page.route('**/api/garden/chat/status',route=>route.fulfill({json:{data:{configured:true}}}))
  await page.route('**/api/garden/chat/stream',async route=>{
    bodies.push(route.request().postDataJSON())
    await route.fulfill({status:200,contentType:'text/event-stream',body:chatStream({text:'合成测试回复：先了解复习卡点。',source:'doubao',notice:'模拟豆包',needHelp:false,phase:'CLARIFY',activities:[]})})
  })
  try {
    await openChat(page)
    const cloud=page.getByRole('checkbox',{name:/使用豆包：/})
    const garden=page.getByRole('checkbox',{name:/同时提供最近三天/})
    const feedback=page.getByRole('checkbox',{name:/允许使用近期行动反馈/})
    await expect(feedback).not.toBeChecked();await expect(feedback).toBeDisabled()
    await cloud.check();await send(page,'考试压力')
    expect(bodies.at(-1)).toMatchObject({cloudConsent:true,includeGardenContext:false,includeActionFeedback:false})
    await feedback.check();await expect(page.locator('.tree-message')).toHaveCount(0)
    await send(page,'再开始')
    expect(bodies.at(-1)).toMatchObject({includeGardenContext:false,includeActionFeedback:true,history:[]})
    await garden.check();await send(page,'两个授权都打开')
    expect(bodies.at(-1)).toMatchObject({includeGardenContext:true,includeActionFeedback:true,history:[]})
    await feedback.uncheck();await send(page,'撤销反馈授权')
    expect(bodies.at(-1)).toMatchObject({includeGardenContext:true,includeActionFeedback:false,history:[]})
    await cloud.uncheck();await expect(garden).not.toBeChecked();await expect(feedback).not.toBeChecked()
    await send(page,'只用本地引导')
    expect(bodies.at(-1)).toMatchObject({cloudConsent:false,includeGardenContext:false,includeActionFeedback:false,history:[]})
    await page.setViewportSize({width:390,height:844})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  } finally {expect((await page.request.delete(base,{headers})).ok()).toBeTruthy()}
})

test('safety pauses the composer and removes older recommendation buttons',async({page})=>{
  const headers=await enter(page)
  try {
    await openChat(page);await send(page,'合成测试：复习有压力');await send(page,'资料太多')
    await expect(page.getByRole('button',{name:/加入任务簿：/})).toHaveCount(1)
    await page.getByLabel('想说的话').fill('合成安全测试：不想活了')
    await page.getByRole('button',{name:'发送消息'}).click()
    const help=page.getByRole('dialog',{name:'此刻，先照顾你的安全'})
    await expect(help).toBeVisible();await help.getByRole('button',{name:'关闭窗口'}).click()
    await expect(page.getByLabel('想说的话')).toBeDisabled()
    await expect(page.getByRole('button',{name:/加入任务簿：/})).toHaveCount(0)
    await expect(page.getByText('本次对话的普通复习建议已暂停',{exact:false})).toBeVisible()
    await page.getByRole('button',{name:'查看求助方式'}).click();await expect(help).toBeVisible()
    const state=(await(await page.request.get(base,{headers})).json()).data.garden
    expect(state.tasks).toHaveLength(0)
  } finally {expect((await page.request.delete(base,{headers})).ok()).toBeTruthy()}
})

test('screenshot conversation changes suggestions after urgency and requests for another method',async({page})=>{
  const headers=await enter(page)
  const replies:Array<{text:string;activities:Array<{id:string}>;recommendationReason:string}>=[]
  const sent:Array<{history:Array<{role:string;activityId?:string}>}>=[]
  // Record real local-policy responses, not a scripted replacement.
  page.on('response',async response=>{
    if(response.url()===base+'/chat/stream'&&response.ok()) {
      const match=(await response.text()).match(/event: complete\r?\ndata: (.+)\r?\n/)
      if(match) replies.push(JSON.parse(match[1]))
    }
  })
  page.on('request',request=>{if(request.url()===base+'/chat/stream')sent.push(request.postDataJSON())})
  try {
    const created=await page.request.post(base+'/tasks',{headers,data:{activityId:'exam_outline'}})
    const taskId=(await created.json()).data.tasks[0].id
    expect((await page.request.put(base+'/tasks/'+taskId,{headers,data:{status:'DONE',helpfulScore:5}})).ok()).toBeTruthy()
    await page.reload();await expect(page.locator('.garden-scene')).toBeVisible();await openChat(page)
    await send(page,'担心学不会')
    await expect(page.locator('.tree-message.guide').last()).toContainText('概念')
    await expect(page.getByRole('button',{name:/加入任务簿：/})).toHaveCount(0)
    await send(page,'不知道从哪里开始')
    await send(page,'我的事情太多了 再不做下去的话就来不及了')
    await expect(page.locator('.tree-message.guide').last()).toContainText('来不及')
    await send(page,'没有别的办法吗')
    await expect(page.locator('.tree-message.guide').last()).toContainText('换')
    await expect.poll(()=>replies.length).toBe(4)
    const recommendations=replies.slice(1)
    expect(new Set(recommendations.map(r=>r.activities[0].id)).size).toBe(3)
    expect(new Set(recommendations.map(r=>r.text)).size).toBe(3)
    expect(recommendations.every(r=>!!r.recommendationReason)).toBeTruthy()
    expect(sent.at(-1)?.history.some(m=>m.role==='assistant'&&m.activityId===replies[2].activities[0].id)).toBeTruthy()
    await expect(page.getByRole('button',{name:/加入任务簿：/})).toHaveCount(1)
    await expect(page.locator('.tree-message.guide').last().locator('.study-action-card')).toContainText('为什么推荐')
    const state=(await(await page.request.get(base,{headers})).json()).data.garden
    expect(state.tasks).toHaveLength(1)
    await page.screenshot({path:'test-results/study-conversation-desktop.png',fullPage:true})
    await page.setViewportSize({width:390,height:844})
    await page.locator('.study-action-card').last().scrollIntoViewIfNeeded()
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
    await page.screenshot({path:'test-results/study-conversation-mobile.png',fullPage:true})
  } finally {expect((await page.request.delete(base,{headers})).ok()).toBeTruthy()}
})
