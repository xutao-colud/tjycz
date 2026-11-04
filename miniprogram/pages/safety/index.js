const api = require('../../utils/api');
const safety = require('../../utils/safety');
const pickup = require('../../utils/pickup');
const format = require('../../utils/format');
const ui = require('../../utils/growth');
const { definePage, toast, confirm } = require('../../utils/page');
definePage({
  data: { legacyPickup: false, photoRecord: null, today: format.today(),  students: [], childNames: [], childIndex: 0, studentId: '', tab: 'pickups', viewTab: '', compose: '', leaves: [], pickups: [], contacts: [], pickupGroups: [], activeCount: 0, historyCount: 0, detailId: 0, contactDetailId: 0, contactForm: false, contactId: 0, contactVersion: 0, contactName: '', contactRelationship: '', contactPhone: '', contactDefault: false, revokeRow: null, revokeReason: '', revokeSuccess: null, alerts: [], alertOpenCount: 0, alertCompose: false, alertName: '', alertLast4: '', alertReason: '', alertNote: '', reason: '', note: '', pickupName: '', relationship: '', phone: '', endTime: '23:59', endIndex: 48, endNames: Array.from({ length: 48 }, (_, i) => String(Math.floor(i / 2)).padStart(2, '0') + ':' + (i % 2 ? '30' : '00')).concat('23:59'), issuedCode: '', verifyId: '', code: '', phoneLast4: '', identityChecked: false },
  onLoad(query) { this.setData({ studentId: query.studentId || '', viewTab: ['contacts', 'pickups'].includes(query.view) ? query.view : '', tab: query.tab === 'leaves' ? 'leaves' : 'pickups', detailId: Number(query.pickupId) || 0 }); },
  onHide() { this._codeGeneration = (this._codeGeneration || 0) + 1; this.setData({ issuedCode: '', code: '', identityChecked: false }); },
  async loadData() {
    const students = await api.students({ date: this.data.date });
    const studentId = students.some(s => String(s.id) === String(this.data.studentId)) ? Number(this.data.studentId) : students.length === 1 || !this.data.teacher && students.length ? students[0].id : '';
    const params = { date: this.data.date, studentId };
    const legacyPickup = this.query.legacy === '1';
    const [leaves, rows, contacts, alerts, records] = await Promise.all([
      safety.leaves(params), legacyPickup ? safety.pickups(this.data.teacher ? params : { studentId }) : [],
      legacyPickup && studentId ? pickup.contacts(studentId) : [], this.data.teacher ? safety.alerts(params) : [],
      studentId && !legacyPickup ? api.attendance(params) : []
    ]);
    const pickups = rows.map(pickup.decorate), active = pickups.filter(row => !row.isHistory), history = pickups.filter(row => row.isHistory);
    const row = records.find(item => Number(item.studentId) === Number(studentId));
    const photoRecord = row ? Object.assign(format.decorate(row), { arrival: ui.clock(row.checkInTime), departure: ui.clock(row.checkOutTime) }) : null;
    return { students, studentId, legacyPickup, photoRecord, today: format.today(), viewTab: this.data.viewTab || (this.data.teacher ? 'pickups' : 'contacts'), childNames: ['选择学生 / 孩子'].concat(students.map(s => s.name + ' · ' + s.campusName)), childIndex: studentId ? students.findIndex(s => s.id === studentId) + 1 : 0, leaves: leaves.map(safety.decorate), pickups, contacts: contacts.map(pickup.contact), activeCount: active.length, historyCount: history.length, pickupGroups: [{ key: 'active', title: '当前授权', items: active }, { key: 'history', title: '历史授权', items: history }], alerts: alerts.map(row => Object.assign({}, row, { statusText: ({ OPEN: '待核对', HOLD: '暂不离校', RESOLVED: '已核对' })[row.status] || row.status, createdText: format.time(row.createdAt), logs: (row.logs || []).map(log => Object.assign({}, log, { createdText: format.time(log.createdAt) })) })), alertOpenCount: alerts.filter(row => row.status !== 'RESOLVED').length };
  },
  async photoMark() {
    const row = this.data.photoRecord;
    if (!row || !this.data.teacher || this.data.date !== format.today() || !['NOT_ARRIVED', 'PRESENT'].includes(row.status)) return;
    const action = row.status === 'PRESENT' ? 'CHECK_OUT' : 'CHECK_IN';
    if (action === 'CHECK_OUT' && this.data.alertOpenCount) { toast('请先处理接送异常'); return; }
    await this.act(async () => {
      const result = await require('../../utils/teaching').recordPickup(this, row.studentId, row.campusId, action);
      if (!this._unloaded) toast(result.message);
    });
  },
  changeChild(event) { if (this.data.busy) return; const index = Number(event.detail.value); this.setData({ studentId: index ? this.data.students[index - 1].id : '', issuedCode: '', compose: '', verifyId: '', contactForm: false, revokeRow: null, revokeSuccess: null, detailId: 0 }); this.refresh(); },
  changeDate(event) { if (!this.data.busy) { this.setData({ date: event.detail.value, issuedCode: '', compose: '', verifyId: '' }); this.refresh(); } },
  tab(event) { if (!this.data.busy) this.setData({ tab: event.currentTarget.dataset.tab, compose: '', verifyId: '' }); },
  compose(event) { if (this.data.busy) return; if (event.currentTarget.dataset.kind === 'pickup') { this.authorize(); return; } this.setData({ compose: this.data.compose ? '' : event.currentTarget.dataset.kind, issuedCode: '', reason: '', pickupName: '', relationship: '', phone: '' }); },
  endTime(event) { const i = Number(event.detail.value); this.setData({ endIndex: i, endTime: this.data.endNames[i] }); },
  identity() { if (!this.data.busy) this.setData({ identityChecked: !this.data.identityChecked }); },
  clearCode() { this.setData({ issuedCode: '' }); },
  async create() {
    if (this.data.busy) return;
    if (!this.data.studentId) { toast('请先选择一位学生或孩子'); return; }
    const pickup = this.data.compose === 'pickup';
    const body = pickup ? { studentId: this.data.studentId, date: this.data.date, pickupName: this.data.pickupName.trim(), relationship: this.data.relationship.trim(), phone: this.data.phone.trim(), expiresAt: this.data.date + 'T' + this.data.endTime + ':00' } : { studentId: this.data.studentId, date: this.data.date, reason: this.data.reason.trim() };
    if (pickup ? !body.pickupName || !body.relationship || !body.phone : !body.reason) { toast('请完整填写必填信息'); return; }
    if (!await confirm(pickup ? '确认授权接送人' : '提交整日请假', pickup ? '接送码只显示一次，请自行交给接送人。老师须当面核验后交接。' : '经另一位校长或老板批准后，当天不签到、不消课；返校前请撤销请假。')) return;
    const generation = this._codeGeneration || 0;
    let result;
    const ok = await this.act(async () => { result = await (pickup ? safety.createPickup : safety.createLeave)(ui.request(this, pickup ? 'pickup' : 'leave', body)); }, pickup ? '接送授权已登记' : '请假已提交');
    if (ok && generation === (this._codeGeneration || 0)) this.setData({ compose: '', reason: '', pickupName: '', phone: '', issuedCode: result.pickupCode || '' });
  },
  async processLeave(event) {
    if (this.data.busy) return;
    const row = this.data.leaves.find(r => r.id === Number(event.currentTarget.dataset.id));
    if (!row) return;
    const action = event.currentTarget.dataset.action, note = this.data.note.trim();
    if (!note) { toast('请在处理说明中填写意见或原因'); return; }
    if (!await confirm(action === 'cancel' ? '确认撤销请假' : '确认审核请假', row.studentName + ' · ' + row.leaveDate + '。' + note)) return;
    const body = { note, expectedVersion: row.version };
    if (action !== 'cancel') body.approved = action === 'approve';
    await this.act(() => safety.action('leaves', row.id, action === 'cancel' ? 'cancel' : 'review', body), '处理结果已记录');
  },
  async processPickup(event) {
    if (this.data.busy) return;
    const row = this.data.pickups.find(r => r.id === Number(event.currentTarget.dataset.id));
    if (!row) return;
    const rotate = event.currentTarget.dataset.action === 'rotate-code';
    if (!rotate && !this.data.note.trim()) { toast('请填写撤销原因'); return; }
    if (!await confirm(rotate ? '重置接送码' : '撤销接送授权', '旧接送码与旧核验会立即失效。' + (rotate ? '新码仅本次显示。' : this.data.note.trim()))) return;
    const generation = this._codeGeneration || 0;
    let result;
    const ok = await this.act(async () => { result = await safety.action('pickups', row.id, rotate ? 'rotate-code' : 'revoke', { note: this.data.note.trim(), expectedVersion: row.version }); }, rotate ? '接送码已重置' : '授权已撤销');
    if (ok && generation === (this._codeGeneration || 0)) this.setData({ issuedCode: result.pickupCode || '' });
  },
  openVerify(event) { if (!this.data.busy) this.setData({ verifyId: Number(event.currentTarget.dataset.id), pickupName: '', phoneLast4: '', code: '', identityChecked: false }); },
  async verify() {
    if (this.data.busy) return;
    const row = this.data.pickups.find(r => r.id === this.data.verifyId);
    if (!row) return;
    if (!this.data.identityChecked || !/^\d{6}$/.test(this.data.code) || !/^\d{4}$/.test(this.data.phoneLast4) || !this.data.pickupName.trim()) { toast('请核对姓名、电话后四位、六位码并确认现场核验'); return; }
    const body = { pickupName: this.data.pickupName.trim(), phoneLast4: this.data.phoneLast4, code: this.data.code, confirmedIdentity: true, expectedVersion: row.version };
    const ok = await this.act(() => safety.action('pickups', row.id, 'verify', ui.request(this, 'verify:' + row.id, body)), '核验成功，请确认孩子实际离校');
    if (ok) this.setData({ verifyId: '', code: '', pickupName: '', phoneLast4: '', identityChecked: false });
  },
  async checkout(event) {
    if (this.data.busy) return;
    const row = this.data.pickups.find(r => r.id === Number(event.currentTarget.dataset.id));
    if (!row || !row.canCheckout) { toast('请先完成现场核验并核对异常提醒'); return; }
    if (!await confirm('确认孩子实际离校', row.studentName + ' 交接给 ' + row.pickupName + '（' + row.relationship + '）。确认后按当天排课结算；多次授权每天也仅能接送一次。')) return;
    await this.act(async () => { const result = await api.checkOut({ studentIds: [row.studentId], date: this.data.date }); if (!result.results[0] || !result.results[0].success) throw new Error(result.results[0] ? result.results[0].message : '离校未成功'); }, '安全交接已记录');
  },
  view(event) { if (!this.data.busy) this.setData({ viewTab: event.currentTarget.dataset.view, contactForm: false, compose: '', actionError: '' }); },
  contactDetails(event) { const id = Number(event.currentTarget.dataset.id); this.setData({ contactDetailId: this.data.contactDetailId === id ? 0 : id }); },
  authorize(event) {
    if (this.data.busy || this.data.teacher) return;
    if (!this.data.studentId) { toast('请先选择一位孩子'); return; }
    const id = event && event.currentTarget ? event.currentTarget.dataset.id : '';
    wx.navigateTo({ url: '/pages/pickup-authorization/index?studentId=' + this.data.studentId + '&date=' + this.data.date + (id ? '&contactId=' + id : '') });
  },
  editContact(event) {
    if (this.data.busy || this.data.teacher) return;
    if (!this.data.studentId) { toast('请先选择一位孩子'); return; }
    const id = Number(event.currentTarget.dataset.id), row = this.data.contacts.find(item => item.id === id);
    if (row && !row.canManage) return;
    this.setData({ contactForm: true, contactId: row ? row.id : 0, contactVersion: row ? row.version : 0, contactName: row ? row.name : '', contactRelationship: row ? row.relationship : '', contactPhone: row ? row.phone || '' : '', contactDefault: row ? row.isDefault : false });
  },
  contactDefault() { if (!this.data.busy) this.setData({ contactDefault: !this.data.contactDefault }); },
  cancelContact() { if (!this.data.busy) this.setData({ contactForm: false }); },
  async saveContact() {
    if (this.data.busy || this.data.teacher) return;
    const body = { name: this.data.contactName.trim(), relationship: this.data.contactRelationship.trim(), phone: this.data.contactPhone.trim().replace(/[ +()-]/g, ''), isDefault: this.data.contactDefault };
    if (!this.data.studentId || !body.name || !body.relationship || !/^[0-9]{6,20}$/.test(body.phone)) { toast('请完整填写姓名、关系和6至20位联系电话'); return; }
    const ok = await this.act(() => this.data.contactId ? pickup.editContact(this.data.contactId, Object.assign(body, { expectedVersion: this.data.contactVersion })) : pickup.createContact(ui.request(this, 'pickup-contact', Object.assign(body, { studentId: this.data.studentId }))), '常用接送人已保存');
    if (ok) this.setData({ contactForm: false });
  },
  async setDefault(event) {
    const row = this.data.contacts.find(item => item.id === Number(event.currentTarget.dataset.id));
    if (this.data.busy || !row || !row.canManage || row.isDefault) return;
    await this.act(() => pickup.setDefault(row.id, row.version), '已更新默认接送人');
  },
  async removeContact(event) {
    const row = this.data.contacts.find(item => item.id === Number(event.currentTarget.dataset.id));
    if (this.data.busy || !row || !row.canManage || !await confirm('删除常用接送人', row.name + ' 将从常用名单移除，与此人关联的有效接送授权也会撤销。')) return;
    await this.act(() => pickup.deleteContact(row.id, { expectedVersion: row.version }), '常用接送人已删除');
  },
  detail(event) { const id = Number(event.currentTarget.dataset.id); this.setData({ detailId: this.data.detailId === id ? 0 : id }); },
  openRevoke(event) {
    const row = this.data.pickups.find(item => item.id === Number(event.currentTarget.dataset.id));
    if (!this.data.busy && row && row.canRevoke) this.setData({ revokeRow: row, revokeReason: '', revokeSuccess: null });
  },
  cancelRevoke() { if (!this.data.busy) this.setData({ revokeRow: null }); },
  async confirmRevoke() {
    const row = this.data.revokeRow;
    if (this.data.busy || !row) return;
    const note = this.data.revokeReason.trim();
    if (!note) { toast('请填写撤销原因'); return; }
    const ok = await this.act(() => safety.action('pickups', row.id, 'revoke', { expectedVersion: row.version, note }), '接送授权已撤销');
    if (ok) this.setData({ revokeSuccess: row, revokeRow: null, issuedCode: '' });
  },
  closeRevokeSuccess() { this.setData({ revokeSuccess: null }); },
  toggleAlert() { if (!this.data.busy) this.setData({ alertCompose: !this.data.alertCompose }); },
  async reportAlert() {
    if (this.data.busy || !this.data.teacher) return;
    if (this.data.date !== format.today()) { toast('请切换到今天登记现场异常'); return; }
    const body = { studentId: this.data.studentId, pickupName: this.data.alertName.trim(), phoneLast4: this.data.alertLast4.trim(), reason: this.data.alertReason.trim() };
    if (!body.studentId || !body.pickupName || body.phoneLast4 && !/^\d{4}$/.test(body.phoneLast4) || !body.reason) { toast('请填写学生、来接人姓名和异常原因；电话后四位如填写须为4位数字'); return; }
    const ok = await this.act(() => safety.createAlert(ui.request(this, 'pickup-alert', body)), '异常已登记，保持暂不离校');
    if (ok) this.setData({ alertCompose: false, alertName: '', alertLast4: '', alertReason: '' });
  },
  contactParent(event) {
    const row = this.data.alerts.find(item => item.id === Number(event.currentTarget.dataset.id));
    if (!this.data.teacher || !row || !row.parentPhone) { toast('暂无可用监护人电话，请联系校长核对'); return; }
    wx.makePhoneCall({ phoneNumber: row.parentPhone });
  },
  async handleAlert(event) {
    const row = this.data.alerts.find(item => item.id === Number(event.currentTarget.dataset.id)), action = event.currentTarget.dataset.action;
    if (this.data.busy || !this.data.teacher || !row || !row.canHandle) return;
    const note = this.data.alertNote.trim() || (action === 'HOLD' ? '现场异常尚未核对，孩子继续留在校区等待监护人确认' : '');
    if (!note) { toast('请填写与监护人核对的情况后再解除异常'); return; }
    if (!await confirm(action === 'HOLD' ? '确认暂不离校' : '确认已完成异常核对', action === 'HOLD' ? '孩子继续留在校区，等待监护人确认。' : '异常记录将标为已核对，老师确认实际接送后再现场拍照签退。')) return;
    await this.act(() => safety.handleAlert(row.id, { action, note, expectedVersion: row.version }), action === 'HOLD' ? '已记录暂不离校' : '异常核对结果已记录');
  },
  swallow() {}
});
