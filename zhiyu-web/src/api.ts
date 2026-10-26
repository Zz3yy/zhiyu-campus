const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080'

export type ApiEnvelope<T> = {
  code: number
  message: string
  data: T
  requestId: string
}

export type AnonymousUser = {
  userId: number
  nickname: string
  anonymous: boolean
  policyVersion: string
}

export type Checkin = {
  id: number
  userId: number
  recordDate: string
  moodScore: number
  stressScore: number
  sleepHours: number | null
  sleepQuality: number | null
  energyScore: number | null
  note: string | null
  createdAt: string
  updatedAt: string
}

export type Task = {
  id: number
  userId: number
  title: string
  plannedAt: string | null
  status: 'TODO' | 'DONE' | 'SKIPPED'
  source: string
  helpfulScore: number | null
  completedAt: string | null
}

export type TrendSummary = {
  from: string
  to: string
  checkinCount: number
  averageMood: number
  averageStress: number
  averageSleepHours: number
  streakDays: number
  completedTaskCount: number
  totalTaskCount: number
}

function storedUserId() {
  return window.localStorage.getItem('zhiyu.userId')
}

function localDate() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

async function request<T>(path: string, options: RequestInit = {}, requiresUser = true): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  const userId = storedUserId()
  if (requiresUser && userId) headers.set('X-User-Id', userId)
  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers })
  const payload = (await response.json()) as ApiEnvelope<T>
  if (!response.ok || payload.code !== 0) throw new Error(payload.message || '请求失败')
  return payload.data
}

export async function ensureAnonymousUser(): Promise<AnonymousUser> {
  const current = storedUserId()
  if (current) return { userId: Number(current), nickname: '同学', anonymous: true, policyVersion: '2026-01' }
  const user = await request<AnonymousUser>('/api/auth/anonymous', { method: 'POST' }, false)
  window.localStorage.setItem('zhiyu.userId', String(user.userId))
  return user
}

export function saveCheckin(input: Omit<Checkin, 'id' | 'userId' | 'createdAt' | 'updatedAt'>) {
  return request<Checkin>('/api/checkins', { method: 'POST', body: JSON.stringify(input) })
}

export function listCheckins() {
  return request<Checkin[]>('/api/checkins')
}

export function listTasks(date = localDate()) {
  return request<Task[]>(`/api/tasks?date=${date}`)
}

export function createTask(title: string) {
  return request<Task>('/api/tasks', { method: 'POST', body: JSON.stringify({ title }) })
}

export function completeTask(id: number) {
  return request<Task>(`/api/tasks/${id}/complete`, { method: 'PUT' })
}

export function skipTask(id: number) {
  return request<Task>(`/api/tasks/${id}/skip`, { method: 'PUT' })
}

export function getTrendSummary() {
  return request<TrendSummary>('/api/trends/summary')
}
