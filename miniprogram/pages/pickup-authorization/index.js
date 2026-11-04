const api = require('../../utils/api');
const pickup = require('../../utils/pickup');
const safety = require('../../utils/safety');
const flow = require('../../utils/growth');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const times = Array.from({ length: 48 }, (_, i) => String(Math.floor(i / 2)).padStart(2, '0') + ':' + (i % 2 ? '30' : '00')).concat('23:59');
definePage({
  data: { step: 1, students: [], childNames: [], childIndex: 0, studentId: '', contacts: [], contactNames: ['手动填写接送人'], contactIndex: 0, contactId: '', contactVersion: 0, pickupName: '', relationship: '', phone: '', relationshipNames: ['请选择关系', '妈妈', '爸爸', '爷爷', '奶奶', '外公', '外婆', '亲属', '其他'], relationshipIndex: 0, mode: 'SINGLE', startDate: '', endDate: '', timePreset: 'AFTER_SCHOOL', startTime: '18:30', endTime: '20:00', startIndex: 37, endIndex: 40, times, note: '', authorizationChecked: false, issuedCode: '', issuedAuthorization: null },
  onLoad(query) { this.setData({ studentId: query.studentId || '', contactId: query.contactId || '', startDate: this.data.date, endDate: this.data.date }); },
  onHide() { this._codeGeneration = (this._codeGeneration || 0) + 1; this.setData({ issuedCode: '' }); },
  async loadData() {
    const students = await api.students({});
    const studentId = students.some(row => String(row.id) === String(this.data.studentId)) ? Number(this.data.studentId) : students.length ? students[0].id : '';
    const contacts = studentId ? await pickup.contacts(studentId) : [];
    const patch = { students, studentId, childNames: students.map(row => row.name + ' · ' + row.campusName), childIndex: Math.max(0, students.findIndex(row => row.id === studentId)), contacts: contacts.map(pickup.contact), contactNames: ['手动填写接送人'].concat(contacts.map(row => row.name + ' · ' + row.relationship)) };
    if (this.data.contactId && this.data.step !== 4) {
      const index = contacts.findIndex(row => String(row.id) === String(this.data.contactId)), row = index >= 0 ? contacts[index] : null;
      Object.assign(patch, this.contactPatch(row, index + 1));
      if (!row || this.data.contactIndex > 0 && row.version !== this.data.contactVersion) Object.assign(patch, { step: 1, authorizationChecked: false });
    }
    return patch;
  },
  contactPatch(row, index) {
    return { contactId: row ? row.id : '', contactVersion: row ? row.version : 0, contactIndex: index, pickupName: row ? row.name : '', relationship: row ? row.relationship : '', phone: row ? row.phone || '' : '', relationshipIndex: row ? Math.max(0, this.data.relationshipNames.indexOf(row.relationship)) : 0, authorizationChecked: false };
  },
  changeChild(event) { if (this.data.busy) return; const index = Number(event.detail.value); this.setData(Object.assign({ studentId: this.data.students[index].id, childIndex: index }, this.contactPatch(null, 0))); this.refresh(); },
  changeContact(event) { if (this.data.busy) return; const index = Number(event.detail.value); this.setData(this.contactPatch(index ? this.data.contacts[index - 1] : null, index)); },
  changeRelationship(event) { const index = Number(event.detail.value); this.setData({ relationshipIndex: index, relationship: index ? this.data.relationshipNames[index] : '' }); },
  changeMode(event) { if (this.data.busy) return; const mode = event.currentTarget.dataset.mode; this.setData({ mode, endDate: mode === 'SINGLE' ? this.data.startDate : this.data.endDate, authorizationChecked: false }); },
  changeStartDate(event) { this.setData({ startDate: event.detail.value, endDate: this.data.mode === 'SINGLE' || this.data.endDate < event.detail.value ? event.detail.value : this.data.endDate }); },
  changeEndDate(event) { this.setData({ endDate: event.detail.value }); },
  changePreset(event) { const preset = event.currentTarget.dataset.preset; this.setData({ timePreset: preset, ...(preset === 'AFTER_SCHOOL' ? { startTime: '18:30', endTime: '20:00', startIndex: 37, endIndex: 40 } : {}) }); },
  changeTime(event) { const field = event.currentTarget.dataset.field, index = Number(event.detail.value); this.setData({ [field]: this.data.times[index], [field === 'startTime' ? 'startIndex' : 'endIndex']: index }); },
  authorizationCheck() { if (!this.data.busy) this.setData({ authorizationChecked: !this.data.authorizationChecked }); },
  body() {
    return Object.assign({ studentId: this.data.studentId, mode: this.data.mode, startDate: this.data.startDate, endDate: this.data.mode === 'SINGLE' ? this.data.startDate : this.data.endDate, startTime: this.data.startTime, endTime: this.data.endTime, note: this.data.note.trim() }, this.data.contactId ? { contactId: this.data.contactId, expectedContactVersion: this.data.contactVersion } : { pickupName: this.data.pickupName.trim(), relationship: this.data.relationship.trim(), phone: this.data.phone.trim().replace(/[ +()-]/g, '') });
  },
  next() {
    const error = pickup.validate(this.body());
    if (error) { toast(error); return; }
    if (this.data.startDate < format.today()) { toast('授权不能从过去日期开始'); return; }
    this.setData({ step: 3, actionError: '' });
  },
  previous() { if (!this.data.busy) this.setData({ step: 1, actionError: '', authorizationChecked: false }); },
  async create() {
    if (this.data.busy) return;
    if (this.data.teacher) { toast('请由当前绑定的监护人登记授权'); return; }
    const body = this.body(), error = pickup.validate(body);
    if (error) { toast(error); return; }
    if (!this.data.authorizationChecked) { toast('请核对接送授权内容'); return; }
    if (!await confirm('确认临时接送授权', this.data.pickupName + ' · ' + this.data.relationship + '\n' + body.startDate + (body.endDate !== body.startDate ? ' 至 ' + body.endDate : '') + ' ' + body.startTime + '–' + body.endTime + '\n接送码仅本次展示，老师还须当面核验后确认离校。')) return;
    const generation = this._codeGeneration || 0;
    let result;
    const ok = await this.act(async () => { result = await safety.createPickup(flow.request(this, 'pickup-authorize', body)); }, '', false);
    if (ok && generation === (this._codeGeneration || 0)) this.setData({ step: 4, issuedCode: result.pickupCode || '', issuedAuthorization: pickup.decorate(result) });
  },
  shareCode() {
    if (!this.data.issuedCode || !this.data.issuedAuthorization) { toast('接送码已隐藏，请返回管理页重置'); return; }
    const row = this.data.issuedAuthorization;
    wx.setClipboardData({ data: '接送安排：' + (row.studentName || '孩子') + '\n接送人：' + row.pickupName + '\n时间：' + row.datesText + ' ' + row.windowText + '\n接送码：' + this.data.issuedCode + '\n请私下保管，并在现场配合老师核对姓名和电话后四位。', success: () => toast('已复制，请私下分享给接送人') });
  },
  backToManagement() { wx.redirectTo({ url: '/pages/safety/index?studentId=' + this.data.studentId + '&view=pickups' }); }
});
