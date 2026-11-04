const api = require('../../utils/api');
const privacy = require('../../utils/privacy');
const session = require('../../utils/session');
const { definePage, confirm, toast } = require('../../utils/page');
const { time } = require('../../utils/format');

definePage({
  data: { students: [], studentNames: [], studentId: '', studentIndex: 0, policy: null, consent: null, parent: false, manager: false, requests: [], requestTypes: ['资料副本', '资料脱敏'], requestType: 0, reason: '', reviewId: 0, reviewName: '', reviewDecisions: ['同意申请', '不予通过'], reviewDecision: 0, reviewNote: '', erasureId: 0, erasureName: '', erasureBlocks: [], eraseConfirmation: '', exportText: '', historyOpen: false },
  onLoad(query) { this.setData({ studentId: query.studentId || '' }); },
  async loadData() {
    const parent = session.current().user.role === 'PARENT', manager = session.isAdmin();
    const [students, policy, requests] = await Promise.all([api.students({}), privacy.policy(), parent || manager ? privacy.requests() : Promise.resolve([])]);
    const studentIndex = Math.max(0, students.findIndex(row => Number(row.id) === Number(this.data.studentId)));
    const studentId = students[studentIndex] ? students[studentIndex].id : '';
    let consent = null;
    if (studentId && policy.consentRequired !== false) {
      try { consent = await privacy.consents(studentId); }
      catch (error) { if (error.statusCode !== 409) throw error; }
    }
    if (consent) {
      consent.serviceText = privacy.caption(consent.service.status);
      consent.mediaText = privacy.caption(consent.media.status);
      consent.history = consent.history.map(row => Object.assign({}, row, { scopeText: row.scope === 'MEDIA' ? '媒体记录' : '服务信息', actionText: row.granted ? '同意' : '撤回', timeText: time(row.createdAt) }));
    }
    return { students, studentIndex, studentId, studentNames: students.map(row => row.name + ' · ' + row.className), policy, consent, parent, manager,
      requests: requests.map(row => Object.assign({}, row, { typeText: row.type === 'EXPORT' ? '资料副本' : '资料脱敏', statusText: privacy.caption(row.status), timeText: time(row.createdAt), canExport: row.canExport !== false && row.type === 'EXPORT' && ['APPROVED', 'COMPLETED'].includes(row.status) })) };
  },
  child(event) {
    const index = Number(event.detail.value);
    if (this.data.busy || !this.data.students[index]) return;
    this.setData({ studentIndex: index, studentId: this.data.students[index].id, reason: '', historyOpen: false, exportText: '', reviewId: 0, erasureId: 0 }); this.refresh();
  },
  async consent(event) {
    if (!this.data.parent || !this.data.studentId || !this.data.policy || this.data.policy.consentRequired === false || this.data.busy) return;
    const scope = event.currentTarget.dataset.scope, granted = event.currentTarget.dataset.grant === '1';
    const context = session.context(), studentId = this.data.studentId, policyVersion = this.data.policy.version;
    const title = granted ? '阅读后同意授权' : '撤回授权';
    if (!await confirm(title, this.data.students[this.data.studentIndex].name + ' · ' + (scope === 'MEDIA' ? '图片、录音与视频' : '托管服务信息处理') + '\n' + (granted ? '我已阅读当前版本说明，并以该孩子绑定监护人的身份作出选择。' : '撤回后暂停该孩子的相关媒体记录与查看，必要账目和审计仍保留。'))) return;
    if (!session.isCurrentContext(context)) return;
    await this.act(() => privacy.consent({ studentId, scope, policyVersion, granted }), granted ? '授权已记录' : '授权已撤回');
  },
  requestType(event) { if (!this.data.busy) this.setData({ requestType: Number(event.detail.value) }); },
  async createRequest() {
    if (!this.data.parent || !this.data.studentId || this.data.busy) return;
    if (!this.data.reason.trim()) { toast('请填写申请说明'); return; }
    const context = session.context(), body = { studentId: this.data.studentId, type: this.data.requestType ? 'ERASURE' : 'EXPORT', reason: this.data.reason.trim() };
    if (!await confirm('提交' + (body.type === 'ERASURE' ? '资料脱敏' : '资料副本') + '申请', '提交申请不会立即处理资料。管理员核对后再审核；脱敏还须再次确认执行。')) return;
    if (!session.isCurrentContext(context)) return;
    if (await this.act(() => privacy.createRequest(body), '申请已提交', false)) { this.setData({ reason: '' }); await this.refresh(); }
  },
  history() { this.setData({ historyOpen: !this.data.historyOpen }); },
  openReview(event) {
    if (!this.data.manager || this.data.busy) return;
    const row = this.data.requests.find(row => Number(row.id) === Number(event.currentTarget.dataset.id));
    if (row && row.status === 'OPEN') this.setData({ reviewId: row.id, reviewName: row.studentName + ' · ' + row.typeText, reviewDecision: 0, reviewNote: '' });
  },
  reviewDecision(event) { if (!this.data.busy) this.setData({ reviewDecision: Number(event.detail.value) }); },
  cancelReview() { if (!this.data.busy) this.setData({ reviewId: 0 }); },
  async saveReview() {
    if (!this.data.manager || !this.data.reviewId || this.data.busy) return;
    if (!this.data.reviewNote.trim()) { toast('请填写核对说明'); return; }
    const id = this.data.reviewId, body = { decision: this.data.reviewDecision ? 'REJECT' : 'APPROVE', note: this.data.reviewNote.trim() };
    if (await this.act(() => privacy.review(id, body), '审核已保存', false)) { this.setData({ reviewId: 0 }); await this.refresh(); }
  },
  async export(event) {
    const row = this.data.requests.find(row => Number(row.id) === Number(event.currentTarget.dataset.id));
    if (!row || !row.canExport || this.data.busy) return;
    let result;
    if (await this.act(async () => { result = await privacy.export(row.id); }, '资料副本已生成', false)) { this.setData({ exportText: JSON.stringify(result, null, 2) }); await this.refresh(); }
  },
  copyExport() { if (this.data.exportText) wx.setClipboardData({ data: this.data.exportText }); },
  closeExport() { this.setData({ exportText: '' }); },
  async checkErasure(event) {
    if (!this.data.manager || this.data.busy) return;
    const row = this.data.requests.find(row => Number(row.id) === Number(event.currentTarget.dataset.id));
    if (!row || row.type !== 'ERASURE' || row.status !== 'APPROVED') return;
    let check;
    if (await this.act(async () => { check = await privacy.checkErasure(row.id); }, '', false)) this.setData({ erasureId: row.id, erasureName: row.studentName, erasureBlocks: check.blocks, eraseConfirmation: '' });
  },
  cancelErasure() { if (!this.data.busy) this.setData({ erasureId: 0 }); },
  async executeErasure() {
    if (!this.data.manager || !this.data.erasureId || this.data.erasureBlocks.length || this.data.busy) return;
    if (this.data.eraseConfirmation !== '确认脱敏') { toast('请输入“确认脱敏”'); return; }
    const context = session.context(), id = this.data.erasureId;
    if (!await confirm('最终确认资料脱敏', this.data.erasureName + '\n执行后移除孩子的姓名、联系方式和学习正文，关闭相关媒体访问。必要的脱敏账目与申请审计保留，共用附件不连带删除。')) return;
    if (!session.isCurrentContext(context)) return;
    if (await this.act(() => privacy.erase(id, { confirmed: true, note: '管理员已完成离校、费用与安全核对，并明确确认脱敏' }), '资料脱敏已完成', false)) { this.setData({ erasureId: 0, exportText: '' }); await this.refresh(); }
  }
});
