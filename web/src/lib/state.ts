import { computed, reactive, ref } from 'vue'
import { ApiError, api, businessDate, connection, contextVersion, currentCampusId, post, setRequestContext, token } from './api'
import { closeDialog, notify } from './ui'
import type { Campus, DemoRole, Session, User, Role } from './types'
import { mobileApp } from '../mobile/platform'
export { currentCampusId, contextVersion }
export const user = ref<User | null>(null)
export const campuses = ref<Campus[]>([])
export const campusLoading = ref(false)
export const campusError = ref('')
export const selectedDate = ref(businessDate())
export const globalSearch = ref('')
export const health = reactive({ ready: false, demo: false, error: '' })
export const isSuperAdmin = computed(() => user.value?.role === 'SUPER_ADMIN')
export const isAdmin = computed(() => isSuperAdmin.value || user.value?.role === 'ADMIN')
export const isParent = computed(() => user.value?.role === 'PARENT')
export const roleName: Record<Role, string> = { SUPER_ADMIN: '总管理员', ADMIN: '校区校长', TEACHER: '授课老师', PARENT: '学生家长' }
export const demoRole = computed<DemoRole>(() => user.value?.role === 'SUPER_ADMIN' ? 'ADMIN' : user.value?.role === 'ADMIN' ? 'CAMPUS_ADMIN' : user.value?.role || 'ADMIN')
export const demoRoleName: Record<DemoRole, string> = { ADMIN: '总管理员', CAMPUS_ADMIN: '校区校长', TEACHER: '授课老师', PARENT: '学生家长' }
export const campusOptions = computed(() => campuses.value.filter(c => c.enabled !== false).map(c => ({ value: c.id, label: c.name })))
export const defaultCampusId = computed(() => currentCampusId.value ?? (campusOptions.value.length === 1 ? campusOptions.value[0]!.value : ''))
export const currentCampusName = computed(() => campuses.value.find(c => c.id === currentCampusId.value)?.name || (isSuperAdmin.value ? '全部校区' : '全部授权校区'))
// “全部”选项的文案与显示：总管理员=全部校区，其他角色=全部授权校区；仅一个可用校区时无需选择。
export const allCampusLabel = computed(() => isSuperAdmin.value ? '全部校区' : '全部授权校区')
export const showAllCampusOption = computed(() => campusOptions.value.length > 1)
function campusStorageKey() { return `wanfu.campus.${user.value?.id}` }
export function selectCampus(id: number | null) {
  if (id !== null && !campuses.value.some(c => c.id === id)) throw new Error('当前账号无权访问该校区。')
  if (id === currentCampusId.value) return
  closeDialog()
  globalSearch.value = ''
  setRequestContext(id)
  localStorage.setItem(campusStorageKey(), id === null ? 'all' : String(id))
}
export async function refreshCampuses() {
  const version = contextVersion.value
  campusLoading.value = true; campusError.value = ''
  try {
    // 此接口始终返回完整授权校区，不能随 X-Campus-Id 缩窄。
    const list = await api<Campus[]>('/campuses')
    if (version !== contextVersion.value) return
    campuses.value = list
    if (user.value) user.value = { ...user.value, campuses: list.map(({id, name}) => ({id, name})), campusIds: list.map(c => c.id) }
    if (currentCampusId.value !== null && !list.some(c => c.id === currentCampusId.value)) selectCampus(null)
  } catch (error) { if (version === contextVersion.value) campusError.value = (error as Error).message }
  finally { if (version === contextVersion.value || !campusError.value) campusLoading.value = false }
}
export async function saveSession(session: Session) {
  closeDialog()
  setRequestContext(null)
  localStorage.setItem('wanfu.token', session.token)
  user.value = { ...session.user, campusIds: session.user.campusIds || [], campuses: session.user.campuses || [], demo: session.demo }
  campuses.value = user.value.campuses
  connection.unauthorized = false
  globalSearch.value = ''
  await refreshCampuses()
  const saved = localStorage.getItem(campusStorageKey())
  const id = saved && saved !== 'all' ? Number(saved) : null
  if (id !== null && campuses.value.some(c => c.id === id)) selectCampus(id)
}
export async function demoLogin(role: DemoRole = 'ADMIN') { await saveSession(await post<Session>('/auth/demo', { role })) }
function clearLocalSession() {
  closeDialog(); setRequestContext(null)
  localStorage.removeItem('wanfu.token'); user.value = null; campuses.value = []; campusError.value = ''; connection.unauthorized = false
}
export async function logout() {
  const previousToken=token()
  if(previousToken&&!connection.unauthorized) {
    try { await post('/auth/logout') }
    catch(error) {
      if(error instanceof ApiError&&error.status===401) { if(token()===previousToken) clearLocalSession(); return true }
      if(token()===previousToken) notify('退出失败，服务器会话尚未撤销，请联网重试。','error')
      return false
    }
  }
  if(token()===previousToken) clearLocalSession()
  return true
}
export async function initialize() {
  health.error = ''; health.ready = false
  try {
    const result = await api<{ status: string; demo: boolean }>('/health')
    health.demo = result.demo
    if (token()) {
      try { await saveSession({ token: token(), user: await api<User>('/me'), demo: result.demo }); connection.unauthorized = false } catch { clearLocalSession() }
    }
    if (!user.value && health.demo && !mobileApp) await demoLogin()
  } catch (error) { health.error = (error as Error).message }
  finally { health.ready = true }
}
