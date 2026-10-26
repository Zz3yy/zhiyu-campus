import { test, expect } from '@playwright/test'

test('treehouse recommendations grow only their linked plant and survive reload', async ({page}) => {
  await page.goto('/')
  await page.getByRole('checkbox').check()
  await page.getByRole('button',{name:'进入我的花园'}).click()
  await expect(page.locator('.garden-scene')).toBeVisible()
  const token=await page.evaluate(()=>localStorage.getItem('zhiyu.garden.token'))
  const headers={Authorization:`Bearer ${token}`}
  const base=(process.env.E2E_API_BASE_URL ?? 'http://127.0.0.1:8080')+'/api/garden'
  const read=async()=> (await (await page.request.get(base,{headers})).json()).data
  const initial=await read()
  const activity=initial.activities.find((a:{id:string})=>a.id==='breathe')
  await page.route('**/api/garden/chat/status',route=>route.fulfill({json:{data:{configured:true}}}))
  await page.route('**/api/garden/chat',async route=>{
    expect(route.request().postDataJSON().cloudConsent).toBe(true)
    await route.fulfill({json:{data:{text:'合成测试回复：试试一个小行动。',source:'doubao',notice:'模拟豆包回复',needHelp:false,activities:[activity]}}})
  })
  const chat=async()=>{
    await page.getByRole('navigation').getByRole('button',{name:'树屋倾诉'}).click()
    await page.getByRole('checkbox',{name:/使用豆包：/}).check()
    await page.getByRole('button',{name:'考前有点慌'}).click()
  }
  const add=()=>page.getByRole('button',{name:'加入任务簿：给呼吸两分钟',exact:true})
  try {
    await chat()
    await expect(page.getByText('还没有情绪植物。',{exact:false})).toBeVisible()
    await add().click()
    await expect(page.getByRole('button',{name:'已加入',exact:true})).toBeDisabled()
    expect((await read()).garden.tasks[0].entryId).toBeFalsy()
    for(const requestId of ['first','second']) {
      expect((await page.request.post(base+'/entries',{headers,data:{requestId,plant:'mimosa',intensity:3,note:'合成测试 '+requestId,timezone:'Asia/Shanghai'}})).ok()).toBeTruthy()
    }
    await page.reload()
    const entries=(await read()).garden.entries
    await chat()
    await expect(page.getByLabel('推荐行动关联的植物')).toHaveValue(entries[1].id)
    await page.route('**/api/garden/tasks',route=>route.abort(),{times:1})
    await add().click()
    await expect(page.getByRole('alert')).toContainText('暂时无法连接花园')
    await expect(add()).toBeEnabled()
    await add().click()
    await expect(page.getByRole('button',{name:'已加入',exact:true})).toBeDisabled()
    await page.getByLabel('推荐行动关联的植物').selectOption(entries[0].id)
    await expect(add()).toBeEnabled()
    await add().click()
    await expect(page.getByRole('button',{name:'已加入',exact:true})).toBeDisabled()
    expect((await read()).garden.tasks).toHaveLength(3)
    await page.setViewportSize({width:390,height:844})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBeTruthy()
    await page.screenshot({path:'test-results/treehouse-growth-mobile.png',fullPage:true})
    await page.getByRole('navigation').getByRole('button',{name:'任务簿',exact:true}).click()
    const plantTasks=page.locator('.task-book > .g-section').filter({has:page.getByRole('heading',{name:'来自情绪植物',exact:true})})
    await plantTasks.locator('.book-task').first().getByRole('button',{name:'完成行动'}).click()
    await page.getByRole('dialog').getByRole('button',{name:'确认完成',exact:true}).click()
    await expect(plantTasks.locator('.book-task').first()).toContainText('长出新叶')
    await plantTasks.locator('.book-task').last().getByRole('button',{name:'今天先跳过'}).click()
    await expect(plantTasks.locator('.book-task').last()).toContainText('刚刚萌芽')
    await page.reload()
    await page.getByRole('navigation').getByRole('button',{name:'任务簿',exact:true}).click()
    await expect(plantTasks.locator('.book-task').first()).toContainText('长出新叶')
    await plantTasks.locator('.book-task').first().getByRole('button',{name:'看植物'}).click()
    await expect(page.getByRole('dialog')).toContainText('长出新叶')
    const tasks=(await read()).garden.tasks
    expect(tasks.find((t:{entryId:string})=>t.entryId===entries[0].id).status).toBe('DONE')
    expect(tasks.find((t:{entryId:string})=>t.entryId===entries[1].id).status).toBe('SKIPPED')
  } finally { expect((await page.request.delete(base,{headers})).ok()).toBeTruthy() }
})
