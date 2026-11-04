const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast } = require('../../utils/page');
const { selection } = require('../../utils/students');
const statuses = ['', 'UNCORRECTED', 'PENDING_REVIEW', 'MASTERED'];
const subjectStyles = {
  '语文': { subjectIcon: '语', subjectTone: 'chinese' },
  '数学': { subjectIcon: '数', subjectTone: 'math' },
  '英语': { subjectIcon: 'Aa', subjectTone: 'english' }
};
definePage({
  data: { students: [], items: [], subjects: ['全部学科', '语文', '数学', '英语'], subjectIndex: 0, statusIndex: 0, statusNames: ['全部状态', '未订正', '待复核', '已掌握'], showCreate: false, taskSubjects: ['语文', '数学', '英语'], taskSubjectIndex: 0, title: '', knowledgePoint: '', errorReason: '', homeworkId: '', attachmentIds: [], uploading: false, mediaReset: 0, book: null },
  async loadData() {
    const selected = await selection(this, false);
    const items = selected.studentIds.length ? await api.mistakes({ studentId: selected.studentIds[0], subject: this.data.subjectIndex ? this.data.subjects[this.data.subjectIndex] : '', status: statuses[this.data.statusIndex] }) : [];
    return Object.assign({}, selected, {
      items: items.map(row => Object.assign(format.decorate(row), subjectStyles[row.subject] || { subjectIcon: String(row.subject || '学').slice(0, 1), subjectTone: 'other' }, {
        statusText: row.status === 'PENDING_REVIEW' ? '待复核' : format.label(row.status),
        pendingReviews: (row.reviews || []).filter(review => review.status === 'PENDING').length,
        completedReviews: (row.reviews || []).filter(review => review.status !== 'PENDING').length,
        createdDate: row.createdAt ? format.time(row.createdAt).slice(0, 10) : '—'
      }))
    });
  },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids, book: null }); this.refresh(); },
  filter(event) { this.setData({ [event.currentTarget.dataset.field]: Number(event.detail.value) }); this.refresh(); },
  selectSubject(event) { this.setData({ taskSubjectIndex: Number(event.detail.value) }); },
  toggleCreate() { this.setData({ showCreate: !this.data.showCreate }); },
  media(event) { this.setData({ attachmentIds: event.detail.ids }); },
  uploadState(event) { this.setData({ uploading: event.detail.busy }); },
  async create() {
    if (!this.data.studentIds.length || (!this.data.title.trim() && !this.data.attachmentIds.length)) { toast('请选择学生，填写题目或上传题目照片'); return; }
    if (this.data.uploading) { toast('请等待照片上传完成'); return; }
    const linked = this.data.homeworkId.trim();
    if (linked && (!Number.isInteger(Number(linked)) || Number(linked) <= 0)) { toast('关联作业编号须为正整数'); return; }
    await this.act(async () => {
      const payload = { studentId: this.data.studentIds[0], subject: this.data.taskSubjects[this.data.taskSubjectIndex], title: this.data.title.trim(), knowledgePoint: this.data.knowledgePoint.trim(), errorReason: this.data.errorReason.trim(), attachmentIds: this.data.attachmentIds };
      if (linked) payload.homeworkId = Number(linked);
      await api.createMistake(payload);
      this.setData({ title: '', knowledgePoint: '', errorReason: '', homeworkId: '', attachmentIds: [], mediaReset: this.data.mediaReset + 1, showCreate: false });
    }, '错题已录入');
  },
  async printBook() {
    if (!this.data.studentIds.length || !this.data.items.length) { toast('请先选择有错题的孩子'); return; }
    await this.act(async () => { const book = await api.request('POST', '/mistake-books', { studentId: this.data.studentIds[0], mistakeIds: this.data.items.map(item => item.id) }); this.setData({ book }); }, 'PDF打印本已生成', false);
  },
  openBook() { if (this.data.book) return this.act(() => require('../../utils/teaching').openBook(this, this.data.book), '', false); },
  capture() { wx.navigateTo({ url: '/pages/capture/index?kind=MISTAKE&studentId=' + this.data.studentIds[0] }); },
  open(event) { wx.navigateTo({ url: '/pages/mistake-detail/index?id=' + event.currentTarget.dataset.id + '&studentId=' + this.data.studentIds[0] }); }
});
