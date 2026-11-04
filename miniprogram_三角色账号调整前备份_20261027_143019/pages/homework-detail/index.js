const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
definePage({
  data: { item: null, accuracy: '', comment: '', attachmentIds: [], uploading: false, mediaReset: 0 },
  async loadData() {
    if (!this.query.id) throw new Error('缺少作业编号，请返回作业列表');
    const rows = await api.homework({ date: this.query.date || '' });
    const row = rows.find(item => String(item.id) === String(this.query.id));
    if (!row) throw new Error('作业不存在或当前账号无权访问');
    return { item: format.decorate(row), accuracy: row.accuracy === null || row.accuracy === undefined ? '' : String(row.accuracy), comment: row.comment || '' };
  },
  media(event) { this.setData({ attachmentIds: event.detail.ids }); },
  uploadState(event) { this.setData({ uploading: event.detail.busy }); },
  async attachFiles() {
    if (!this.data.attachmentIds.length) return;
    await api.homeworkAttachments(this.data.item.id, this.data.attachmentIds);
    this.setData({ attachmentIds: [], mediaReset: this.data.mediaReset + 1 });
  },
  saveAttachments() {
    if (!this.data.attachmentIds.length) { toast('请先上传附件'); return; }
    this.act(() => this.attachFiles(), '附件已保存');
  },
  async submit() {
    if (this.data.uploading) { toast('请等待上传完成'); return; }
    const recheck = this.data.item.status === 'CORRECTION';
    if (!await confirm(recheck ? '提交订正' : '提交作业', recheck ? '订正内容将提交老师复核。' : '作业将提交老师批改。')) return;
    this.act(async () => { await this.attachFiles(); await api.homeworkStatus(this.data.item.id, recheck ? 'PENDING_RECHECK' : 'PENDING_REVIEW'); }, '提交成功');
  },
  progress(event) { this.act(() => api.homeworkStatus(this.data.item.id, event.currentTarget.dataset.status), '作业状态已更新'); },
  async review(event) {
    const value = this.data.accuracy.trim() === '' ? NaN : Number(this.data.accuracy);
    if (!Number.isInteger(value) || value < 0 || value > 100) { toast('请填写 0 至 100 的正确率'); return; }
    const status = event.currentTarget.dataset.status;
    if (status === 'CORRECTION' && !this.data.comment.trim()) { toast('请填写需要订正的说明'); return; }
    if (!await confirm(status === 'COMPLETED' ? '确认完成批改' : '退回订正', status === 'COMPLETED' ? '完成后由后端按机构积分规则入账，同一份作业不重复奖励。' : '家长将看到批改说明并可重新提交订正。')) return;
    this.act(() => api.reviewHomework(this.data.item.id, { status, accuracy: value, comment: this.data.comment.trim() }), '批改已保存');
  }
});
