const api = require('../../utils/api');
const format = require('../../utils/format');
const ui = require('../../utils/growth');
const { definePage, toast, confirm } = require('../../utils/page');

definePage({
  adminOnly: true,
  data: { campusId: '', campuses: [], campusNames: [], campusIndex: 0, classes: [], overview: {}, summary: null, readOnly: false, editing: false, content: '', followUp: '', nextPlan: '', version: 0, commentingClassId: 0, commentingClassName: '', commentText: '' },
  onLoad(query) { this.setData({ campusId: query.campusId || '' }); },
  async loadData() {
    const page = await api.campusReports({ date: this.data.date, campusId: this.data.campusId });
    const decorate = row => Object.assign({}, row, { updatedText: format.time(row.updatedAt) });
    return { campusId: page.campusId || '', campuses: page.campuses, campusNames: page.campuses.map(c => c.name), campusIndex: Math.max(0, page.campuses.findIndex(c => c.id === page.campusId)), overview: page.overview, readOnly: page.readOnly, summary: page.summary ? decorate(page.summary) : null, classes: page.classes.map(c => Object.assign({}, c, { comments: (c.comments || []).map(note => Object.assign({}, note, { createdText: format.time(note.createdAt) })) })) };
  },
  async switchScope(patch) {
    if (this.data.busy || (this.data.editing || this.data.commentingClassId) && !await confirm('切换记录范围', '未保存内容会清空，确认切换？')) return;
    this.setData(Object.assign({ editing: false, commentingClassId: 0, commentText: '', content: '', followUp: '', nextPlan: '' }, patch)); await this.refresh();
  },
  changeCampus(event) { const campus = this.data.campuses[Number(event.detail.value)]; if (campus) return this.switchScope({ campusId: campus.id }); },
  changeDate(event) { return this.switchScope({ date: event.detail.value }); },
  edit() {
    if (this.data.busy || this.data.readOnly || !this.data.campusId) return;
    const summary = this.data.summary || {};
    this.setData({ editing: true, content: summary.content || '', followUp: summary.followUp || '', nextPlan: summary.nextPlan || '', version: summary.version || 0, actionError: '' });
  },
  cancelEdit() { if (!this.data.busy) this.setData({ editing: false }); },
  async save() {
    if (!this.data.editing || this.data.busy || this.data.readOnly || !this.data.campusId) return;
    const content = this.data.content.trim(), followUp = this.data.followUp.trim(), nextPlan = this.data.nextPlan.trim();
    if (!content || content.length > 6000 || followUp.length > 2000 || nextPlan.length > 2000) { toast('总结必填且最多6000字，跟进和安排分别最多2000字'); return; }
    const campus = this.data.campusId, date = this.data.date, body = { content, followUp, nextPlan, expectedVersion: this.data.version };
    await this.act(async () => { await api.saveCampusReport(campus, date, body); if (!this._unloaded) this.setData({ editing: false }); }, '校区总结已保存');
  },
  comment(event) {
    if (this.data.busy || this.data.readOnly) return;
    const classroom = this.data.classes.find(c => Number(c.id) === Number(event.currentTarget.dataset.id));
    if (classroom) this.setData({ commentingClassId: classroom.id, commentingClassName: classroom.name, commentText: '', actionError: '' });
  },
  cancelComment() { if (!this.data.busy) this.setData({ commentingClassId: 0, commentText: '' }); },
  async saveComment() {
    if (this.data.busy || this.data.readOnly || !this.data.commentingClassId) return;
    const text = this.data.commentText.trim();
    if (!text || text.length > 2000) { toast('请填写班级点评，最多2000字'); return; }
    const id = this.data.commentingClassId, date = this.data.date, body = ui.request(this, 'campus-class-comment:' + id, { text });
    await this.act(async () => { await api.commentClassReport(id, date, body); if (!this._unloaded) this.setData({ commentingClassId: 0, commentText: '' }); }, '班级点评已保存');
  },
  openClass(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (!this.data.busy && this.data.classes.some(c => Number(c.id) === id)) wx.navigateTo({ url: '/pages/class-reports/index?classId=' + id + '&date=' + this.data.date });
  }
});
