const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
function contentText(content) {
  if (typeof content === 'string') return format.businessText(content);
  if (content === null || content === undefined) return '暂无小结正文';
  if (Array.isArray(content)) return content.map(contentText).join('\n');
  const names = { attendance: '出勤情况', homework: '作业情况', mistakes: '错题情况', fitness: '体能情况', points: '积分情况', date: '日期', studentName: '学生', subject: '学科', title: '任务', status: '状态', total: '合计', completed: '已完成', count: '次数', duration: '时长（秒）', balance: '余额', project: '项目', summary: '小结', content: '内容' };
  return Object.keys(content).map(key => (names[key] || '记录') + '：' + contentText(content[key])).join('\n');
}
definePage({
  data: { students: [], items: [], editingId: '', teacherComment: '', feedbackId: '', rating: 5, feedbackText: '', ratings: [1, 2, 3, 4, 5], generated: '' },
  async loadData() {
    const selected = await selection(this, this.data.teacher);
    let items = await api.summaries({ date: this.data.date, studentId: selected.studentIds.length === 1 ? selected.studentIds[0] : '' });
    if (selected.studentIds.length > 1) items = items.filter(row => selected.studentIds.includes(Number(row.studentId)));
    if (!this.data.teacher) items = items.filter(row => row.status === 'PUBLISHED');
    return Object.assign({}, selected, { items: items.map(row => Object.assign(format.decorate(row), { contentText: contentText(row.content), publishedText: format.time(row.publishedAt) })) });
  },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids, editingId: '', feedbackId: '' }); this.refresh(); },
  async generate() {
    if (!this.data.studentIds.length) { toast('请先选择学生'); return; }
    await this.act(async () => {
      const result = await api.generateSummaries({ date: this.data.date, studentIds: this.data.studentIds });
      this.setData({ generated: '已生成 ' + result.generatedCount + ' 份小结草稿；已发布内容不会被覆盖。' });
    }, '小结草稿已生成');
  },
  edit(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    this.setData({ editingId: item.id, teacherComment: item.teacherComment || '' });
  },
  async save() {
    await this.act(async () => { await api.editSummary(this.data.editingId, this.data.teacherComment.trim()); this.setData({ editingId: '' }); }, '教师评语已保存');
  },
  async publish(event) {
    if (!await confirm('确认发布每日小结', '发布后保存正文快照，家长可阅读并评分；通知是否发送以实际返回状态为准。')) return;
    this.act(() => api.publishSummary(event.currentTarget.dataset.id), '小结已发布，可在小程序内阅读');
  },
  feedback(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    this.setData({ feedbackId: item.id, rating: item.feedbackRating || 5, feedbackText: item.feedbackText || '' });
  },
  rate(event) { this.setData({ rating: Number(event.currentTarget.dataset.rating) }); },
  async sendFeedback() {
    await this.act(async () => { await api.feedbackSummary(this.data.feedbackId, { rating: this.data.rating, text: this.data.feedbackText.trim() }); this.setData({ feedbackId: '' }); }, '感谢反馈，已提交');
  }
});
