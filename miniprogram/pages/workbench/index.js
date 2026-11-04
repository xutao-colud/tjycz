const api = require('../../utils/api');
const ui = require('../../utils/growth');
const format = require('../../utils/format');
const session = require('../../utils/session');
const { definePage, toast, confirm } = require('../../utils/page');
const fields = ['studentIds', 'panel', 'noteType', 'text', 'reason', 'measures', 'project', 'count', 'duration', 'attachmentIds', 'accuracy', 'comment', 'reviewStatus'];
definePage({
  teacherOnly: true,
  data: { batchMode: false, expandedId: null, today: format.today(), classId: '', classes: [], classNames: [], classIndex: 0, students: [], roster: [], homework: [], tasks: [], taskNames: [], taskIndex: 0, taskId: '', concerns: [], messages: [], summaries: [], scheduled: false, readOnly: false, filterIndex: 0, filters: ['全部学生', '未签到', '待批改', '需订正', '待复核', '需关注', '作业全完成', '已请假', '待回复'], counts: {}, panel: '', noteType: 0, noteTypes: ['课前准备', '学习表现', '老师点评', '体能活动', '运动暂停'], text: '', reason: '', measures: '', project: '跳绳', count: '', duration: '', attachmentIds: [], restoredIds: [], uploading: false, mediaReset: 0, accuracy: '', comment: '', reviewStatus: 0, reviewStatuses: ['批改完成', '退回订正'], reviewEntries: [], overrides: {}, summaryPreviews: [], confirmedIds: [], results: [], selectedCount: 0, historicalReason: '', pickupAlerts: [], pickupAlertPreview: null },
  onLoad(query) { this.setData({ classId: query.classId || '', batchMode: false, today: format.today() }); },
  scope() { return ['workbench', this.data.date, this.data.classId]; },
  saveDraft() { const d = {}; fields.forEach(k => { d[k] = this.data[k]; }); ui.draft(this.scope(), d); },
  async loadData() {
    const g = await api.workbench({ date: this.data.date, classId: this.data.classId });
    const draft = ui.draft(['workbench', this.data.date, g.classId || '']);
    const allowed = new Set(g.students.map(s => s.id));
    const studentIds = (draft.studentIds || this.data.studentIds || []).filter(id => allowed.has(id));
    const rows = g.students.map(s => {
      const hs = g.homework.filter(h => h.studentId === s.id), open = g.concerns.filter(c => c.studentId === s.id && ['OPEN', 'IN_PROGRESS'].includes(c.status));
      const review = hs.find(h => h.status === 'PENDING_RECHECK') || hs.find(h => h.status === 'PENDING_REVIEW');
      const needsReply = g.messages.some(row => row.studentId === s.id && ['OPEN', 'IN_PROGRESS'].includes(row.status) && row.lastActorRole === 'PARENT');
      const attendance = !g.readOnly && this.data.date === format.today() && ['NOT_ARRIVED', 'PRESENT'].includes(s.attendanceStatus);
      const summary = g.summaries.find(row => row.studentId === s.id);
      const pendingSummary = !g.readOnly && ['PRESENT', 'LEFT'].includes(s.attendanceStatus) && (!summary || summary.canEdit || summary.canPublish);
      const action = review ? (review.status === 'PENDING_RECHECK' ? '复核' : '批改') : s.attendanceStatus === 'NOT_ARRIVED' && attendance ? '拍照签到' : hs.length ? '查看作业' : attendance ? '拍照签退' : '查看档案';
      return Object.assign(format.decorate(s), { action, needsReply, pendingSummary, avatarUrl: s.avatar ? api.fileUrl(s.avatar) : '', checkIn: s.checkInTime ? ui.clock(s.checkInTime) : '未签到', checkOut: s.checkOutTime ? ui.clock(s.checkOutTime) : s.attendanceStatus === 'LEFT' ? '时间未记录' : '未签退', taskSummary: hs.map(h => h.subject + ' ' + ui.status(h.status)).join(' · '), tasks: hs.length, done: hs.filter(h => h.status === 'COMPLETED').length, primary: ui.primary(hs), concernCount: open.length, selected: studentIds.includes(s.id), pendingReview: hs.some(h => h.status === 'PENDING_REVIEW'), correction: hs.some(h => h.status === 'CORRECTION'), recheck: hs.some(h => h.status === 'PENDING_RECHECK'), taskStates: hs.map(h => h.status), messageCount: g.messages.filter(m => m.studentId === s.id && ['OPEN', 'IN_PROGRESS'].includes(m.status)).length });
    });
    const tasks = Array.from(new Map(g.homework.map(h => [h.taskId, { id: h.taskId, title: h.subject + ' · ' + h.title }])).values());
    const taskIndex = Math.max(0, tasks.findIndex(t => String(t.id) === String(this.data.taskId))), taskId = tasks[taskIndex] ? tasks[taskIndex].id : '';
    const data = Object.assign({}, draft, g, { today: format.today(), filters: [ '全部学生', g.scheduled ? '未到校' : '未签到', '待批改', '需订正', '待复核', '需关注', '作业全完成', '已请假', '待回复', '待处理小结' ], classNames: g.classes.map(c => c.name + ' · ' + c.campusName), classIndex: Math.max(0, g.classes.findIndex(c => c.id === g.classId)), roster: rows, students: this.filterRows(rows), tasks, taskNames: tasks.map(t => t.title), taskId, taskIndex, studentIds, selectedCount: studentIds.length, restoredIds: draft.attachmentIds || [], counts: {
      total: rows.length, arrived: rows.filter(s => ['PRESENT', 'LEFT'].includes(s.attendanceStatus)).length, present: rows.filter(s => s.attendanceStatus === 'PRESENT').length,
      leave: rows.filter(s => s.attendanceStatus === 'ON_LEAVE').length, absent: rows.filter(s => s.attendanceStatus === 'NOT_ARRIVED').length, allDone: rows.filter(s => s.tasks > 0 && s.done === s.tasks).length,
      review: g.homework.filter(h => ['PENDING_REVIEW', 'PENDING_RECHECK'].includes(h.status)).length,
      concerns: g.concerns.filter(c => ['OPEN', 'IN_PROGRESS'].includes(c.status)).length,
      summaries: rows.filter(s => s.pendingSummary).length
    } });
    data.reviewEntries = this.entries(g.homework, studentIds, taskId); data.summaryPreviews = this.previews(g.summaries, studentIds, g.concerns, this.data.confirmedIds);
    return data;
  },
  afterLoad() {
    if (this._returnScroll && session.isCurrentContext(this._returnScroll.context) && wx.pageScrollTo) {
      wx.pageScrollTo({ scrollTop: this._returnScroll.top, duration: 0 });
    }
    this._returnScroll = null;
    this._seenPickupAlerts = this._seenPickupAlerts || new Set();
    const current = this.data.pickupAlertPreview;
    if (current && !(this.data.pickupAlerts || []).some(item => item.id === current.id)) this.setData({ pickupAlertPreview: null });
    const scope = JSON.stringify(session.context());
    const row = (this.data.pickupAlerts || []).find(item => !this._seenPickupAlerts.has(scope + ':' + item.id + ':' + item.version));
    if (row) { this._seenPickupAlerts.add(scope + ':' + row.id + ':' + row.version); this.setData({ pickupAlertPreview: row }); }
  },
  dismissPickupAlert() { this.setData({ pickupAlertPreview: null }); },
  toggleBatch() { if (!this.data.busy && !this.data.uploading) this.setData({ batchMode: !this.data.batchMode }); },
  async photoMark(event) {
    if (this.data.date !== format.today()) { toast('拍照考勤仅支持当天'); return; }
    const row = this.data.roster.find(s => s.id === Number(event.currentTarget.dataset.id));
    if (!row || !['NOT_ARRIVED', 'PRESENT'].includes(row.attendanceStatus)) return;
    const action = row.attendanceStatus === 'PRESENT' ? 'CHECK_OUT' : 'CHECK_IN';
    await this.act(async () => {
      const result = await require('../../utils/teaching').recordPickup(this, row.id, row.campusId, action);
      if (!this._unloaded) toast(result.message + '，已生成家长站内消息');
    });
  },
  openPickupAlert(event) { const id = event.currentTarget.dataset.id; wx.navigateTo({ url: '/pages/safety/index?date=' + this.data.date + (id ? '&studentId=' + id : '') }); },
  contactPickupGuardian() { const row = this.data.pickupAlertPreview; if (row && /^[+0-9 -]{6,30}$/.test(row.parentPhone || '')) wx.makePhoneCall({ phoneNumber: row.parentPhone }); else toast('暂无可用监护人电话，请联系校区负责人'); },
  async holdPickup() {
    const row = this.data.pickupAlertPreview;
    if (!row || this.data.busy || !await confirm('确认暂不离校', '保持拦截，待监护人和接送情况核对清楚后，再现场拍照签退。')) return;
    const ok = await this.act(() => api.request('POST', '/pickup-alerts/' + row.id + '/handle', { action: 'HOLD', expectedVersion: row.version, note: '老师确认暂不离校，等待监护人与接送安排核对' }), '已记录暂不离校');
    if (ok) this.setData({ pickupAlertPreview: null });
  },
  filterRows(rows) { const i = this.data.filterIndex; return rows.filter(s => !i || i === 1 && s.attendanceStatus === 'NOT_ARRIVED' || i === 2 && s.pendingReview || i === 3 && s.correction || i === 4 && s.recheck || i === 5 && s.concernCount || i === 6 && s.tasks > 0 && s.done === s.tasks || i === 7 && s.attendanceStatus === 'ON_LEAVE' || i === 8 && s.needsReply || i === 9 && s.pendingSummary); },
  entries(homework, ids, tid) { return homework.filter(h => ids.includes(h.studentId) && h.taskId === tid && h.status !== 'COMPLETED').map(h => Object.assign(ui.decorate(h), this.data.overrides[h.id] || {}, { inputAccuracy: (this.data.overrides[h.id] || {}).accuracy || '', inputComment: (this.data.overrides[h.id] || {}).comment || '' })); },
  previews(summaries, ids, concerns, confirmed) { return summaries.filter(s => ids.includes(s.studentId)).map(s => Object.assign({}, s, { statusText: format.label(s.status), contentText: format.businessText(s.content), openConcerns: concerns.filter(c => c.studentId === s.studentId && ['OPEN', 'IN_PROGRESS'].includes(c.status)), confirmed: (confirmed || []).includes(s.studentId) })); },
  derive() {
    const ids = this.data.studentIds, roster = this.data.roster.map(s => Object.assign({}, s, { selected: ids.includes(s.id) }));
    this.setData({ roster, students: this.filterRows(roster), selectedCount: ids.length, reviewEntries: this.entries(this.data.homework, ids, this.data.taskId), summaryPreviews: this.previews(this.data.summaries, ids, this.data.concerns, this.data.confirmedIds) });
  },
  filter(event) { this.setData({ filterIndex: Number(event.detail.value) }); this.derive(); },
  select(event) { if (this.data.busy || this.data.uploading) return; const id = Number(event.currentTarget.dataset.id), ids = this.data.studentIds; this.setData({ studentIds: ids.includes(id) ? ids.filter(s => s !== id) : ids.concat(id), confirmedIds: [] }); this.derive(); this.saveDraft(); },
  selectVisible() { if (this.data.busy || this.data.uploading) return; this.setData({ studentIds: this.data.students.map(s => s.id), confirmedIds: [] }); this.derive(); this.saveDraft(); },
  clearSelection() { if (this.data.busy || this.data.uploading) return; this.setData({ studentIds: [], confirmedIds: [] }); this.derive(); this.saveDraft(); },
  async switchScope(patch) { if (this.data.busy || this.data.uploading) { toast('请等待当前操作完成'); return; } this.setData(Object.assign({ studentIds: [], expandedId: null, panel: '', roster: [], students: [], pickupAlerts: [], pickupAlertPreview: null, accuracy: '', comment: '', text: '', reason: '', measures: '', attachmentIds: [], restoredIds: [], overrides: {}, confirmedIds: [], results: [], mediaReset: this.data.mediaReset + 1 }, patch)); await this.refresh(); },
  changeDate(event) { return this.switchScope({ date: event.detail.value }); },
  classroom(event) { const c = this.data.classes[Number(event.detail.value)]; if (c) this.switchScope({ classId: c.id }); },
  field(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }); this.saveDraft(); },
  pick(event) { this.setData({ [event.currentTarget.dataset.field]: Number(event.detail.value) }); this.saveDraft(); },
  panel(event) { if (this.data.busy || this.data.uploading) return; this.setData({ panel: this.data.panel === event.currentTarget.dataset.panel ? '' : event.currentTarget.dataset.panel, results: [] }); this.saveDraft(); },
  task(event) { const i = Number(event.detail.value); this.setData({ taskIndex: i, taskId: this.data.tasks[i] ? this.data.tasks[i].id : '', studentIds: [], overrides: {}, confirmedIds: [] }); this.derive(); this.saveDraft(); },
  override(event) { const id = event.currentTarget.dataset.id, field = event.currentTarget.dataset.field; const overrides = Object.assign({}, this.data.overrides); overrides[id] = Object.assign({}, overrides[id], { [field]: event.detail.value }); this.setData({ overrides }); },
  media(event) { this.setData({ attachmentIds: Array.from(new Set(this.data.restoredIds.concat(event.detail.ids))) }); this.saveDraft(); },
  uploadState(event) { this.setData({ uploading: event.detail.busy }); },
  clearFiles() { if (!this.data.busy && !this.data.uploading) { this.setData({ attachmentIds: [], restoredIds: [], mediaReset: this.data.mediaReset + 1 }); this.saveDraft(); } },
  ready() { if (this.data.readOnly || !this.data.studentIds.length || this.data.uploading) { toast(this.data.readOnly ? '未来日期不可登记事实' : this.data.uploading ? '请等待上传完成' : '请先选择学生'); return false; } return true; },
  results(result, idMap) {
    const results = (result.results || []).map(r => Object.assign({}, r, { studentId: r.studentId || idMap && idMap[r.id], name: (this.data.roster.find(s => s.id === (r.studentId || idMap && idMap[r.id])) || {}).name || '学生', message: r.message || (r.success ? '已完成' : '处理失败') }));
    this.setData({ results, studentIds: results.filter(r => !r.success).map(r => r.studentId), confirmedIds: [] }); this.derive(); this.saveDraft();
    if (results.some(r => !r.success)) toast('部分记录未完成，已保留选择，请核对后重试');
  },
  openClassReport() { if (this.data.classId && !this.data.busy && !this.data.uploading) wx.navigateTo({ url: '/pages/class-reports/index?classId=' + this.data.classId + '&date=' + this.data.date }); },
  async attendance(event) {
    if (!this.ready()) return;
    const out = event.currentTarget.dataset.out === '1';
    if (out && this.data.date < format.today() && (!this.data.admin || !this.data.historicalReason.trim())) { toast('历史离校须由校长或老板填写核对原因'); return; }
    if (!await confirm(out ? '批量离校' : '批量到校', '已选择 ' + this.data.studentIds.length + ' 名学生，请核对名单。')) return;
    const date = this.data.date, studentIds = [...this.data.studentIds], historicalReason = this.data.historicalReason.trim();
    await this.act(async () => {
      if (this._unloaded) return;
      const data = { date, studentIds, historicalReason };
      this.results(await (out ? api.checkOut(data) : api.checkIn(data)));
    }, '考勤处理结果已更新');
  },
  async record() {
    if (!this.ready()) return;
    const d = this.data, body = { date: d.date, studentIds: d.studentIds };
    if (d.noteType < 3) { if (!d.text.trim()) { toast('请填写记录内容'); return; } Object.assign(body, { type: ['PREPARATION', 'BEHAVIOR', 'COMMENT'][d.noteType], text: d.text.trim(), attachmentIds: d.attachmentIds }); }
    if (d.noteType === 3) {
      const count = d.count.trim() === '' ? NaN : Number(d.count), duration = d.duration.trim() === '' ? NaN : Number(d.duration);
      if (!d.project.trim() || !Number.isInteger(count) || !Number.isInteger(duration) || count < 0 || duration < 0 || !(count || duration)) { toast('请填写项目、非负整数次数与秒数，至少一项大于0'); return; }
      Object.assign(body, { project: d.project.trim(), count, duration });
    }
    if (d.noteType === 4) { if (!d.reason.trim() || !d.measures.trim()) { toast('请填写暂停原因和采取的措施'); return; } Object.assign(body, { reason: d.reason.trim(), measures: d.measures.trim() }); }
    await this.act(async () => {
      let r;
      if (d.noteType < 3) r = await api.growthNote(ui.request(this, 'note', body));
      else if (d.noteType === 4) r = await api.fitnessPause(ui.request(this, 'pause', body));
      else { const value = await api.fitnessBatch(body); r = { results: d.studentIds.map(studentId => ({ studentId, success: true, message: '已处理；同日相同项目不会重复登记' })) }; toast('新增 ' + value.recordedCount + ' 条运动记录'); }
      this.results(r); this.setData({ text: '', reason: '', measures: '', attachmentIds: [], restoredIds: [], mediaReset: d.mediaReset + 1 }); this.saveDraft();
    }, '记录已保存');
  },
  async review() {
    if (!this.ready()) return;
    if (!this.data.reviewEntries.length) { toast('所选学生在此任务下没有可批改作业'); return; }
    const accuracy = this.data.accuracy.trim() === '' ? NaN : Number(this.data.accuracy), status = this.data.reviewStatus ? 'CORRECTION' : 'COMPLETED';
    const entries = this.data.reviewEntries, items = entries.map(h => {
      const o = this.data.overrides[h.id] || {}, item = { id: h.id, expectedVersion: h.version };
      if (o.accuracy !== undefined && o.accuracy.trim() !== '') item.accuracy = Number(o.accuracy);
      if (o.comment !== undefined && o.comment.trim() !== '') item.comment = o.comment.trim();
      return item;
    });
    if (items.some(h => !Number.isInteger(h.accuracy === undefined ? accuracy : h.accuracy) || (h.accuracy === undefined ? accuracy : h.accuracy) < 0 || (h.accuracy === undefined ? accuracy : h.accuracy) > 100)) { toast('请填写0至100的整数正确率，可逐份覆盖'); return; }
    if (status === 'CORRECTION' && items.some(h => !(h.comment || this.data.comment.trim()))) { toast('每份退回作业须有订正说明'); return; }
    if (!await confirm('确认批改 ' + items.length + ' 份作业', '任务：' + this.data.taskNames[this.data.taskIndex] + '\n' + entries.map(h => h.studentName).join('、') + '\n结果：' + ui.status(status))) return;
    const body = { taskId: this.data.taskId, status, comment: this.data.comment.trim(), items }; if (Number.isFinite(accuracy)) body.accuracy = accuracy;
    await this.act(async () => { const map = {}; entries.forEach(h => { map[h.id] = h.studentId; }); this.results(await api.batchReview(ui.request(this, 'review', body)), map); this.setData({ overrides: {} }); }, '请查看逐份批改结果');
  },
  async generate() { if (!this.ready()) return; await this.act(() => api.generateSummaries({ date: this.data.date, studentIds: this.data.studentIds }), '草稿已生成，请逐份预览'); },
  confirmConcern(event) { const id = Number(event.currentTarget.dataset.id), ids = this.data.confirmedIds; this.setData({ confirmedIds: ids.includes(id) ? ids.filter(s => s !== id) : ids.concat(id) }); this.derive(); },
  summaryComment(event) { const id = Number(event.currentTarget.dataset.id); this.setData({ summaryPreviews: this.data.summaryPreviews.map(s => s.id === id ? Object.assign({}, s, { teacherComment: event.detail.value }) : s) }); },
  async publish() {
    if (!this.ready()) return;
    const rows = this.data.summaryPreviews.filter(s => s.canPublish);
    if (!rows.length) { wx.navigateTo({ url: '/pages/summaries/index?date=' + this.data.date }); return; }
    if (rows.some(s => s.openConcerns.length && !this.data.confirmedIds.includes(s.studentId))) { toast('请逐份核对关注事项的原因与措施并勾选确认'); return; }
    if (!await confirm('发布 ' + rows.length + ' 份已审核小结', '发布经过审核的固定快照，家长可在小程序内查看。')) return;
    await this.act(async () => {
      const map = {}; rows.forEach(s => { map[s.id] = s.studentId; });
      this.results(await api.batchPublish({ summaryIds: rows.map(s => s.id), confirmedConcernStudentIds: this.data.confirmedIds }), map);
    }, '请查看逐份发布结果');
  },

  growth(event) { wx.navigateTo({ url: '/pages/growth/index?date=' + this.data.date + '&studentId=' + event.currentTarget.dataset.id }); },
  follow(event) { wx.navigateTo({ url: '/pages/follow-ups/index?date=' + this.data.date + '&studentId=' + event.currentTarget.dataset.id + (event.currentTarget.dataset.kind === 'MESSAGE' ? '&kind=MESSAGE' : '') + (event.currentTarget.dataset.compose ? '&compose=1' : '') }); },
  onPageScroll(event) { this._scrollTop = event.scrollTop; },
  moreStudent(event) {
    const id = Number(event.currentTarget.dataset.id);
    this.setData({ expandedId: this.data.expandedId === id ? null : id });
  },
  primaryStudent(event) {
    const student = this.data.roster.find(row => row.id === Number(event.currentTarget.dataset.id));
    if (!student) return;
    if (this.data.filterIndex === 9) return this.quickSummary(event);
    if (this.data.filterIndex === 8) return this.follow({ currentTarget: { dataset: { id: student.id, kind: 'MESSAGE' } } });
    if (student.action === '拍照签到' || student.action === '拍照签退') return this.photoMark(event);
    if (student.action === '查看档案') return this.growth(event);
    this.homework(event);
  },
  quickSummary(event) {
    wx.navigateTo({ url: '/pages/summaries/index?quick=1&date=' + this.data.date + '&studentId=' + event.currentTarget.dataset.id });
  },
  homework(event) {
    const rows = this.data.homework.filter(h => h.studentId === Number(event.currentTarget.dataset.id));
    const reviewStatus = this.data.filterIndex === 2 ? 'PENDING_REVIEW' : this.data.filterIndex === 4 ? 'PENDING_RECHECK' : '';
    const h = rows.find(row => reviewStatus && row.status === reviewStatus) || rows.find(row => row.status === 'PENDING_RECHECK')
      || rows.find(row => row.status === 'PENDING_REVIEW') || rows.find(row => row.status !== 'COMPLETED') || rows[0];
    if (!h) { toast('当日未布置作业'); return; }
    this._returnScroll = { top: this._scrollTop || 0, context: session.context() };
    wx.navigateTo({ url: '/pages/homework-detail/index?id=' + h.id + '&classId=' + this.data.classId + '&reviewStatus=' + reviewStatus });
  }
});
