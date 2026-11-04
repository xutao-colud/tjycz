const api = require('./api');
const format = require('./format');
const statuses = { DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '审核通过', REJECTED: '已退回', PUBLISHED: '已发布' };
const actions = { GENERATE: '生成草稿', REFRESH: '刷新草稿', EDIT: '编辑内容', SUBMIT: '提交审核', WITHDRAW: '撤回待审', APPROVE: '审核通过', REJECT: '退回修改', PUBLISH: '发布给家长' };
function decorate(row) {
  return Object.assign({}, format.decorate(row), {
    statusText: statuses[row.status] || '状态待确认',
    reviewTone: row.status === 'REJECTED' ? 'review-rejected' : row.status === 'IN_REVIEW' ? 'review-pending' : row.status === 'DRAFT' ? 'warn' : '',
    submittedText: format.time(row.submittedAt), reviewedText: format.time(row.reviewedAt),
    reviewHistory: (row.reviewHistory || []).map(entry => Object.assign({}, entry, {
      actionText: actions[entry.action] || '操作记录', statusText: statuses[entry.toStatus] || '状态待确认', createdText: format.time(entry.createdAt)
    }))
  });
}
function submit(item) { return api.request('POST', '/summaries/' + item.id + '/submit-review', { expectedVersion: item.version }); }
function withdraw(item) { return api.request('POST', '/summaries/' + item.id + '/withdraw-review', { expectedVersion: item.version }); }
function review(item, approved, comment) { return api.request('POST', '/summaries/' + item.id + '/review', { expectedVersion: item.version, approved, comment }); }
function save(item, content, teacherComment, photoId) {
  const body = { expectedVersion: item.version, content, teacherComment };
  if (photoId !== undefined) body.photoId = photoId;
  return api.request('PATCH', '/summaries/' + item.id, body);
}
module.exports = { decorate, submit, withdraw, review, save };
