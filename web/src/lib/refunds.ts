import type { Account, Renewal, CampusScope } from './types'

export type RefundStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'CONFIRMED'
export interface RefundSource extends Renewal { refundableAmount: number; refundableLessons: number; refundableGiftLessons: number; availableBalance: number }
export type RefundAccount = Account & { refundReserved?: number; availableBalance?: number }
export interface Refund extends CampusScope {
  id: number; renewalId: number; accountId: number; studentId: number; studentName: string; courseName: string; unit: string
  amount: number; lessons: number; giftLessons: number; reason: string; status: RefundStatus; version: number
  applicantName: string; createdBy: number; createdAt: string; updatedAt: string; reviewNote: string
  confirmedAt: string | null; paymentMethod: string | null; paymentReference: string; confirmationNote: string; ledgerId: number | null
  canReview: boolean; canConfirm: boolean; canCancel: boolean
  logs?: { id: number; actorName: string; action: string; toStatus: RefundStatus; text: string; createdAt: string }[]
}
export const refundLabel: Record<RefundStatus, string> = { REQUESTED:'待审核', APPROVED:'待线下退款', REJECTED:'已驳回', CANCELLED:'已取消', CONFIRMED:'已退款' }
export const refundTone: Record<RefundStatus, string> = { REQUESTED:'amber', APPROVED:'amber', REJECTED:'red', CANCELLED:'gray', CONFIRMED:'green' }
export function refundCents(value: unknown): number {
  const text=String(value).trim()
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) throw new Error('退款金额须为非负金额，最多两位小数')
  const [whole, fraction='']=text.split('.')
  const cents=Number(whole)*100+Number(fraction.padEnd(2,'0'))
  if (!Number.isSafeInteger(cents)) throw new Error('退款金额超出允许范围')
  return cents
}
