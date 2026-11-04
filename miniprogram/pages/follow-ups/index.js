const api = require('../../utils/api');
const ui = require('../../utils/growth');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const typeCodes = ['LEARNING', 'HEALTH', 'ATTENDANCE', 'COMMUNICATION', 'OTHER'];
const composeFields = ['title', 'reason', 'measures', 'needsParent', 'parentAction', 'typeIndex', 'nextFeedbackAt', 'attachmentIds', 'tomorrowReminder'];
definePage({
  data: { tomorrowReminder: false, isToday: false,  allChildren: false, id: '', studentId: '', students: [], childNames: [], childIndex: 0, kind: 'ISSUE', detail: null, rows: [], allRows: [], compose: false, stateIndex: 0, stateNames: ['待处理', '已结束', '全部'], types: ['学习', '身体情况', '到离校', '家校沟通', '其他'], typeIndex: 0, title: '', reason: '', measures: '', needsParent: false, parentAction: '', nextFeedbackAt: '', replyText: '', processText: '', attachmentIds: [], restoredIds: [], uploading: false, mediaReset: 0, owners: [], ownerNames: [], ownerIndex: 0, ownerId: '', read: false, readOnly: false, sourceType: '', sourceId: '', ownerReason: '' },
  onLoad(query) { this.setData({ id: query.id || '', studentId: query.studentId || '', kind: query.kind === 'MESSAGE' ? 'MESSAGE' : 'ISSUE', compose: query.compose === '1', sourceType: query.sourceType || '', sourceId: query.sourceId || '' }); },
  scope() { return this.data.id ? ['followReply', this.data.id] : ['followCompose', this.data.date, this.data.studentId, this.data.kind]; },
  saved(fields) { const value = {}; fields.forEach(k => { value[k] = this.data[k]; }); ui.draft(this.scope(), value); },
  afterLoad(patch) {
    if (!patch.detail || !patch.detail.lastLogId) return;
    const id = this.data.id, last = patch.detail.lastLogId;
    api.readFollowUp(id, last).then(() => {
      if (this._unloaded || this.data.id !== id || !this.data.detail || this.data.detail.lastLogId !== last) return;
      const logs = this.data.detail.logs.map(l => Object.assign({}, l, this.data.teacher ? { readByStaff: true } : { readByParent: true }));
      this.setData({ read: true, detail: Object.assign({}, this.data.detail, { logs }) });
    }).catch(() => { if (!this._unloaded && this.data.id === id) this.setData({ read: false }); });
  },
  async loadData() {
    if (this.data.id) {
      const detail = await api.followUp(this.data.id);
      const logs = (detail.logs || []).map(l => Object.assign(ui.decorate(l), { timeText: format.time(l.createdAt), isParent: l.actorRole === 'PARENT' }));
      let owners = [];
      if (this.data.admin) owners = await api.owners(detail.studentId);
      const draft = ui.draft(this.scope());
      return Object.assign({}, draft, { detail: Object.assign(ui.decorate(detail), { logs, nextFeedbackText: format.time(detail.nextFeedbackAt) }), owners, ownerNames: owners.map(o => o.name), date: detail.businessDate, studentId: detail.studentId, kind: detail.kind, restoredIds: draft.attachmentIds || [] });
    }
    const students = await api.students({});
    const allChildren = this.data.kind === 'MESSAGE' && !this.data.compose;
    const selected = students.find(s => String(s.id) === String(this.data.studentId));
    const studentId = selected ? selected.id : allChildren || !students.length ? '' : students[0].id;
    const childNames = students.map(s => s.name + ' · ' + s.campusName);
    if (allChildren) childNames.unshift(this.data.teacher ? '全部授权学生' : '全部孩子');
    const childIndex = students.findIndex(s => s.id === studentId) + Number(allChildren);
    const rows = (studentId || allChildren && students.length) ? (await api.followUps({ date: this.data.date, studentId, kind: this.data.kind })).map(ui.decorate) : [];
    const owners = studentId && this.data.teacher ? await api.owners(studentId) : [];
    const scope = ['followCompose', this.data.date, studentId, this.data.kind], saved = ui.draft(scope);
    return Object.assign({ title: '', reason: '', measures: '', needsParent: false, parentAction: '', typeIndex: 0, nextFeedbackAt: '', attachmentIds: [], tomorrowReminder: false }, saved, { students, studentId, allChildren, childNames, childIndex: Math.max(0, childIndex), owners, ownerNames: owners.map(o => o.name), ownerIndex: 0, ownerId: '', allRows: rows, rows: this.filterRows(rows), isToday: this.data.date === format.today(), readOnly: this.data.date > format.today(), restoredIds: saved.attachmentIds || [] });
  },
  filterRows(rows) { return rows.filter(r => this.data.stateIndex === 2 || (this.data.stateIndex === 0 ? ['OPEN', 'IN_PROGRESS'].includes(r.status) : ['RESOLVED', 'CANCELLED'].includes(r.status))); },
  filter(event) { this.setData({ stateIndex: Number(event.detail.value) }); this.setData({ rows: this.filterRows(this.data.allRows) }); },
  field(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }); this.saved(this.data.id ? ['replyText', 'processText', 'attachmentIds'] : composeFields); },
  needs(event) { this.setData({ needsParent: event.detail.value }); this.saved(composeFields); },
  setTomorrow(event) { this.setData({ tomorrowReminder: event.detail.value }); this.saved(composeFields); },
  type(event) { this.setData({ typeIndex: Number(event.detail.value) }); this.saved(composeFields); },
  owner(event) { const i = Number(event.detail.value); this.setData({ ownerIndex: i, ownerId: this.data.owners[i] ? this.data.owners[i].id : '' }); },
  async leaveForm(patch) { if (this.data.busy || this.data.uploading) { toast('请等待当前操作完成'); return; } this.setData(Object.assign({ detail: null, mediaReset: this.data.mediaReset + 1, restoredIds: [], attachmentIds: [], error: '', actionError: '' }, patch)); await this.refresh(); },
  changeDate(event) { return this.leaveForm({ date: event.detail.value }); },
  child(event) {
    const index = Number(event.detail.value) - Number(this.data.allChildren);
    const row = this.data.students[index];
    if (row || index === -1 && this.data.allChildren) this.leaveForm({ studentId: row ? row.id : '' });
  },
  kind(event) { this.leaveForm({ kind: event.currentTarget.dataset.kind, compose: false }); },
  toggleCompose() { return this.leaveForm({ compose: !this.data.compose }); },
  media(event) { this.setData({ attachmentIds: Array.from(new Set(this.data.restoredIds.concat(event.detail.ids))) }); this.saved(this.data.id ? ['replyText', 'processText', 'attachmentIds'] : composeFields); },
  uploadState(event) { this.setData({ uploading: event.detail.busy }); },
  clearFiles() { if (this.data.busy || this.data.uploading) return; this.setData({ restoredIds: [], attachmentIds: [], mediaReset: this.data.mediaReset + 1 }); this.saved(this.data.id ? ['replyText', 'processText', 'attachmentIds'] : composeFields); },
  open(event) { wx.navigateTo({ url: '/pages/follow-ups/index?id=' + event.currentTarget.dataset.id }); },
  async create() {
    if (this.data.uploading || this.data.readOnly) { toast('请等待上传完成，且只能登记今天或历史日期'); return; }
    const d = this.data;
    if (!d.studentId || !d.reason.trim() || (d.kind === 'ISSUE' && (!d.title.trim() || !d.measures.trim() || (d.needsParent && !d.parentAction.trim())))) { toast('请补全必填内容'); return; }
    const body = { studentId: d.studentId, date: d.date, kind: d.kind, type: typeCodes[d.typeIndex], title: d.title.trim(), reason: d.reason.trim(), measures: d.measures.trim(), needsParent: d.needsParent, parentAction: d.parentAction.trim(), nextFeedbackAt: d.nextFeedbackAt.trim(), attachmentIds: d.attachmentIds };
    if (d.teacher && d.kind === 'MESSAGE' && d.isToday && d.tomorrowReminder) body.tomorrowReminder = true;
    if (d.sourceType && d.sourceId) Object.assign(body, { sourceType: d.sourceType, sourceId: Number(d.sourceId) });
    if (d.admin && d.ownerId) body.ownerId = Number(d.ownerId);
    await this.act(async () => {
      const result = await api.createFollowUp(ui.request(this, 'create', body));
      ui.draft(this.scope(), null);
      this.setData({ compose: false, title: '', reason: '', measures: '', parentAction: '', nextFeedbackAt: '', tomorrowReminder: false, attachmentIds: [], restoredIds: [], mediaReset: d.mediaReset + 1 });
      wx.navigateTo({ url: '/pages/follow-ups/index?id=' + result.id });
    }, '已提交，可在小程序查看进展');
  },
  async reply(event) {
    if (this.data.uploading) { toast('请等待上传完成'); return; }
    const action = event.currentTarget.dataset.action, text = this.data.replyText.trim();
    if (action !== 'ACK' && !text) { toast('请填写具体反馈'); return; }
    await this.act(async () => {
      await api.replyFollowUp(this.data.id, ui.request(this, 'reply:' + action, { action, text, attachmentIds: action === 'ACK' ? [] : this.data.attachmentIds }));
      ui.draft(this.scope(), null); this.setData({ replyText: '', attachmentIds: [], restoredIds: [], mediaReset: this.data.mediaReset + 1 });
    }, action === 'PARENT_DONE' ? '已记录配合情况，等待老师核实' : '反馈已提交');
  },
  async update(event) {
    if (this.data.uploading) { toast('请等待上传完成'); return; }
    const target = event.currentTarget.dataset.status, text = this.data.processText.trim();
    if (!text) { toast('请填写处理结果或变更原因'); return; }
    if (!await confirm('确认' + ui.status(target), text)) return;
    await this.act(async () => { await api.updateFollowUp(this.data.id, ui.request(this, 'status', { status: target, text, expectedVersion: this.data.detail.version })); this.setData({ processText: '' }); ui.draft(this.scope(), null); }, '处理记录已保存');
  },
  async reassign() {
    if (this.data.uploading) { toast('请等待上传完成'); return; }
    if (!this.data.ownerId || !this.data.ownerReason.trim()) { toast('请选择老师并填写调整原因'); return; }
    this.act(() => api.reassignFollowUp(this.data.id, ui.request(this, 'owner', { ownerId: Number(this.data.ownerId), text: this.data.ownerReason.trim(), expectedVersion: this.data.detail.version })), '负责人已调整');
  },
  async markRead() { await this.act(async () => { await api.readFollowUp(this.data.id, this.data.detail.lastLogId); this.setData({ read: true }); }, '已标记查看'); }
});
