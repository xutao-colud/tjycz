const labels = {
  SUPER_ADMIN: '老板 / 总管理员', ADMIN: '校区校长', TEACHER: '教师', PARENT: '家长',
  CHECKED_IN: '已签到', NOT_CHECKED_IN: '未签到',
  ON_LEAVE:"已请假", IN_REVIEW:"待审核", APPROVED:"已批准", REQUESTED:"待审核", REJECTED:"已驳回", CANCELLED:"已取消", REFUND:"退款扣回", ACTIVE:"授权有效", EXPIRED:"已过期", REVOKED:"已撤销", USED:"已接送", VERIFIED:"核验通过", ROTATED:"接送码已重置", SAFETY:"请假与接送", VERIFY_REJECTED:"核验被拦截",
  NOT_ARRIVED: '未到校', PRESENT: '在校', LEFT: '已离校',
  NOT_STARTED: '未开始', IN_PROGRESS: '进行中', PENDING_REVIEW: '待批改',
  CORRECTION: '需订正', PENDING_RECHECK: '待复核', COMPLETED: '已完成',
  UNCORRECTED: '未订正', MASTERED: '已掌握',
  WRITTEN: '书面作业', ORAL: '口头作业', DRAFT: '草稿', PUBLISHED: '已发布',
  PENDING: '待处理', DONE: '已完成', SETTLED: '已结算', CONFIRMED: '已确认',
  NOT_CONFIGURED: '消息服务未配置', SENT: '已发送', FAILED: '发送失败',
  LOCATION: '定位签到', MANUAL: '人工操作', TEACHER_MANUAL: '教师人工操作', SYSTEM: '系统',
  HOMEWORK: '作业奖励', SUMMARY_PUBLISHED: '日小结发布', PRESET_AWARD: '固定奖励', PRIZE_REDEMPTION: '奖品兑换', TEACHER_GRANT: '增加课时', PHOTO: '拍照登记', HOMEWORK_COMPLETED: '作业完成奖励',
  MISTAKE: '错题奖励', MISTAKE_MASTERED: '错题掌握奖励', FITNESS: '体能奖励',
  FITNESS_COMPLETED: '体能打卡奖励', ADJUSTMENT: '人工调整',
  PURCHASE: '购买课时', GIFT: '赠送课时', CONSUME: '课时消耗',
  CONSUMPTION: '课时消耗', REVERSAL: '流水冲正', RECHARGE: '课时充值',
  RENEWAL: '续费入账', AFTER_SCHOOL: '晚辅课程', EVENING: '晚辅课程',
  MANUAL_ADJUST: '人工调整', FITNESS_CHECKIN: '体能打卡奖励',
  HOLIDAY: '假期课程', WEEKEND: '周末课程', RENEW: '续费课时',
  PURCHASED: '购买课时', GIFTED: '赠送课时', CONSUMED: '课时消耗', REVERSE: '流水冲正',
  WECHAT_OFFLINE: '线下微信', ALIPAY: '支付宝', CASH: '现金', BANK: '银行转账'
};
function label(value, fallback) { return labels[value] || fallback || '其他记录'; }
function today() {
  const date = new Date(Date.now() + 8 * 60 * 60 * 1000);
  return date.toISOString().slice(0, 10);
}
function time(value) {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 16);
}
function decorate(row) {
  return Object.assign({}, row, {
    statusText: label(row.status), typeText: label(row.type),
    createdText: time(row.createdAt), checkInText: time(row.checkInTime),
    checkOutText: time(row.checkOutTime), attendanceText: label(row.attendanceStatus),
    notificationText: label(row.notificationStatus, '通知状态待确认'),
    sourceText: label(row.source, '人工记录')
  });
}
function amount(value) { return (Number(value || 0) / 100).toFixed(2); }
function businessText(value) {
  return String(value).replace(/\b[A-Z][A-Z_]+\b/g, code => labels[code] || code);
}
module.exports = { label, today, time, decorate, amount, businessText };
