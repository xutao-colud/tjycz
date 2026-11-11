const api = require('../../utils/api');
const { definePage, toast, confirm } = require('../../utils/page');
const format = require('../../utils/format');
definePage({
  teacherOnly: true,
  data: { draftId: '', draft: null, items: [], drafts: [], students: [], studentIds: [], subjects: ['语文', '数学', '英语'], subjectIndex: 1, taskDate: '', classes: [], classNames: ['自选学生'], classIndex: 0 },
  onLoad(query) { this.setData({ draftId: query.id || '' }); },
  async loadData() {
    const draft = this.data.draftId ? await api.request('GET', '/homework-drafts/' + this.data.draftId) : null;
    if (!draft) { const rows = await api.request('GET', '/homework-drafts'); return { drafts: rows.map(r => Object.assign({}, r, { statusText: r.status === 'ASSIGNED' ? '已分配' : '待确认草稿', createdText: format.time(r.createdAt) })), draft: null }; }
    const students = (await api.students({})).filter(s => Number(s.campusId) === Number(draft.campusId)), classes = [];
    students.forEach(s => { if (!classes.some(c => Number(c.id) === Number(s.classId))) classes.push({ id: s.classId, name: s.className }); });
    const selected = this.data.studentIds.length ? this.data.studentIds : [draft.studentId];
    return { draft, items: draft.items, students, studentIds: selected.filter(id => students.some(s => Number(s.id) === Number(id))), classes, classNames: ['自选学生'].concat(classes.map(c => c.name)), taskDate: draft.date, subjectIndex: Math.max(0, this.data.subjects.indexOf(draft.subject)) };
  },
  open(event) { if (this.data.busy) return; this.setData({ draftId: event.currentTarget.dataset.id, draft: null, items: [], studentIds: [], classIndex: 0 }); return this.refresh(); },
  back() { if (this.data.busy) return; this.setData({ draftId: '', draft: null, items: [], studentIds: [] }); return this.refresh(); },
  subject(event) { this.setData({ subjectIndex: Number(event.detail.value) }); },
  datePick(event) { this.setData({ taskDate: event.detail.value }); },
  pickStudents(event) { if (!this.data.busy) this.setData({ studentIds: event.detail.ids, classIndex: 0 }); },
  classPick(event) { if (this.data.busy) return; const index = Number(event.detail.value); this.setData({ classIndex: index, studentIds: index ? this.data.students.filter(s => Number(s.classId) === Number(this.data.classes[index - 1].id)).map(s => s.id) : [] }); },
  edit(event) { if (this.data.busy) return; const index = Number(event.currentTarget.dataset.index), items = this.data.items.slice(); items[index] = Object.assign({}, items[index], { [event.currentTarget.dataset.field]: event.detail.value }); this.setData({ items }); },
  remove(event) { if (!this.data.busy && this.data.items.length > 1) this.setData({ items: this.data.items.filter(q => Number(q.id) !== Number(event.currentTarget.dataset.id)) }); },
  async persist() { const d = this.data.draft; const draft = await api.request('PATCH', '/homework-drafts/' + d.id, { expectedVersion: d.version, subject: this.data.subjects[this.data.subjectIndex], date: this.data.taskDate, items: this.data.items }); if (!this._unloaded) this.setData({ draft, items: draft.items }); return draft; },
  async save() { if (!this.data.draft || this.data.busy) return; await this.act(() => this.persist(), '草稿已保存，尚未分配', false); },
  async assign() {
    if (!this.data.draft || this.data.busy) return;
    if (!this.data.studentIds.length) { toast('请选择接收学生或班级'); return; }
    if (!await confirm('确认分配作业', '将为 ' + this.data.studentIds.length + ' 位学生分配 ' + this.data.items.length + ' 道题。请核对题干及裁图，确认图片不含其他孩子姓名或个人信息。')) return;
    await this.act(async () => { const draft = await this.persist(); const result = await api.request('POST', '/homework-drafts/' + draft.id + '/assign', { expectedVersion: draft.version, studentIds: this.data.studentIds, confirmed: true }); if (!this._unloaded) this.setData({ draft: result }); }, '作业已分配，等待学生完成');
  },
  scan() { wx.navigateTo({ url: '/pages/scan/index' }); },
  source() { wx.navigateTo({ url: '/pages/scan/index?jobId=' + this.data.draft.scanId }); },
  homework() { wx.navigateTo({ url: '/pages/homework/index?date=' + this.data.draft.date }); }
});
