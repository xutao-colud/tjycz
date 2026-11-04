const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast } = require('../../utils/page');
const { selection } = require('../../utils/students');
const statuses = ['', 'NOT_STARTED', 'IN_PROGRESS', 'PENDING_REVIEW', 'CORRECTION', 'PENDING_RECHECK', 'COMPLETED'];
const subjectStyles = {
  '语文': { subjectIcon: '语', subjectTone: 'chinese' },
  '数学': { subjectIcon: '数', subjectTone: 'math' },
  '英语': { subjectIcon: 'Aa', subjectTone: 'english' }
};
definePage({
  data: { students: [], items: [], subjects: ['全部学科', '语文', '数学', '英语'], subjectIndex: 0, statusIndex: 0, statusNames: ['全部状态'].concat(statuses.slice(1).map(value => format.label(value))), showCreate: false, taskSubjectIndex: 0, taskSubjects: ['语文', '数学', '英语'], typeIndex: 0, types: ['书面作业', '口头作业'], title: '', description: '', attachmentIds: [], uploading: false, mediaReset: 0, created: '' },
  async loadData() {
    const selected = await selection(this, this.data.teacher);
    const studentId = selected.studentIds.length === 1 ? selected.studentIds[0] : '';
    let items = await api.homework({ date: this.data.date, studentId, attachments: false, subject: this.data.subjectIndex ? this.data.subjects[this.data.subjectIndex] : '', status: statuses[this.data.statusIndex] });
    if (selected.studentIds.length > 1) items = items.filter(item => selected.studentIds.includes(Number(item.studentId)));
    return Object.assign({}, selected, {
      items: items.map(item => Object.assign(format.decorate(item), subjectStyles[item.subject] || { subjectIcon: String(item.subject || '学').slice(0, 1), subjectTone: 'other' }, {
        statusTone: item.status === 'COMPLETED' ? 'done' : item.status === 'CORRECTION' ? 'correction' : ['PENDING_REVIEW', 'PENDING_RECHECK'].includes(item.status) ? 'review' : item.status === 'IN_PROGRESS' ? 'progressing' : 'waiting'
      }))
    });
  },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids }); this.refresh(); },
  filter(event) { this.setData({ [event.currentTarget.dataset.field]: Number(event.detail.value) }); this.refresh(); },
  selectForm(event) { this.setData({ [event.currentTarget.dataset.field]: Number(event.detail.value) }); },
  toggleCreate() { this.setData({ showCreate: !this.data.showCreate }); },
  media(event) { this.setData({ attachmentIds: event.detail.ids }); },
  uploadState(event) { this.setData({ uploading: event.detail.busy }); },
  async create() {
    if (!this.data.studentIds.length || !this.data.title.trim()) { toast('请选择学生并填写作业标题'); return; }
    if (this.data.uploading) { toast('请等待附件上传完成'); return; }
    await this.act(async () => {
      const value = await api.createHomework({ date: this.data.date, subject: this.data.taskSubjects[this.data.taskSubjectIndex], type: ['WRITTEN', 'ORAL'][this.data.typeIndex], title: this.data.title.trim(), description: this.data.description.trim(), studentIds: this.data.studentIds, attachmentIds: this.data.attachmentIds });
      this.setData({ title: '', description: '', attachmentIds: [], mediaReset: this.data.mediaReset + 1, showCreate: false, created: '已为 ' + value.assignedCount + ' 位学生创建作业' });
    }, '作业已创建');
  },
  open(event) { wx.navigateTo({ url: '/pages/homework-detail/index?id=' + event.currentTarget.dataset.id + '&date=' + this.data.date }); }
});
