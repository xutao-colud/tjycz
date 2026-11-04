<script setup lang="ts">
import SoftSelect from '../components/SoftSelect.vue'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, post, query, requestId, money, datetime } from '../lib/api'
import { isAdmin, isParent } from '../lib/state'
import { useResource, loadStudents, openDialog, label, act, notify } from '../lib/ui'
import type { Account, Ledger, Renewal, Settlement } from '../lib/types'
import { refundCents, refundLabel, refundTone, type Refund, type RefundAccount, type RefundSource } from '../lib/refunds'
import PageHeader from '../components/PageHeader.vue'
import AppIcon from '../components/AppIcon.vue'
import LoadState from '../components/LoadState.vue'
import StatusBadge from '../components/StatusBadge.vue'
const props=defineProps<{mode:'ledger'|'renewals'}>();const route=useRoute();const studentId=ref<string | number>(String(route.query.studentId||''));const accountId=ref<string | number>('');const tab=ref(props.mode==='renewals'?'renewals':'accounts')
const refundDetail=ref<Refund|null>(null)
const canRefund=computed(()=>isAdmin.value||isParent.value)
const view=computed(()=>props.mode==='renewals'&&tab.value!=='refunds'?'renewals':tab.value)
watch(()=>props.mode,()=>{tab.value=props.mode==='renewals'?'renewals':'accounts';refundDetail.value=null})
watch(studentId,()=>{accountId.value='';refundDetail.value=null})
const students=useResource(loadStudents)
const {data,loading,error,refresh}=useResource(async()=>{
  const [accounts,ledger,renewals,settlements,refunds,refundSources]=await Promise.all([
    api<RefundAccount[]>(`/accounts${query({studentId:studentId.value})}`),api<Ledger[]>(`/lesson-ledger${query({studentId:studentId.value,accountId:accountId.value})}`),api<Renewal[]>(`/renewals${query({studentId:studentId.value})}`),
    isParent.value?Promise.resolve([]):api<Settlement[]>('/settlements'),
    canRefund.value?api<Refund[]>(`/refunds${query({studentId:studentId.value})}`):Promise.resolve([]),
    canRefund.value?api<RefundSource[]>(`/refunds/sources${query({studentId:studentId.value})}`):Promise.resolve([])
  ])
  return {accounts,ledger,renewals,settlements:settlements.filter(s=>!studentId.value||s.studentId===Number(studentId.value)),refunds,refundSources}
},[studentId,accountId,()=>props.mode])
const available=(a:RefundAccount)=>a.availableBalance??a.balance
const lowBalance=computed(()=>data.value?.accounts.filter(a=>available(a)<=a.warningThreshold).length||0)
const pending=computed(()=>data.value?.renewals.filter(r=>r.status==='PENDING').length||0)
const rows=computed(()=>view.value==='renewals'?data.value?.renewals:view.value==='accounts'?data.value?.accounts:view.value==='ledger'?data.value?.ledger:view.value==='refunds'?data.value?.refunds:data.value?.settlements)
async function grant() {
  const courses=await api<{id:number;name:string;campusId:number}[]>('/courses')
  const options=courses.filter(c=>!studentId.value||students.data.value?.some(s=>s.id===Number(studentId.value)&&s.campusId===c.campusId)).map(c=>({value:c.id,label:c.name}))
  const key=requestId()
  if(await openDialog({title:'为孩子增加课程课时',description:'按所选课程独立记入增补课时，不代表已收款。收费续费仍由校区核对确认。',fields:[{key:'studentId',label:'学生',type:'select',required:true,options:(students.data.value||[]).map(s=>({value:s.id,label:s.name}))},{key:'courseId',label:'课程（晚辅、周末、假期等）',type:'select',required:true,options},{key:'units',label:'增加课时',type:'number',min:0.01,max:1000,step:0.01,required:true},{key:'reason',label:'增加原因',type:'textarea',required:true}],initial:{studentId:studentId.value?Number(studentId.value):'',units:1},submit:v=>post('/lesson-grants',{...v,requestId:key}),success:'课时已增加并保留流水'}))refresh()
}
async function renewal(account?:Account) {
  const id=requestId()
  if(await openDialog({title:'登记线下收费续费',description:'金额单位为人民币元。续费单保存后还需确认收款，确认后课时才会入账。此处不发起在线支付。',fields:[{key:'accountId',label:'学生 / 课时账户',type:'select',required:true,options:(data.value?.accounts||[]).map(a=>({value:a.id,label:`${a.studentName} · ${a.courseName}（余额 ${a.balance}${a.unit}）`}))},{key:'amountYuan',label:'实收金额（元）',type:'number',min:0.01,step:0.01,required:true},{key:'lessons',label:'购买课时',type:'number',min:0.01,step:0.01,required:true},{key:'giftLessons',label:'赠送课时',type:'number',min:0,step:0.01,required:true},{key:'paymentMethod',label:'收款方式',type:'select',required:true,options:['WECHAT_OFFLINE','ALIPAY','CASH','BANK'].map(v=>({value:v,label:label(v)}))},{key:'note',label:'收费备注',type:'textarea'}],initial:{accountId:account?.id||'',giftLessons:0,paymentMethod:'WECHAT_OFFLINE'},submit:({amountYuan,...v})=>post('/renewals',{...v,amount:Math.round(amountYuan*100),requestId:id}),success:'续费单已登记，请确认收款后入账'}))refresh()
}
async function confirm(item:Renewal) {
  if(await openDialog({title:'确认收款并入账',description:`请核实已收到 ${item.studentName} 的 ${money(item.amount)}。确认后，${item.courseName}账户将增加 ${item.lessons} 购买课时与 ${item.giftLessons} 赠送课时。重复确认不会重复入账。`,confirm:'已核实，确认入账',submit:()=>post(`/renewals/${item.id}/confirm`),success:'收款已确认，课时已入账'}))refresh()
}
async function reverse(item:Ledger) {
  if(await openDialog({title:'冲正课时流水',description:`将撤销 ${item.studentName}「${item.courseName}」此笔 ${item.amount} 课时变动。系统保留关联冲正流水；有待处理或已完成退款的原续费单不可冲正。`,fields:[{key:'reason',label:'冲正原因',type:'textarea',required:true,placeholder:'请说明冲正原因，便于后续审计'}],danger:true,confirm:'确认冲正',submit:v=>post(`/lesson-ledger/${item.id}/reverse`,v),success:'流水已冲正'}))refresh()
}
async function retry(item:Settlement) {if(await act(()=>post(`/settlements/${item.id}/retry`),'已重新尝试课时结算'))refresh()}
async function requestRefund(renewalId?:number) {
  const sources=data.value?.refundSources||[]
  if(!sources.length){notify('当前没有可申请退款的已确认续费单','error');return}
  const id=requestId()
  if(await openDialog({title:isAdmin.value?'登记退款申请':'申请退回未用课时',description:'选择原续费单并填写拟退金额、购买与赠送课时。审核通过后预留课时，机构实际完成线下退款并确认后才扣减账户；本页不会发起转账。',fields:[
    {key:'renewalId',label:'原续费单',type:'select',required:true,options:sources.map(s=>({value:s.id,label:`${s.studentName} · ${s.courseName} · #${s.id}（剩余 ${money(s.refundableAmount)} / 购买 ${s.refundableLessons} / 赠送 ${s.refundableGiftLessons}）`}))},
    {key:'amountYuan',label:'拟退金额（元）',type:'number',required:true,min:0,step:0.01},{key:'lessons',label:'退回购买课时',type:'number',required:true,min:0,step:0.01},{key:'giftLessons',label:'退回赠送课时',type:'number',required:true,min:0,step:0.01},{key:'reason',label:'申请原因',type:'textarea',required:true,hint:'最多 1000 字。购买与赠送课时合计须大于 0。'}
  ],initial:{renewalId:renewalId||'',amountYuan:'',lessons:0,giftLessons:0},confirm:'提交审核',submit:v=>{
    const source=sources.find(s=>s.id===Number(v.renewalId));const amount=refundCents(v.amountYuan)
    if(!source||amount>source.refundableAmount||Number(v.lessons)>source.refundableLessons||Number(v.giftLessons)>source.refundableGiftLessons)throw new Error('超过原续费单剩余可退额度，请刷新核对')
    if(Number(v.lessons)+Number(v.giftLessons)<=0)throw new Error('请填写退回的购买或赠送课时')
    return post('/refunds',{renewalId:Number(v.renewalId),amount,lessons:v.lessons,giftLessons:v.giftLessons,reason:v.reason,requestId:id})
  },success:'退款申请已登记，等待审核'})){tab.value='refunds';refresh()}
}
const refundSummary=(r:Refund)=>`${r.studentName} · ${r.courseName} · 原单 #${r.renewalId}\n拟退 ${money(r.amount)}，退回购买 ${r.lessons}、赠送 ${r.giftLessons}${r.unit}。`
async function reviewRefund(item:Refund,approved:boolean) {
  const id=requestId()
  if(await openDialog({title:approved?'审核通过并预留课时':'驳回退款申请',description:refundSummary(item)+(approved?'通过后预留上述课时，暂不扣减总余额。':''),fields:[{key:'note',label:'审核意见',type:'textarea',required:true,hint:'最多 1000 字'}],confirm:approved?'通过并预留':'确认驳回',submit:v=>post(`/refunds/${item.id}/review`,{...v,approved,expectedVersion:item.version,requestId:id}),success:approved?'已通过，等待线下退款':'已记录驳回意见'})){refundDetail.value=null;refresh()}
}
async function cancelRefund(item:Refund) {
  const id=requestId()
  if(await openDialog({title:'取消退款申请',description:refundSummary(item)+'取消后释放原单额度及已预留课时。',fields:[{key:'note',label:'取消原因',type:'textarea',required:true}],confirm:'确认取消申请',submit:v=>post(`/refunds/${item.id}/cancel`,{...v,expectedVersion:item.version,requestId:id}),success:'退款申请已取消'})){refundDetail.value=null;refresh()}
}
async function confirmRefund(item:Refund) {
  const id=requestId()
  if(await openDialog({title:'确认已完成线下退款',description:refundSummary(item)+'请先核实资金已在线下实际退还。保存后生成退款负向流水，记录不可修改；此操作不会自动转账。',fields:[
    {key:'paymentMethod',label:'实际退款方式',type:'select',required:true,options:['WECHAT_OFFLINE','ALIPAY','CASH','BANK'].map(v=>({value:v,label:label(v)}))},
    {key:'paymentReference',label:'退款流水号 / 签收凭证',required:true,hint:'现金退款填写签收记录编号；最多 200 字'},{key:'note',label:'退款确认说明',type:'textarea',required:true,hint:'最多 1000 字'}
  ],initial:{paymentMethod:'WECHAT_OFFLINE'},danger:true,confirm:'已实际退款，确认登记',submit:v=>post(`/refunds/${item.id}/confirm`,{...v,confirmed:true,expectedVersion:item.version,requestId:id}),success:'线下退款已确认，账本已更新'})){refundDetail.value=null;refresh()}
}
async function showRefund(item:Refund) {
  if(refundDetail.value?.id===item.id){refundDetail.value=null;return}
  const selected=studentId.value
  await act(async()=>{const result=await api<Refund>(`/refunds/${item.id}`);if(studentId.value===selected)refundDetail.value=result},'已加载退款记录')
}
</script>
<template>
  <PageHeader :title="mode==='renewals'?'收费续费':'课时账本'" :subtitle="mode==='renewals'?'收款与退款清晰留痕，让信任与陪伴长久延续。':'独立账户，真实流水，每一节课都有据可查。'">
    <button v-if="!isParent" class="btn secondary" @click="grant">增加课时</button><button v-if="canRefund" class="btn secondary" @click="requestRefund()"><AppIcon name="ReceiptText" :size="17" />{{ isAdmin?'登记退款申请':'申请退款' }}</button>
    <button v-if="isAdmin" class="btn primary" @click="renewal()"><AppIcon name="Plus" :size="17" />登记收费续费</button>
  </PageHeader>
  <div class="finance-stats">
    <article><span class="kpi-icon mint"><AppIcon name="WalletCards" :size="23" /></span><div><small>学生课时账户</small><strong>{{ data?.accounts.length||0 }} <span>个</span></strong></div></article>
    <article><span class="kpi-icon peach"><AppIcon name="TriangleAlert" :size="23" /></span><div><small>可用余额预警账户</small><strong>{{ lowBalance }} <span>个</span></strong></div></article>
    <article><span class="kpi-icon blue"><AppIcon name="ReceiptText" :size="23" /></span><div><small>待确认收款</small><strong>{{ pending }} <span>笔</span></strong></div></article>
  </div>
  <section class="panel">
    <div class="filter-bar">
      <div class="tabs">
        <template v-if="mode==='ledger'"><button :class="{active:tab==='accounts'}" @click="tab='accounts'">课时账户</button><button :class="{active:tab==='ledger'}" @click="tab='ledger'">课时流水</button><button v-if="!isParent" :class="{active:tab==='settlements'}" @click="tab='settlements'">消课待处理 <span v-if="data?.settlements.filter(s=>s.status==='PENDING').length" class="count-pill">{{ data?.settlements.filter(s=>s.status==='PENDING').length }}</span></button></template>
        <button v-else :class="{active:tab!=='refunds'}" @click="tab='renewals'">收费记录</button>
        <button v-if="canRefund" :class="{active:tab==='refunds'}" @click="tab='refunds'">退款记录</button>
      </div>
      <SoftSelect v-model="studentId" aria-label="财务学生" :options="[{value:'',label:'全部学生'}, ...(students.data.value || []).map(s => ({value:s.id,label:s.name}))]" />
      <SoftSelect v-if="view==='ledger'" v-model="accountId" aria-label="课时账户筛选" :options="[{value:'',label:'全部账户'}, ...(data?.accounts || []).map(a => ({value:a.id,label:`${a.studentName} · ${a.courseName}`}))]" />
      <span class="filter-count">{{ view==='refunds'?'申请 → 审核 → 线下退款确认':mode==='renewals'?'线下收费记录 · 确认后入账':'课时变动全程可追溯' }}</span><button class="icon-btn" aria-label="刷新账本" @click="refresh"><AppIcon name="RefreshCw" :size="17" /></button>
    </div>
    <LoadState :loading="loading" :error="error" :empty="!rows?.length" @retry="refresh">
      <div class="table-wrap">
        <table v-if="view==='renewals'"><thead><tr><th>学生 / 课程</th><th>实收金额</th><th>购买 / 赠送</th><th>收款方式</th><th>状态</th><th>登记时间 / 备注</th><th>操作</th></tr></thead><tbody><tr v-for="item in data?.renewals" :key="item.id"><td><strong>{{ item.studentName }}</strong><small v-if="item.campusName" class="block muted">{{ item.campusName }}</small><small class="block muted">{{ item.courseName }} · 原单 #{{ item.id }}</small></td><td class="numeric"><strong>{{ money(item.amount) }}</strong></td><td>{{ item.lessons }} / {{ item.giftLessons }} 课时</td><td>{{ label(item.paymentMethod) }}</td><td><StatusBadge :value="item.status" /></td><td>{{ datetime(item.createdAt) }}<small class="block muted">{{ item.note||'—' }}</small></td><td><div class="row-actions"><button v-if="isAdmin&&item.status==='PENDING'" class="text-btn" @click="confirm(item)">确认收款</button><span v-else class="muted">{{ item.status==='CONFIRMED'?'已入账':'待机构确认' }}</span><button v-if="canRefund&&data?.refundSources.some(s=>s.id===item.id)" class="text-btn" @click="requestRefund(item.id)">申请退款</button></div></td></tr></tbody></table>
        <table v-else-if="view==='accounts'"><thead><tr><th>学生 / 课程账户</th><th>账户总余额</th><th>可用课时</th><th>退款预留</th><th>净购买课时</th><th>净赠送课时</th><th>累计消耗</th><th>余额提示</th><th>操作</th></tr></thead><tbody><tr v-for="item in data?.accounts" :key="item.id"><td><strong>{{ item.studentName }}</strong><small v-if="item.campusName" class="block muted">{{ item.campusName }}</small><small class="block muted">{{ item.courseName }} · #{{ item.id }}</small></td><td class="numeric">{{ item.balance }} {{ item.unit }}</td><td><strong class="balance-number" :class="available(item)<=item.warningThreshold?'text-danger':'text-green'">{{ available(item) }}</strong> {{ item.unit }}</td><td>{{ item.refundReserved||0 }} {{ item.unit }}</td><td>{{ item.purchased }}</td><td>{{ item.gifted }}</td><td>{{ item.consumed }}</td><td><span class="badge" :class="available(item)<=item.warningThreshold?'amber':'green'"><i></i>{{ available(item)<=item.warningThreshold?'建议及时续费':'余额充足' }}</span></td><td><div class="row-actions"><button class="text-btn" @click="accountId=String(item.id);tab='ledger'">流水</button><button v-if="isAdmin" class="text-btn" @click="renewal(item)">续费</button></div></td></tr></tbody></table>
        <table v-else-if="view==='ledger'"><thead><tr><th>学生 / 课程</th><th>类型</th><th>课时变动</th><th>变动前 → 后</th><th>原因 / 发生时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in data?.ledger" :key="item.id"><td><strong>{{ item.studentName }}</strong><small v-if="item.campusName" class="block muted">{{ item.campusName }}</small><small class="block muted">{{ item.courseName }}</small></td><td>{{ label(item.type) }}<small v-if="item.originalId" class="block muted">关联 #{{ item.originalId }}</small></td><td class="numeric" :class="item.amount>0?'text-green':'text-danger'">{{ item.amount>0?'+':'' }}{{ item.amount }}</td><td class="numeric">{{ item.balanceBefore }} → {{ item.balanceAfter }}</td><td>{{ item.reason }}<small class="block muted">{{ datetime(item.createdAt) }}</small></td><td><button v-if="isAdmin&&!item.reversed&&!item.originalId&&!['REVERSE','REFUND'].includes(item.type)" class="text-btn text-danger" @click="reverse(item)">冲正</button><span v-else class="muted">{{ item.reversed?'已冲正':item.type==='REFUND'?'已退款，不可冲正':'—' }}</span></td></tr></tbody></table>
        <table v-else-if="view==='refunds'"><thead><tr><th>学生 / 原续费单</th><th>拟退金额 / 课时</th><th>状态</th><th>申请原因</th><th>登记时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in data?.refunds" :key="item.id"><td><strong>{{ item.studentName }}</strong><small class="block muted">{{ item.courseName }} · 原单 #{{ item.renewalId }}</small></td><td><strong class="numeric">{{ money(item.amount) }}</strong><small class="block muted">购买 {{ item.lessons }} / 赠送 {{ item.giftLessons }} {{ item.unit }}</small></td><td><span class="badge" :class="refundTone[item.status]">{{ refundLabel[item.status] }}</span></td><td class="refund-reason">{{ item.reason }}</td><td>{{ datetime(item.createdAt) }}<small class="block muted">{{ item.applicantName }}</small></td><td><div class="row-actions"><button class="text-btn" @click="showRefund(item)">记录</button><button v-if="item.canReview" class="text-btn" @click="reviewRefund(item,true)">通过</button><button v-if="item.canReview" class="text-btn text-danger" @click="reviewRefund(item,false)">驳回</button><button v-if="item.canConfirm" class="text-btn" @click="confirmRefund(item)">确认线下退款</button><button v-if="item.canCancel" class="text-btn" @click="cancelRefund(item)">取消申请</button></div></td></tr></tbody></table>
        <table v-else><thead><tr><th>学生</th><th>课程</th><th>消课日期</th><th>状态</th><th>原因</th><th>操作</th></tr></thead><tbody><tr v-for="item in data?.settlements" :key="item.id"><td><strong>{{ item.studentName }}</strong></td><td>{{ item.courseName }}</td><td>{{ item.date }}</td><td><StatusBadge :value="item.status" /></td><td>{{ item.reason }}</td><td><button v-if="item.status==='PENDING'" class="text-btn" @click="retry(item)">重试结算</button><span v-else class="muted">已结算</span></td></tr></tbody></table>
      </div>
    </LoadState>
    <section v-if="refundDetail&&view==='refunds'" class="detail-body refund-detail" aria-label="退款处理记录">
      <div class="panel-header"><h2>退款 #{{ refundDetail.id }} · {{ refundLabel[refundDetail.status] }}</h2><button class="icon-btn" aria-label="收起退款记录" @click="refundDetail=null"><AppIcon name="X" /></button></div>
      <div class="detail-grid"><div><span>原续费单</span><strong>#{{ refundDetail.renewalId }} · {{ refundDetail.courseName }}</strong></div><div><span>拟退金额与课时</span><strong>{{ money(refundDetail.amount) }} · 购买 {{ refundDetail.lessons }} / 赠送 {{ refundDetail.giftLessons }} {{ refundDetail.unit }}</strong></div><div><span>审核意见</span><strong>{{ refundDetail.reviewNote||'等待审核' }}</strong></div><div><span>线下退款凭证</span><strong>{{ refundDetail.paymentReference||'尚未登记' }}</strong></div></div>
      <div v-for="log in refundDetail.logs" :key="log.id" class="review-row"><div><strong>{{ log.actorName }} · {{ refundLabel[log.toStatus] }}</strong><small class="block muted">{{ datetime(log.createdAt) }}</small><p class="prose">{{ log.text }}</p></div></div>
    </section>
  </section>
  <div class="info-banner"><AppIcon name="ShieldCheck" :size="18" /><span>{{ view==='refunds'?'审核通过只预留课时；确认实际线下退款后扣减购买/赠送课时并生成不可编辑的退款流水。':mode==='renewals'?'此页用于登记线下收款与退款，不提供在线支付或自动转账。':'总余额包含退款预留。消课仅使用可用课时；余额不足可在充值或取消退款后重新结算。' }}</span></div>
</template>
<style scoped>
.refund-reason{white-space:normal;min-width:160px;max-width:300px;overflow-wrap:anywhere}
.refund-detail{border-top:1px solid var(--border)}
.refund-detail .panel-header{padding:0 0 20px}
.refund-detail .prose{margin:8px 0 0}
</style>
