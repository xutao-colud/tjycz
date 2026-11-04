const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast, confirm } = require('../../utils/page');
definePage({
  data: { item: null, correctionText: '', teacherComment: '', attachmentIds: [], uploading: false, mediaReset: 0 },
  async loadData() {
    if (!this.query.id) throw new Error('缺少错题编号');
    const row = await api.request('GET', '/mistakes/' + this.query.id);
    if (!row) throw new Error('错题不存在或当前账号无权访问');
    return { item: Object.assign(format.decorate(row), {
      statusText: row.status === 'PENDING_REVIEW' ? '待复核' : format.label(row.status),
      history: (row.history || []).map(h => Object.assign(format.decorate(h), { fromText: format.label(h.fromStatus), toText: format.label(h.toStatus) })),
      corrections: (row.corrections || []).map(format.decorate),
      reviews: (row.reviews || []).map(review => Object.assign(format.decorate(review), { statusText: review.status === 'PENDING' ? '待复习' : '已复习', completedText: format.time(review.completedAt), canComplete: review.status === 'PENDING' && review.dueDate <= format.today() }))
    }), teacherComment: row.teacherComment || '' };
  },
  media(event) { this.setData({ attachmentIds: event.detail.ids }); },
  uploadState(event) { this.setData({ uploading: event.detail.busy }); },
  async correct() {
    if (!this.data.correctionText.trim()) { toast('请简述订正过程，可同时上传订正照片'); return; }
    await this.act(async () => {
      await api.correctMistake(this.data.item.id, { text: this.data.correctionText.trim(), attachmentIds: this.data.attachmentIds });
      this.setData({ correctionText: '', attachmentIds: [], mediaReset: this.data.mediaReset + 1 });
    }, '订正已提交，等待复核');
  },
  async review(event) {
    const approved = event.currentTarget.dataset.approved === 'yes';
    if (!approved && !this.data.teacherComment.trim()) { toast('请填写继续订正的原因'); return; }
    if (!await confirm(approved ? '确认已掌握' : '退回继续订正', approved ? '后端将按机构规则奖励积分，并生成第 1、3、7 天复习任务。' : '本题将返回未订正状态。')) return;
    this.act(() => api.reviewMistake(this.data.item.id, { approved, comment: this.data.teacherComment.trim() }), '复核结果已保存');
  },
  async complete(event) {
    if (!await confirm('完成本次复习', '确认孩子已独立回顾并掌握本题？')) return;
    this.act(() => api.completeReview(event.currentTarget.dataset.id), '本次复习已完成');
  }
});
