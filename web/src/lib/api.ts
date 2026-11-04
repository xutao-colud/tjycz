import { reactive, ref } from 'vue'
import { serviceBase } from '../mobile/platform'
import { serviceUrl } from '../mobile/endpoints'
export class ApiError extends Error {
  constructor(message: string, public status = 0, public code = '') { super(message) }
}
export const connection = reactive({ unauthorized: false })
export const currentCampusId = ref<number | null>(null)
export const contextVersion = ref(0)
const pending = new Set<AbortController>()
export const contextChanged = () => new DOMException('校区或账号已切换，本次请求已取消。', 'AbortError')
export const isCancelled = (error: unknown) => error instanceof Error && error.name === 'AbortError'
export function setRequestContext(campusId: number | null) {
  pending.forEach(controller => controller.abort())
  pending.clear()
  currentCampusId.value = campusId
  contextVersion.value++
}
export function token() { return localStorage.getItem('wanfu.token') || '' }
export async function api<T>(path: string, options: { method?: string; body?: unknown; signal?: AbortSignal; campusId?: number } = {}): Promise<T> {
  const version = contextVersion.value
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (options.signal?.aborted) controller.abort()
  else options.signal?.addEventListener('abort', abort, { once: true })
  pending.add(controller)
  const headers: Record<string, string> = {}
  if (token()) headers.Authorization = `Bearer ${token()}`
  if (options.campusId !== undefined || currentCampusId.value !== null) headers['X-Campus-Id'] = String(options.campusId ?? currentCampusId.value)
  const isFile = options.body instanceof FormData
  if (options.body !== undefined && !isFile) headers['Content-Type'] = 'application/json'
  try {
    let response: Response
    try {
      response = await fetch(serviceUrl(serviceBase, path), { method: options.method || 'GET', headers, signal: controller.signal, cache: path.startsWith('/auth/') ? 'no-store' : 'default', body: options.body === undefined ? undefined : isFile ? options.body as FormData : JSON.stringify(options.body) })
    } catch (error) {
      if (controller.signal.aborted || version !== contextVersion.value || isCancelled(error)) throw contextChanged()
      throw new ApiError('暂时无法连接服务，请确认后端已启动后重试。')
    }
    const data = await response.json().catch(() => null)
    // 即便服务端已完成或忽略取消，也不能把旧账号/校区的结果交给调用者。
    if (controller.signal.aborted || version !== contextVersion.value) throw contextChanged()
    if (!response.ok) {
      if (response.status === 401 && !path.startsWith('/auth')) connection.unauthorized = true
      throw new ApiError(data?.message || (response.status === 403 ? '当前账号没有此操作权限。' : `请求失败（${response.status}），请稍后重试。`), response.status, data?.code)
    }
    return data as T
  } finally {
    pending.delete(controller)
    options.signal?.removeEventListener('abort', abort)
  }
}
export function query(values: Record<string, unknown>) {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => { if (value !== '' && value !== null && value !== undefined) params.set(key, String(value)) })
  return params.size ? `?${params}` : ''
}
export const post = <T>(path: string, body?: unknown) => api<T>(path, { method: 'POST', body })
export const requestId = () => crypto.randomUUID()
export const money = (cents: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format((cents || 0) / 100)
export const time = (value?: string | null) => value ? value.includes('T') ? value.slice(11, 16) : value.slice(0, 5) : '—'
export const datetime = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 16) : '—'
export function businessDate() { return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()) }
