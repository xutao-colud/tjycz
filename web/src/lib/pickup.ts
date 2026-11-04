import type { Attachment } from './types'

export interface SafetyLog { id:number; action:string; actorName:string; actorRole:string; detail:string; createdAt:string }
export interface Leave { id:number; studentId:number; studentName:string; campusName:string; leaveDate:string; reason:string; status:string; version:number; createdByName:string; reviewedByName?:string; reviewComment:string; canReview:boolean; canCancel:boolean; logs:SafetyLog[] }
export interface PickupContact { id:number; studentId:number; name:string; relationship:string; phone?:string; phoneMasked:string; phoneLast4:string; isDefault:boolean; version:number; canManage:boolean; faceStatus:string; avatar?:Attachment|null }
export interface Pickup { id:number; studentId:number; studentName:string; campusName:string; pickupName:string; relationship:string; phoneMasked:string; validFrom:string; expiresAt:string; mode:'SINGLE'|'MULTI'; startDate:string; endDate:string; startTime:string; endTime:string; note:string; contactId?:number; status:string; effectiveStatus:string; version:number; canVerify:boolean; canCheckout:boolean; canRevoke:boolean; guardianName:string; faceStatus:string; usedToday:boolean; usageDates?:string[]; logs:SafetyLog[]; pickupCode?:string }
export interface PickupAlert { id:number; studentId:number; studentName:string; className:string; campusName:string; businessDate:string; pickupName:string; phoneLast4:string; parentPhone?:string; reason:string; status:'OPEN'|'HOLD'|'RESOLVED'; source:string; version:number; canHandle:boolean; createdAt:string; updatedAt:string; logs:{id:number;action:string;actorName:string;note:string;createdAt:string}[] }
export interface FaceStatus { providerConfigured:boolean; status:string; message:string }
export interface AuthorizationDraft { studentId:string|number; contactId:string|number; expectedContactVersion?:number; pickupName:string; relationship:string; phone:string; mode:'SINGLE'|'MULTI'; startDate:string; endDate:string; startTime:string; endTime:string; note:string; requestId:string }
export function authorizationPayload(draft:AuthorizationDraft) {
  const studentId=Number(draft.studentId),contactId=Number(draft.contactId)
  if(!Number.isSafeInteger(studentId)||studentId<=0) throw new Error('请选择需要接送的孩子')
  if(!draft.startDate||!draft.endDate||draft.endDate<draft.startDate) throw new Error('请核对授权起止日期')
  if(draft.mode==='SINGLE'&&draft.startDate!==draft.endDate) throw new Error('单次接送的起止日期须为同一天')
  if(!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(draft.startTime)||!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(draft.endTime)||draft.startTime>=draft.endTime) throw new Error('接送时段须在同一天，结束时间晚于开始时间')
  const base={studentId,mode:draft.mode,startDate:draft.startDate,endDate:draft.endDate,startTime:draft.startTime,endTime:draft.endTime,note:draft.note.trim(),requestId:draft.requestId}
  if(contactId>0) {
    const version=draft.expectedContactVersion
    if(version===undefined||!Number.isSafeInteger(version)||version<0) throw new Error('联系人资料已变化，请重新选择并核对')
    return {...base,contactId,expectedContactVersion:version}
  }
  const pickupName=draft.pickupName.trim(),relationship=draft.relationship.trim(),phone=draft.phone.replace(/[ +()-]/g,'')
  if(!pickupName||!relationship) throw new Error('请填写接送人姓名与关系')
  if(!/^\d{6,20}$/.test(phone)) throw new Error('请填写有效的接送人手机号')
  return {...base,pickupName,relationship,phone}
}
export function pickupPeriod(row:Pick<Pickup,'mode'|'startDate'|'endDate'|'startTime'|'endTime'|'validFrom'|'expiresAt'>) {
  if(!row.startDate) return `${row.validFrom?.replace('T',' ').slice(0,16)} 至 ${row.expiresAt?.replace('T',' ').slice(0,16)}`
  const dates=row.startDate===row.endDate?row.startDate:`${row.startDate} 至 ${row.endDate}`
  return `${dates} · ${row.mode==='MULTI'?'每日 ':''}${row.startTime?.slice(0,5)}–${row.endTime?.slice(0,5)}`
}
export function pickupShareText(row:Pickup,code:string) {
  if(!/^\d{6}$/.test(code)) throw new Error('接送码未返回，请先重置后再分享')
  return `${row.studentName}的接送安排\n接送人：${row.pickupName}（${row.relationship}）\n接送时间：${pickupPeriod(row)}\n接送码：${code}\n请到校后主动向老师提供姓名、电话后四位与接送码，并配合现场核对。请勿公开转发。`
}
export async function sharePickupText(text:string,native:boolean,platform:{share?:(data:{title:string;text:string})=>Promise<void>;copy?:(text:string)=>Promise<void>}):Promise<'shared'|'copied'|'cancelled'> {
  if(native&&platform.share) {
    try {await platform.share({title:'孩子的接送安排',text});return 'shared'}
    catch(error) {if((error as {name?:string})?.name==='AbortError')return 'cancelled'}
  }
  if(!platform.copy)throw new Error('当前浏览器无法直接复制，请展开下方接送信息并手工复制。')
  try {await platform.copy(text);return 'copied'}
  catch {throw new Error('暂时无法复制，请展开下方接送信息并手工复制。')}
}
