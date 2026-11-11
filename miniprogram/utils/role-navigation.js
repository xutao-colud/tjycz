// Priorities only: page/API authorization continues to use the existing permissions.
const profiles = {
  PARENT: [
    ['孩子的今天', ['growth', 'homework', 'summaries', 'notifications', 'safety']],
    ['成长与鼓励', ['mistakes', 'fitness', 'points', 'rewards', 'adventure']],
    ['家校与服务', ['follow-ups', 'family-services', 'lessons', 'students', 'refunds', 'privacy', 'complaints']]
  ],
  TEACHER: [
    ['今日带班', ['workbench', 'students', 'attendance', 'homework', 'homework-drafts', 'scan', 'summaries']],
    ['家校与班级记录', ['follow-ups', 'safety', 'notifications', 'class-reports']],
    ['学生成长与续费', ['mistakes', 'fitness', 'points', 'rewards', 'family-services', 'lessons', 'growth', 'adventure']],
    ['我的考勤', ['teacher-attendance']]
  ],
  ADMIN: [
    ['校区日常管理', ['students', 'attendance', 'teacher-attendance', 'safety', 'summaries', 'follow-ups']],
    ['教学与服务质量', ['class-reports', 'campus-reports', 'complaints', 'notifications']],
    ['课时与续费', ['lessons', 'family-services', 'refunds']],
    ['教学明细', ['workbench', 'homework', 'homework-drafts', 'scan', 'mistakes', 'fitness', 'points', 'rewards', 'growth', 'adventure']],
    ['校区设置', ['campuses', 'privacy']]
  ],
  SUPER_ADMIN: [
    ['运营与校区', ['principal', 'campuses', 'students', 'teacher-attendance']],
    ['课时与费用', ['lessons', 'family-services', 'refunds']],
    ['管理与服务质量', ['campus-reports', 'class-reports', 'summaries', 'follow-ups', 'safety', 'complaints', 'notifications', 'privacy']],
    ['教学业务明细', ['workbench', 'attendance', 'homework', 'homework-drafts', 'scan', 'mistakes', 'fitness', 'points', 'rewards', 'growth', 'adventure']]
  ]
};
function groups(entries, role) {
  return (profiles[role] || []).map(([title, pages]) => ({ title, items: pages.map(page => entries.find(item => item.page === page)).filter(Boolean).map(item => Object.assign({}, item, {
    name: role === 'PARENT' && item.parentName ? item.parentName : item.page === 'teacher-attendance' && role === 'TEACHER' ? '我的打卡' : item.page === 'family-services' && role !== 'PARENT' ? '续费意向与订餐' : item.name
  })) })).filter(group => group.items.length);
}
function tabs(role) {
  if (role === 'ADMIN') return [
    { page:'dashboard', section:'overview', name:'概览', icon:'home' },
    { page:'principal', section:'todos', name:'待办', icon:'summaries' },
    { page:'principal', section:'affairs', name:'校务', icon:'students' }
  ];
  if (role === 'SUPER_ADMIN') return [
    { page:'dashboard', name:'运营', icon:'home' },
    { page:'lessons', name:'课时费用', icon:'lessons' },
    { page:'campuses', name:'校区', icon:'students' }
  ];
  return [
    { page:'dashboard', name:role === 'PARENT' ? '今日' : '工作台', icon:'home' },
    role === 'TEACHER' ? { page:'students', name:'学生', icon:'students' } : { page:'homework', name:'学习', icon:'homework' },
    { page:'follow-ups', kind:'MESSAGE', name:'消息', icon:'summaries' }
  ];
}
module.exports = { groups, tabs };
