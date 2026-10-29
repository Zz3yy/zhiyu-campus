import { test, expect } from '@playwright/test'

test('pending sources are visible and never presented as reviewed citations', async ({page}) => {
  const base=(process.env.E2E_API_BASE_URL??'http://127.0.0.1:8080')+'/api/garden'
  await page.emulateMedia({reducedMotion:'reduce'})
  await page.goto('/')
  await page.getByRole('checkbox').check()
  await page.getByRole('button',{name:'进入我的花园'}).click()
  await expect(page.locator('.garden-scene')).toBeVisible()
  const token=await page.evaluate(()=>localStorage.getItem('zhiyu.garden.token'))
  const headers={Authorization:`Bearer ${token}`}
  try {
    const catalog=await (await page.request.get(base+'/chat/knowledge',{headers})).json()
    expect(catalog.data).toHaveLength(21)
    expect(catalog.data.every((item:{reviewStatus:string})=>item.reviewStatus==='PENDING')).toBeTruthy()
    await page.getByRole('navigation').getByRole('button',{name:'树屋倾诉'}).click()
    for(const text of ['复习资料太多','不知道从哪里开始']) {
      await page.getByLabel('想说的话').fill(text)
      await page.getByRole('button',{name:'发送消息'}).click()
      await expect(page.getByLabel('想说的话')).toBeEnabled()
    }
    const sources=page.locator('.knowledge-sources').last()
    await sources.locator('summary').click()
    await expect(sources).toContainText('审核状态：待审阅')
    await expect(sources).toContainText('行动来源')
    await expect(sources).not.toContainText('本轮引用')
    await page.setViewportSize({width:390,height:844})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
    await page.screenshot({path:'test-results/knowledge-sources-mobile.png',fullPage:true})
  } finally { expect((await page.request.delete(base,{headers})).ok()).toBeTruthy() }
})
