const api = require('../../utils/api');
const format = require('../../utils/format');
const ui = require('../../utils/growth');
const session = require('../../utils/session');
const { definePage, toast, confirm } = require('../../utils/page');
definePage({
  data: { nextReady: false, item: null, queryId: '', siblings: [], subjectTasks: [], subjects: ['语文', '数学', '英语', '其他'], subject: '', accuracy: '', comment: '', submissionText: '', attachmentIds: [], restoredIds: [], uploading: false, mediaReset: 0, readOnly: false },
  scope() { return ['homework', this.query.id]; },
  async loadData() {
    if (!this.query.id) throw new Error('缺少作业编号，请返回作业列表');
    const row = await api.homeworkDetail(this.query.id);
    const siblings = await api.homework({ date: row.date, studentId: row.studentId, attachments: false });
    const subject = this.data.subject || row.subject, draft = ui.draft(this.scope());
    return Object.assign({ submissionText: '', attachmentIds: [] }, draft, { date: row.date, queryId: row.id, readOnly: row.date > format.today(), subject, siblings: siblings.map(ui.decorate), subjectTasks: siblings.filter(h => (['语文', '数学', '英语'].includes(h.subject) ? h.subject : '其他') === subject).map(ui.decorate),
      nextReady: !!this.query.classId, item: Object.assign(format.decorate(row), ui.decorate(row), { submittedText: ui.time(row.submittedAt), reviewedText: ui.time(row.reviewedAt), completedText: ui.time(row.completedAt), later: !!row.updatedAt && row.updatedAt.slice(0, 10) > row.date, history: (row.history || []).map(ui.decorate) }),
      accuracy: draft.accuracy === undefined ? (row.accuracy === null || row.accuracy === undefined ? '' : String(row.accuracy)) : draft.accuracy, comment: draft.comment === undefined ? row.comment || '' : draft.comment, restoredIds: draft.attachmentIds || [] });
  },
  saveDraft() { ui.draft(this.scope(), { submissionText: this.data.submissionText, accuracy: this.data.accuracy, comment: this.data.comment, attachmentIds: this.data.attachmentIds }); },
  field(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }); this.saveDraft(); },
  media(event) { this.setData({ attachmentIds: Array.from(new Set(this.data.restoredIds.concat(event.detail.ids))) }); this.saveDraft(); },
  uploadState(event) { this.setData({ uploading: event.detail.busy }); },
  clearFiles() { if (this.data.busy || this.data.uploading) return; this.setData({ attachmentIds: [], restoredIds: [], mediaReset: this.data.mediaReset + 1 }); this.saveDraft(); },
  async chooseSubject(event) {
    if (this.data.uploading || this.data.busy) { toast('请等待当前操作完成'); return; }
    const subject = event.currentTarget.dataset.subject, tasks = this.data.siblings.filter(h => (['语文', '数学', '英语'].includes(h.subject) ? h.subject : '其他') === subject);
    this.setData({ subject, subjectTasks: tasks });
    if (tasks.length) await this.selectTask({ currentTarget: { dataset: { id: tasks[0].id } } });
  },
  async selectTask(event) {
    if (this.data.uploading || this.data.busy) { toast('请等待当前操作完成'); return; }
    this.query.id = event.currentTarget.dataset.id;
    this.setData({ item: null, mediaReset: this.data.mediaReset + 1, restoredIds: [], attachmentIds: [], submissionText: '', accuracy: '', comment: '', actionError: '' });
    await this.refresh();
  },
  async submit() {
    if (this.data.uploading || this.data.readOnly) { toast('请等待上传完成，且不能提交未来日期事实'); return; }
    const recheck = this.data.item.status === 'CORRECTION';
    if (recheck && !this.data.submissionText.trim() && !this.data.attachmentIds.length) { toast('请填写订正说明或上传新的订正附件'); return; }
    if (!await confirm(recheck ? '提交订正' : '提交作业', recheck ? '本次说明与新附件将一并提交老师复核。' : '本次内容将提交老师批改。')) return;
    await this.act(async () => {
      const body = ui.request(this, 'submit', { status: recheck ? 'PENDING_RECHECK' : 'PENDING_REVIEW', submissionText: this.data.submissionText.trim(), attachmentIds: this.data.attachmentIds, expectedVersion: this.data.item.version });
      await api.homeworkStatus(this.data.item.id, body.status, body); this.clearDraft();
    }, '已提交，等待老师确认');
  },
  clearDraft() { ui.draft(this.scope(), null); this.setData({ submissionText: '', attachmentIds: [], restoredIds: [], mediaReset: this.data.mediaReset + 1 }); },
  progress(event) { const status = event.currentTarget.dataset.status; this.act(() => api.homeworkStatus(this.data.item.id, status, ui.request(this, 'progress', { status, expectedVersion: this.data.item.version })), '作业状态已更新'); },
  async review(event) {
    if (this.data.busy || this.data.uploading || this.data.readOnly || !this.data.teacher || !this.data.item) return;
    const value = this.data.accuracy.trim() === '' ? NaN : Number(this.data.accuracy), status = event.currentTarget.dataset.status;
    if (!Number.isInteger(value) || value < 0 || value > 100) { toast('请填写0至100的整数正确率'); return; }
    if (status === 'CORRECTION' && !this.data.comment.trim()) { toast('请填写具体订正要求'); return; }
    const item = this.data.item, context = session.context();
    if (status === 'CORRECTION' && !await confirm('退回订正', item.studentName + ' · ' + item.title + '\n' + this.data.comment.trim())) return;
    const next = event.currentTarget.dataset.next && this.query.classId;
    const saved = await this.act(async () => {
      await api.reviewHomework(item.id, ui.request(this, 'review', { status, accuracy: value, comment: this.data.comment.trim(), expectedVersion: item.version }));
      if (session.isCurrentContext(context) && !this._unloaded) this.clearDraft();
    }, '批改结果已保存', !next);
    if (!saved || !next) return;
    this.setData({ busy: true });
    let pending;
    try {
      const work = await api.workbench({ date: item.date, classId: this.query.classId });
      if (!session.isCurrentContext(context) || this._unloaded) return;
      const states = ['PENDING_REVIEW', 'PENDING_RECHECK'];
      const allowed = states.includes(this.query.reviewStatus) ? [this.query.reviewStatus] : states;
      pending = work.homework.find(row => row.id !== item.id && allowed.includes(row.status));
      if (!pending) toast('当前筛选下已无待处理作业');
    } catch (error) {
      if (session.isCurrentContext(context) && !this._unloaded) toast('批改已保存，暂时无法读取下一份');
    } finally {
      if (session.isCurrentContext(context) && !this._unloaded) this.setData({ busy: false });
    }
    if (!session.isCurrentContext(context) || this._unloaded) return;
    if (pending) {
      this.setData({ subject: '' });
      return this.selectTask({ currentTarget: { dataset: { id: pending.id } } });
    }
    await this.refresh();
  },
  mistake(event) { wx.navigateTo({ url: '/pages/mistake-detail/index?id=' + event.currentTarget.dataset.id }); },
  follow() { wx.navigateTo({ url: '/pages/follow-ups/index?compose=1&date=' + this.data.item.date + '&studentId=' + this.data.item.studentId + '&sourceType=HOMEWORK&sourceId=' + this.data.item.id }); },
  backGrowth() { wx.navigateTo({ url: '/pages/growth/index?date=' + this.data.item.date + '&studentId=' + this.data.item.studentId }); }
});
