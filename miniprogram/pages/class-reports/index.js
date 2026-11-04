const api = require('../../utils/api');
const ui = require('../../utils/growth');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');

definePage({
  teacherOnly: true,
  data: { classId: '', classes: [], classNames: [], classIndex: 0, overview: {}, report: null, readOnly: false, editing: false, commenting: false, commentText: '', content: '', followUp: '', nextPlan: '', version: 0, comments: [], commenting: false, commentText: '' },
  onLoad(query) { this.setData({ classId: query.classId || '' }); },
  async loadData() {
    const page = await api.classReports({ date: this.data.date, classId: this.data.classId });
    return { classId: page.classId || '', classes: page.classes, classNames: page.classes.map(c => c.name + ' · ' + c.campusName), classIndex: Math.max(0, page.classes.findIndex(c => c.id === page.classId)), comments: (page.comments || []).map(note => Object.assign({}, note, { createdText: format.time(note.createdAt) })), overview: page.overview, readOnly: page.readOnly, report: page.report ? Object.assign({}, page.report, { updatedText: format.time(page.report.updatedAt) }) : null };
  },
  async changeClass(event) {
    if (this.data.busy) return;
    const classroom = this.data.classes[Number(event.detail.value)];
    if (!classroom || (this.data.editing || this.data.commenting) && !await confirm('切换班级', '未保存内容会清空，确认切换？')) return;
    this.setData({ classId: classroom.id, editing: false, commenting: false, commentText: '', content: '', followUp: '', nextPlan: '' }); await this.refresh();
  },
  async changeDate(event) {
    if (this.data.busy || (this.data.editing || this.data.commenting) && !await confirm('切换日期', '未保存内容会清空，确认切换？')) return;
    this.setData({ date: event.detail.value, editing: false, commenting: false, commentText: '', content: '', followUp: '', nextPlan: '' }); await this.refresh();
  },
  edit() {
    if (this.data.busy || this.data.readOnly || !this.data.classId) return;
    const report = this.data.report || {};
    this.setData({ editing: true, content: report.content || '', followUp: report.followUp || '', nextPlan: report.nextPlan || '', version: report.version || 0, actionError: '' });
  },
  cancelEdit() { if (!this.data.busy) this.setData({ editing: false, commenting: false, commentText: '', content: '', followUp: '', nextPlan: '' }); },
  comment() { if (this.data.admin && !this.data.busy && !this.data.readOnly && this.data.classId) this.setData({ commenting: true, commentText: '', actionError: '' }); },
  cancelComment() { if (!this.data.busy) this.setData({ commenting: false, commentText: '' }); },
  async saveComment() {
    if (!this.data.admin || !this.data.commenting || this.data.busy || this.data.readOnly || !this.data.classId) return;
    const text = this.data.commentText.trim();
    if (!text || text.length > 2000) { toast('请填写点评，最多2000字'); return; }
    const id = this.data.classId, date = this.data.date, body = ui.request(this, 'class-report-comment', { text });
    await this.act(async () => { await api.commentClassReport(id, date, body); if (!this._unloaded) this.setData({ commenting: false, commentText: '' }); }, '班级点评已保存');
  },
  async save() {
    if (!this.data.editing || this.data.readOnly || !this.data.classId || this.data.busy) return;
    const content = this.data.content.trim(), followUp = this.data.followUp.trim(), nextPlan = this.data.nextPlan.trim();
    if (!content || content.length > 6000 || followUp.length > 2000 || nextPlan.length > 2000) { toast('请填写日报正文（最多6000字），跟进和安排分别最多2000字'); return; }
    const classId = this.data.classId, date = this.data.date, body = { content, followUp, nextPlan, expectedVersion: this.data.version };
    await this.act(async () => { await api.saveClassReport(classId, date, body); if (!this._unloaded) this.setData({ editing: false }); }, '班级日报已保存');
  }
});
