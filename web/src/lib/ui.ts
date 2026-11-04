import { reactive, ref, watch, onBeforeUnmount, type WatchSource } from 'vue'
import { api, contextVersion, contextChanged, isCancelled } from './api'
export interface Option { label: string; value: string | number }
export interface Field { key: string; label: string; type?: 'text' | 'number' | 'textarea' | 'select' | 'multi' | 'date' | 'time' | 'file' | 'password'; required?: boolean; options?: Option[]; min?: number; max?: number; step?: number; hint?: string; placeholder?: string }
export type FormValues = Record<string, any>
export interface DialogConfig { title: string; description?: string; fields?: Field[]; initial?: FormValues; confirm?: string; danger?: boolean; submit?: (values: FormValues) => Promise<unknown>; success?: string }
export const dialog = ref<DialogConfig | null>(null)
let resolveDialog: ((value: boolean) => void) | undefined
export function openDialog(config: DialogConfig): Promise<boolean> {
  if (resolveDialog) resolveDialog(false)
  const version = contextVersion.value
  const submit = config.submit
  dialog.value = { ...config, submit: submit ? async values => {
    if (version !== contextVersion.value) throw contextChanged()
    const result = await submit(values)
    if (version !== contextVersion.value) throw contextChanged()
    return result
  } : undefined }
  return new Promise(resolve => { resolveDialog = resolve })
}
export function closeDialog(success = false) { dialog.value = null; resolveDialog?.(success); resolveDialog = undefined }
export const toasts = reactive<{ id: number; text: string; tone: string }[]>([])
export function notify(text: string, tone = 'success') {
  const id = Date.now() + Math.random(); toasts.push({ id, text, tone })
  setTimeout(() => { const i = toasts.findIndex(t => t.id === id); if (i >= 0) toasts.splice(i, 1) }, 6000)
}
export async function act(task: () => Promise<unknown>, message = '操作成功') {
  try { await task(); notify(message); return true } catch (error) { if (!isCancelled(error)) notify((error as Error).message, 'error'); return false }
}
export function useResource<T>(loader: () => Promise<T>, dependencies: WatchSource[] = []) {
  const data = ref<T | null>(null)
  const loading = ref(false); const error = ref(''); let generation = 0; let disposed = false
  async function refresh() {
    const current = ++generation; const version = contextVersion.value; loading.value = true; error.value = ''
    try { const result = await loader(); if (!disposed && current === generation && version === contextVersion.value) data.value = result as any }
    catch (err) { if (!disposed && current === generation && version === contextVersion.value) { data.value = null; error.value = (err as Error).message } }
    finally { if (!disposed && current === generation && version === contextVersion.value) loading.value = false }
  }
  watch([contextVersion, ...dependencies], () => { data.value = null; refresh() }, { immediate: true })
  onBeforeUnmount(() => { disposed = true; generation++ })
  return { data, loading, error, refresh }
}
export const studentOptions = (students: {id: number; name: string; className?: string; campusName?: string}[]) => students.map(s => ({ value: s.id, label: `${s.name}${s.className ? ` · ${s.className}` : ''}${s.campusName ? ` · ${s.campusName}` : ''}` }))
export const subjectOptions: Option[] = ['语文', '数学', '英语'].map(s => ({label:s,value:s}))
export const loadStudents = () => api<import('./types').Student[]>('/students')
export const labelMap: Record<string, string> = {
  SUMMARY_PUBLISHED:'日小结发布', PRESET_AWARD:'固定奖励', PRIZE_REDEMPTION:'奖品兑换', TEACHER_GRANT:'增加课时', PHOTO:'拍照登记', RECORDED:'已记录', OPEN:'待跟进', RESOLVED:'已解决',
  ON_LEAVE:"已请假", IN_REVIEW:"待审核", APPROVED:"已批准", REQUESTED:"待审核", REJECTED:"已驳回", CANCELLED:"已取消", REFUND:"退款扣回", ACTIVE:"授权有效", EXPIRED:"已过期", REVOKED:"已撤销", USED:"已接送", VERIFIED:"核验通过", ROTATED:"接送码已重置", SAFETY:"请假与接送", VERIFY_REJECTED:"核验被拦截",
  NOT_ARRIVED:'未到校', PRESENT:'已到校', LEFT:'已离校', NOT_STARTED:'未开始', IN_PROGRESS:'进行中', PENDING_REVIEW:'待批阅', CORRECTION:'需订正', PENDING_RECHECK:'待复核', COMPLETED:'已完成', UNCORRECTED:'待订正', MASTERED:'已掌握', PENDING:'待处理', DONE:'已完成', SETTLED:'已结算', CONFIRMED:'已确认', DRAFT:'草稿', PUBLISHED:'已发布', WRITTEN:'书面作业', ORAL:'口头作业', WECHAT_OFFLINE:'线下微信', ALIPAY:'支付宝', CASH:'现金', BANK:'银行转账', PURCHASE:'购买', GIFT:'赠送', CONSUME:'消课', CONSUMPTION:'消课', EVENING:'晚辅课程', HOLIDAY:'假期课程', WEEKEND:'周末课程', REVERSE:'冲正', REVERSAL:'冲正', RENEWAL:'续费', RENEWAL_PURCHASE:'续费购课', RENEWAL_GIFT:'续费赠课', OPENING:'期初入账', DEMO:'演示签到', HOMEWORK_COMPLETED:'完成作业', MISTAKE_MASTERED:'错题掌握', FITNESS:'体能打卡', MANUAL:'人工调整', HOMEWORK:'作业奖励', MISTAKE:'错题奖励', FITNESS_RECORDED:'体能打卡', NOT_CONFIGURED:'未配置消息服务', SENT:'已发送', FAILED:'发送失败', SUPER_ADMIN:'总管理员', ADMIN:'校区校长', TEACHER:'老师', PARENT:'家长', AFTER_SCHOOL:'晚辅', LESSON:'课时', DAY:'天', MALE:'男', FEMALE:'女'
}
export const label = (value?: string | null) => value ? labelMap[value] || value : '—'
