export type PlantKind = 'daisy' | 'sunflower' | 'mimosa' | 'moss' | 'cactus' | 'dandelion'
export type Thought = { situation: string; automaticThought: string; evidenceFor: string; evidenceAgainst: string; balancedThought: string }
export type Reflection = { intensity: number; change: string; note: string; date: string }
export type Entry = { id: string; requestId: string; plant: PlantKind; intensity: number; note: string; date: string; timezone: string; createdAt: string; thought?: Thought; reflection?: Reflection }
export type Task = { id: string; entryId?: string; activityId?: string; title: string; status: 'TODO' | 'DONE' | 'SKIPPED'; createdAt: string; completedAt?: string; helpfulScore?: number; feedback?: 'TOO_HARD' | 'NOT_HELPFUL' }
export type Activity = { id: string; title: string; minutes: number; method: string; instructions: string; source: string; plants: PlantKind[] }
export type Garden = { entries: Entry[]; tasks: Task[]; policyVersion: string; consentedAt: string }
export type Snapshot = { garden: Garden; activities: Activity[]; storageMode: 'memory' | 'postgres' }
const KEY = 'zhiyu.garden.token'
const BASE = (import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080') + '/api/garden'
export class ApiError extends Error { constructor(public status: number, message: string) { super(message) } }
export const hasSession = () => !!localStorage.getItem(KEY)
export const forgetSession = () => localStorage.removeItem(KEY)
export async function request<T>(path = '', method = 'GET', body?: unknown): Promise<T> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), path === '/chat' ? 105000 : 15000)
  try {
    const token = localStorage.getItem(KEY)
    const response = await fetch(BASE + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal })
    const payload = await response.json().catch(() => null)
    if (!response.ok) throw new ApiError(response.status, payload?.message ?? '暂时无法完成请求，请稍后再试。')
    return payload.data as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new Error('暂时无法连接花园。请确认服务已启动，再重试；你的输入仍会保留。')
  } finally { window.clearTimeout(timeout) }
}
export async function enterGarden() {
  const session = await request<{ token: string }>('/session', 'POST', { accepted: true })
  localStorage.setItem(KEY, session.token)
}
export const timezone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'
export function dateInZone(value = new Date(), zone = timezone()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(value)
  return ['year', 'month', 'day'].map(type => parts.find(p => p.type === type)?.value).join('-')
}
export function stage(entry: Entry, tasks: Task[]) { return Math.min(2, tasks.filter(t => t.entryId === entry.id && t.status === 'DONE').length + (entry.reflection ? 1 : 0)) }
export function weekStart(today: string) { const date = new Date(today + 'T12:00:00Z'); date.setUTCDate(date.getUTCDate() - 6); return date.toISOString().slice(0, 10) }
export function streak(entries: Entry[], today: string) {
  const dates = new Set(entries.map(e => e.date)); const cursor = new Date(today + 'T12:00:00Z'); let count = 0
  if (!dates.has(today)) cursor.setUTCDate(cursor.getUTCDate() - 1)
  while (dates.has(cursor.toISOString().slice(0, 10))) { count++; cursor.setUTCDate(cursor.getUTCDate() - 1) }
  return count
}
export function longestStreak(entries: Entry[]) {
  const dates = [...new Set(entries.map(e => e.date))].sort(); let max = 0; let run = 0; let previous = 0
  for (const date of dates) { const current = Date.parse(date + 'T00:00:00Z'); run = current - previous === 86400000 ? run + 1 : 1; max = Math.max(max, run); previous = current }
  return max
}
export const needsHelp = (text: string) => /自杀|自残|伤害自己|不想活|不想醒|活着没意义|撑不下去/.test(text)
