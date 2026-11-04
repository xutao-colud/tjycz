const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
const { selection } = require('../../utils/students');
const reviewFlow = require('../../utils/summary-review');
const session = require('../../utils/session');
const contentNames = {
  attendance: '出勤情况', homework: '作业情况', mistakes: '错题情况', fitness: '体能情况',
  points: '积分情况', lessons: '课时情况', accounts: '课程账户', date: '日期',
  studentName: '学生', subject: '学科', title: '任务', status: '状态', total: '合计',
  completed: '已完成', count: '次数', duration: '时长（秒）', balance: '余额',
  project: '项目', summary: '小结', content: '内容', checkInTime: '到校时间',
  checkOutTime: '离校时间', accuracy: '正确率', courseName: '课程', unit: '单位',
  amount: '变动', reason: '说明', homeworkTotal: '作业总数', homeworkCompleted: '已完成作业'
};
function contentText(content) {
  if (content === null || content === undefined) return '—';
  if (typeof content === 'string') return format.businessText(content);
  if (typeof content === 'boolean') return content ? '是' : '否';
  if (typeof content !== 'object') return String(content);
  if (Array.isArray(content)) return content.length ? content.map(contentText).join('\n') : '暂无记录';
  const keys = Object.keys(content);
  return keys.length ? keys.map(key => (contentNames[key] || key) + '：' + contentText(content[key])).join('\n') : '暂无记录';
}
function sectionMeta(title) {
  if (/考勤|出勤|attendance/i.test(title)) return { icon: 'attendance', tone: 'green' };
  if (/作业|语文|数学|英语|homework/i.test(title)) return { icon: 'homework', tone: 'blue' };
  if (/错题|mistake/i.test(title)) return { icon: 'mistakes', tone: 'purple' };
  if (/体能|运动|fitness/i.test(title)) return { icon: 'fitness', tone: 'orange' };
  if (/积分|points/i.test(title)) return { icon: 'points', tone: 'orange' };
  if (/课时|课程|lessons|accounts/i.test(title)) return { icon: 'lessons', tone: 'blue' };
  return { icon: 'summaries', tone: 'green' };
}
function contentSections(content) {
  const sections = [];
  const add = (title, text) => sections.push(Object.assign({ id: 'section-' + sections.length, title, text }, sectionMeta(title)));
  if (typeof content === 'string') {
    const text = content.trim();
    if (/^[\[{]/.test(text)) {
      try {
        const parsed = JSON.parse(text);
        if (parsed && typeof parsed === 'object') return contentSections(parsed);
      } catch (error) { /* Unstructured text is displayed below. */ }
    }
    const lines = format.businessText(content).split(/\r?\n/);
    let remaining = [];
    const flush = () => {
      if (remaining.length) add('学习记录', remaining.join('\n'));
      remaining = [];
    };
    lines.forEach(line => {
      const trimmed = line.trim();
      const heading = trimmed.match(/^(考勤|出勤|作业|错题|体能|积分|语文|数学|英语)[：:](.*)$/);
      const account = trimmed.match(/^(课时账户\s*[^：:]+)[：:](.*)$/);
      if (heading) {
        flush();
        const title = { 考勤: '出勤情况', 出勤: '出勤情况', 作业: '作业情况', 错题: '错题情况', 体能: '体能情况', 积分: '积分情况', 语文: '语文作业', 数学: '数学作业', 英语: '英语作业' }[heading[1]];
        add(title, heading[2].trim() || '暂无记录');
      } else if (/^作业完成\s/.test(trimmed)) {
        flush();
        add('作业概况', trimmed);
      } else if (account) {
        flush();
        add(account[1], account[2].trim() || '暂无记录');
      } else if (trimmed) remaining.push(line);
    });
    flush();
  } else if (Array.isArray(content)) {
    content.forEach((value, index) => add('学习记录 ' + (index + 1), contentText(value)));
  } else if (content && typeof content === 'object') {
    Object.keys(content).forEach(key => add(contentNames[key] || key, contentText(content[key])));
  } else if (content !== null && content !== undefined) {
    add('学习记录', contentText(content));
  }
  if (!sections.length) add('学习记录', '暂无小结正文');
  return sections;
}
definePage({
  data: { quick: false, quickName: '', readOnly: false, draftPhoto: null, photoChanged: false,  students: [], items: [], editingId: '', editingVersion: 0, editContent: '', teacherComment: '', reviewingId: '', reviewVersion: 0, reviewComment: '', historyId: '', historyEntryId: '', feedbackId: '', satisfaction: 'SATISFIED', feedbackText: '', satisfactions: [{ value: 'SATISFIED', label: '满意' }, { value: 'DISSATISFIED', label: '不满意' }], generated: '', generateOpen: false },
  async loadData() {
    const selected = await selection(this, this.data.teacher);
    const quick = this.data.teacher && this.query.quick === '1' && selected.studentIds.length === 1;
    let items = await api.summaries({ date: this.data.date, studentId: selected.studentIds.length === 1 ? selected.studentIds[0] : '' });
    if (selected.studentIds.length > 1) items = items.filter(row => selected.studentIds.includes(Number(row.studentId)));
    if (!this.data.teacher) items = items.filter(row => row.status === 'PUBLISHED');
    const quickStudent = quick && selected.students.find(row => Number(row.id) === selected.studentIds[0]);
    return Object.assign({}, selected, { quick, readOnly: this.data.date > format.today(), quickName: quickStudent ? quickStudent.name : '', items: items.map(row => Object.assign(reviewFlow.decorate(row), {
      sections: contentSections(row.content),
      initial: Array.from(String(row.studentName || '生'))[0],
      publishedText: format.time(row.publishedAt)
    })) });
  },
  toggleGenerate() { this.setData({ generateOpen: !this.data.generateOpen }); },
  cancelEdit() { this.setData({ editingId: '', editContent: '', teacherComment: '', draftPhoto: null, photoChanged: false }); },
  cancelReview() { this.setData({ reviewingId: '', reviewComment: '' }); },
  cancelFeedback() { this.setData({ feedbackId: '', feedbackText: '' }); },
  pickStudents(event) { if (this.data.busy) return; this.cancelEdit(); this.setData({ studentIds: event.detail.ids, reviewingId: '', feedbackId: '', historyId: '', historyEntryId: '' }); this.refresh(); },
  changeDate(event) { if (this.data.busy) return; this.cancelEdit(); this.setData({ date: event.detail.value, reviewingId: '', feedbackId: '', historyId: '', historyEntryId: '' }); this.refresh(); },
  async generate() {
    if (!this.data.studentIds.length) { toast('请先选择学生'); return; }
    await this.act(async () => {
      const result = await api.generateSummaries({ date: this.data.date, studentIds: this.data.studentIds, ...(this.data.quick ? { keepExisting: true } : {}) });
      this.setData({ generated: '已生成 / 刷新 ' + result.generatedCount + ' 份草稿；待审、已通过和已发布的内容保持不变。' });
    }, '小结草稿已生成');
  },
  edit(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    if (!item || !item.canEdit) return;
    this.setData({ editingId: item.id, editingVersion: item.version, editContent: contentText(item.content), teacherComment: item.teacherComment || '', draftPhoto: (item.photos || [])[0] || null, photoChanged: false, reviewingId: '' });
  },
  quickComment(event) {
    const text = event.currentTarget.dataset.text, current = this.data.teacherComment.trim();
    if (!current.includes(text) && current.length + text.length + 1 <= 2000) this.setData({ teacherComment: (current ? current + '\n' : '') + text });
  },
  choosePhoto() {
    const item = this.data.items.find(row => row.id === this.data.editingId && row.canEdit);
    if (!item) return;
    const context = session.context();
    this.act(async () => {
      const file = await require('../../utils/teaching').choosePhoto(this, item.campusId);
      if (!this._unloaded && session.isCurrentContext(context) && this.data.editingId === item.id) this.setData({ draftPhoto: file, photoChanged: true });
    }, '', false);
  },
  removePhoto() { this.setData({ draftPhoto: null, photoChanged: true }); },
  async save() {
    if (!this.data.editContent.trim()) { toast('请填写小结正文'); return; }
    if (this.data.editContent.trim().length > 12000) { toast('小结正文最多12000字'); return; }
    const photoId = this.data.photoChanged ? (this.data.draftPhoto ? this.data.draftPhoto.id : null) : undefined;
    const context = session.context();
    await this.act(async () => {
      await reviewFlow.save({ id: this.data.editingId, version: this.data.editingVersion }, this.data.editContent.trim(), this.data.teacherComment.trim(), photoId);
      if (!this._unloaded && session.isCurrentContext(context)) this.cancelEdit();
    }, '小结已保存，可提交独立审核');
  },
  async submitReview(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    if (!item || !item.canSubmit) return;
    if (!await confirm('提交独立审核', item.studentName + ' · ' + item.date + '\n提交后内容冻结，请由另一位校长或老板审核。提交者可撤回待审稿。')) return;
    this.act(() => reviewFlow.submit(item), '已提交，请等待审核');
  },
  async withdrawReview(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    if (!item || !item.canWithdraw) return;
    if (!await confirm('撤回待审小结', '撤回后可继续编辑，再次提交会保留本次提交和撤回记录。')) return;
    this.act(() => reviewFlow.withdraw(item), '已撤回，可继续修改');
  },
  beginReview(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    if (!item || !item.canReview) return;
    this.setData({ reviewingId: item.id, reviewVersion: item.version, reviewComment: '', editingId: '' });
  },
  async decideReview(event) {
    const comment = this.data.reviewComment.trim(), approved = event.currentTarget.dataset.approved === 'yes';
    if (!comment) { toast('请填写审核意见'); return; }
    if (!await confirm(approved ? '通过独立审核' : '退回修改', comment)) return;
    await this.act(async () => { await reviewFlow.review({ id: this.data.reviewingId, version: this.data.reviewVersion }, approved, comment); this.cancelReview(); }, '审核结论已保存');
  },
  toggleHistory(event) { const id = event.currentTarget.dataset.id; this.setData({ historyId: this.data.historyId === id ? '' : id, historyEntryId: '' }); },
  toggleHistoryVersion(event) { const id = event.currentTarget.dataset.id; this.setData({ historyEntryId: this.data.historyEntryId === id ? '' : id });
  },
  async publish(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    if (!item || !item.canPublish) { toast('请先完成独立审核'); return; }
    let confirmedConcerns = false;
    try {
      const issues = (await api.followUps({ date: item.date, studentId: item.studentId, kind: 'ISSUE' })).filter(c => ['OPEN', 'IN_PROGRESS'].includes(c.status));
      if (issues.length) {
        confirmedConcerns = await confirm('核对 ' + item.studentName + ' 的关注事项', issues.map(c => c.title + '：' + c.reason + '\n措施：' + c.measures).join('\n\n'));
        if (!confirmedConcerns) return;
      }
    } catch (error) { toast(error.message); return; }
    if (!await confirm('发布审核通过的小结', '将发布 ' + (item.reviewedName || '审核人') + ' 已核对的固定内容，家长可阅读并反馈。')) return;
    this.act(() => api.publishSummary(item.id, confirmedConcerns), '小结已发布，可在小程序内阅读');
  },
  feedback(event) {
    const item = this.data.items.find(row => String(row.id) === String(event.currentTarget.dataset.id));
    this.setData({ feedbackId: item.id, satisfaction: item.feedbackSatisfaction || 'SATISFIED', feedbackText: item.feedbackText || '' });
  },
  rate(event) { this.setData({ satisfaction: event.currentTarget.dataset.satisfaction }); },
  async sendFeedback() {
    if (this.data.satisfaction === 'DISSATISFIED' && !this.data.feedbackText.trim()) { toast('请填写不满意的原因'); return; }
    await this.act(async () => { await api.feedbackSummary(this.data.feedbackId, { satisfaction: this.data.satisfaction, text: this.data.feedbackText.trim() }); this.setData({ feedbackId: '' }); }, '感谢反馈，已提交');
  }
});
