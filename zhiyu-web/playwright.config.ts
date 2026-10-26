import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests', timeout: 45000, workers: 1,
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173', channel: 'msedge', headless: true, screenshot: 'only-on-failure', trace: 'retain-on-failure' },
})
