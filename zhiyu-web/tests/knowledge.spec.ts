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
    expect(catalog.data).toHaveLength(150)
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

test('RAG panel renders actual response scores, repeated searches and fallback distinctly', async ({page}) => {
  const base=(process.env.E2E_API_BASE_URL??'http://127.0.0.1:8080')+'/api/garden'
  await page.emulateMedia({reducedMotion:'reduce'})
  await page.goto('/')
  await page.getByRole('checkbox').check()
  await page.getByRole('button',{name:'进入我的花园'}).click()
  await expect(page.locator('.garden-scene')).toBeVisible()
  const token=await page.evaluate(()=>localStorage.getItem('zhiyu.garden.token'))
  const headers={Authorization:`Bearer ${token}`}
  const citation={id:'synthetic-ui-evidence',title:'界面合成验收：允许粗糙初稿',source:'https://example.invalid/reference',content:'此条仅用于界面验收，不是真实已审核内容。',sourceLocator:'合成来源',sourceRelation:'仅测试',boundary:'测试边界',version:'1',reviewStatus:'APPROVED',reviewer:'合成测试审核员',reviewedAt:'2026-10-29'}
  const filters={timeLimit:2,scene:'dormitory',cognitiveLoad:'very_low',emotion:null}
  const hit={citation,vectorSimilarity:0.421,bm25:2.735,fusionScore:0.0328}
  let turn=0
  await page.route('**/api/garden/chat/status',route=>route.fulfill({json:{success:true,data:{configured:true,missingConfig:[]}}}))
  await page.route('**/api/garden/chat',route=>route.fulfill({json:{success:true,data:{
    text:'仅用于界面回归的合成回复。',source:'fallback',notice:'合成测试',needHelp:false,activities:[],phase:'EXPLAIN',citations:[],activitySources:[],
    retrievalTrace:++turn===1?[
      {trigger:'initial',result:{mode:'memory-hybrid',status:'NO_MATCH',filters,hits:[]}},
      {trigger:'agent_search_1',result:{mode:'memory-hybrid',status:'MATCHED',filters,hits:[hit]}}
    ]:[{trigger:'initial',result:{mode:'memory-hybrid',status:'NO_APPROVED_CONTENT',filters,hits:[]}}]
  }}}))
  try {
    await page.getByRole('navigation').getByRole('button',{name:'树屋倾诉'}).click()
    await page.getByLabel('想说的话').fill('解释一下完美主义拖延')
    await page.getByRole('button',{name:'发送消息'}).click()
    const panel=page.locator('.rag-trace').last()
    await panel.getByText('查看知识依据 (RAG)').click()
    await expect(panel).toContainText('Agent 动态检索')
    await expect(panel).toContainText('不超过 2 分钟 / 宿舍 / 负荷不高于极低')
    await expect(panel).toContainText('0.421 · BM25 2.735 · RRF 0.0328')
    await expect(panel).toContainText('synthetic-ui-evidence')
    await expect(panel.getByRole('link',{name:'合成来源'})).toHaveAttribute('href','https://example.invalid/reference')
    await page.setViewportSize({width:390,height:844})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
    await panel.getByRole('link',{name:'合成来源'}).scrollIntoViewIfNeeded()
    await expect(panel.getByRole('link',{name:'合成来源'})).toBeVisible()
    await page.screenshot({path:'test-results/hybrid-rag-panel-mobile.png',fullPage:true})
    await page.getByLabel('想说的话').fill('换个角度')
    await page.getByRole('button',{name:'发送消息'}).click()
    await page.locator('.rag-trace').last().getByText('查看知识依据 (RAG)').click()
    await expect(page.locator('.rag-trace').last()).toContainText('暂无已审核内容，启用本地兜底回复')
    await expect(page.locator('.rag-trace').last()).not.toContainText('0.421')
  } finally { expect((await page.request.delete(base,{headers})).ok()).toBeTruthy() }
})
