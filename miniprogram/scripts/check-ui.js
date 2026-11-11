const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const storage = new Map();
let app;
let component;
let page;
let route = 'pages/dashboard/index';
let launched = '';
let navigated = '';
let health = { status: 'UP', demo: true };
global.App = definition => { app = definition; };
global.Page = definition => { page = definition; };
global.Component = definition => { component = definition; };
global.getApp = () => app;
global.getCurrentPages = () => [{ route }];
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  stopPullDownRefresh() {}, showToast() {},
  navigateTo({ url }) { navigated = url; },
  reLaunch({ url, complete }) { launched = url; if (complete) complete(); }
};
require(path.join(root, 'app.js'));
app.onLaunch();
const api = require(path.join(root, 'utils/api'));
const identity = role => ({ token: 'ui-fixture-token', user: { id: 1, name: '测试账号', role }, demo: true });
function instance(definition) {
  return Object.assign({}, definition, definition.methods, { data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch); } });
}
function loadPage(name) {
  const file = path.join(root, 'pages', name, 'index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  return instance(page);
}
async function main() {
  const rosterTemplate = require('node:fs').readFileSync(path.join(root, 'pages/workbench/index.wxml'), 'utf8');
  assert.match(rosterTemplate, /<image[^>]*src="{{item.avatarUrl}}"/, '教师名单必须将取得的学生头像渲染为图片');
  app.setSession(identity('TEACHER'));
  require(path.join(root, 'components/app-nav/index.js'));
  const nav = instance(component);
  component.lifetimes.attached.call(nav);
  assert.equal(nav.data.active, 'dashboard');
  assert.ok(nav.data.groups.flatMap(group => group.items).some(item => item.page === 'attendance'));
  route = 'pages/mistake-detail/index';
  component.pageLifetimes.show.call(nav);
  assert.equal(nav.data.active, 'mistakes', '错题详情仍映射到错题模块');
  assert.equal(nav.data.allActive, true, '错题移至全部应用');
  nav.navigate({ currentTarget: { dataset: { page: 'follow-ups', kind: 'MESSAGE' } } });
  assert.equal(launched, '/pages/follow-ups/index?kind=MESSAGE', '消息导航直达家校作业消息');
  route = 'pages/points/index';
  component.pageLifetimes.show.call(nav);
  assert.equal(nav.data.allActive, true, '其他业务页选中全部应用');
  nav.setData({ open: true });
  nav.navigate({ currentTarget: { dataset: { page: 'homework' } } });
  assert.equal(launched, '/pages/homework/index');
  assert.equal(nav.data.open, false);
  launched = '';
  route = 'pages/homework/index';
  nav.navigate({ currentTarget: { dataset: { page: 'homework' } } });
  assert.equal(launched, '', '当前页重复点击不重启页面');
  app.setSession(identity('PARENT'));
  component.pageLifetimes.show.call(nav);
  const parentApps = nav.data.groups.flatMap(group => group.items);
  assert.ok(!parentApps.some(item => item.page === 'attendance'));
  assert.equal(parentApps.find(item => item.page === 'students').name, '我的孩子');

  api.me = async () => Object.assign({}, app.globalData.session.user, { demo: true });
  api.settings = async () => ({ institutionName: '样例机构' });
  api.classSatisfaction = async () => [{ classId: 1, className: '样例班级', parentCount: 2, submittedCount: 1, defaultCount: 1, satisfactionPercent: 50 }];
  api.dashboard = async () => ({ studentCount: 2, presentCount: 1, homeworkCount: 4, completedHomeworkCount: 1, subjectProgress: [{ subject: '数学', total: 4, completed: 1 }], weeklyAttendance: [{ date: '2026-10-27', total: 2, present: 1 }], students: [], activities: [] });
  const dashboard = loadPage('dashboard');
  dashboard.setData(await dashboard.loadData());
  assert.equal(dashboard.data.percent, 25);
  assert.equal(dashboard.data.progress[0].percent, 25);
  assert.equal(dashboard.data.weekly[0].percent, 50);
  assert.ok(!dashboard.data.management.some(item => item.page === 'attendance'));
  dashboard.go({ currentTarget: { dataset: { page: 'mistakes' } } });
  assert.equal(navigated, '/pages/mistakes/index');
  navigated = '';
  dashboard.go({ currentTarget: { dataset: { page: 'unknown' } } });
  assert.equal(navigated, '', '功能网格仅跳转已声明应用');
  api.dashboard = async () => ({ homeworkCount: 0, completedHomeworkCount: 0 });
  assert.equal((await dashboard.loadData()).percent, 0, '无任务不显示虚构完成率');

  app.setSession(identity('TEACHER'));
  app.globalData.selectedStudentIds = [1, 2];
  api.classes = async () => [{ id: 1, name: '样例班级' }];
  api.students = async () => [{ id: 1, name: '样例学生', classId: 1 }];
  const students = loadPage('students');
  students.onLoad({ date: '2026-11-04' });
  students.setData({ teacher: true, loading: false });
  students.setData(await students.loadData());
  assert.deepEqual(app.globalData.selectedStudentIds, [1, 2], '浏览名册不改写其他业务的选择');
  students.openStudent({ currentTarget: { dataset: { id: 1 } } });
  assert.equal(navigated, '/pages/student-detail/index?studentId=1&date=2026-11-04', '卡片进入单学生详情');
  navigated = '';
  for (const id of [2, '', 'invalid', -1]) students.openStudent({ currentTarget: { dataset: { id } } });
  assert.equal(navigated, '', '不可打开筛选结果之外或无效的学生');

  const requests = [];
  api.growth = async params => {
    requests.push({ api: 'growth', ...params });
    return { student: { id: 1, name: '样例学生', attendanceStatus: 'PRESENT', checkInTime: params.date + 'T16:00:00' }, homework: [
      { id: 11, studentId: 1, status: 'COMPLETED' }, { id: 12, studentId: 1, status: 'PENDING_REVIEW' }, { id: 21, studentId: 2, status: 'COMPLETED' }
    ] };
  };
  api.summaries = async params => { requests.push({ api: 'summaries', ...params }); return [{ id: 2, studentId: 2, status: 'PUBLISHED' }, { id: 1, studentId: 1, status: 'DRAFT' }]; };
  const detail = loadPage('student-detail');
  detail.onLoad({ studentId: '1', date: '2026-11-04' });
  detail.setData({ teacher: true });
  await detail.refresh();
  assert.deepEqual(requests, [{ api: 'growth', compact: true, studentId: 1, date: '2026-11-04' }, { api: 'summaries', studentId: 1, date: '2026-11-04' }]);
  assert.deepEqual(detail.data.homework.map(row => row.id), [11, 12]);
  assert.equal(detail.data.completed, 1);
  assert.equal(detail.data.student.arrival, '16:00');
  assert.equal(detail.data.student.departure, '未签退');
  assert.equal(detail.data.summary.statusText, '草稿');
  for (const [section, target] of [['homework', 'homework'], ['attendance', 'safety'], ['summary', 'summaries']]) {
    detail.openSection({ currentTarget: { dataset: { section } } });
    assert.equal(navigated, '/pages/' + target + '/index?studentId=1&date=2026-11-04' + (section === 'summary' ? '&quick=1' : ''), '详情入口只携带当前学生与日期');
  }
  detail.openHomework({ currentTarget: { dataset: { id: 12 } } });
  assert.equal(navigated, '/pages/homework-detail/index?id=12');
  navigated = '';
  detail.openHomework({ currentTarget: { dataset: { id: 21 } } });
  detail.openSection({ currentTarget: { dataset: { section: 'unknown' } } });
  assert.equal(navigated, '', '详情只允许打开本学生作业和已声明入口');
  assert.deepEqual(app.globalData.selectedStudentIds, [1, 2], '详情导航不依赖旧多选');
  detail.setData({ date: '2026-11-03' });
  await detail.refresh();
  assert.equal(requests.at(-1).date, '2026-11-03', '切换日期同时更新作业、考勤与小结');

  api.growth = async () => { throw new Error('无权查看该学生'); };
  await detail.refresh();
  assert.equal(detail.data.error, '无权查看该学生');
  detail.openSection({ currentTarget: { dataset: { section: 'summary' } } });
  detail.openHomework({ currentTarget: { dataset: { id: 12 } } });
  assert.equal(navigated, '', '权限失效后不可继续操作旧详情');
  api.growth = async () => ({ student: { id: 2 }, homework: [] });
  await assert.rejects(detail.loadData(), /不在当前权限范围/);
  for (const studentId of [undefined, '0', '-1', '1.5', 'invalid']) {
    const invalid = loadPage('student-detail');
    invalid.onLoad({ studentId });
    await assert.rejects(invalid.loadData(), /学生编号无效/);
  }
  app.setSession(identity('PARENT'));
  api.growth = async () => ({ student: { id: 1, attendanceStatus: 'LEFT', checkInTime: '2026-11-04T16:00:00', checkOutTime: '2026-11-04T18:30:00' }, homework: [], summary: { id: 1, teacherComment: '学习认真' } });
  api.summaries = async () => { throw new Error('家长不请求未发布小结'); };
  const childDetail = loadPage('student-detail');
  childDetail.onLoad({ studentId: '1', date: '2026-11-04' });
  await childDetail.refresh();
  assert.equal(childDetail.data.student.departure, '18:30');
  assert.equal(childDetail.data.summary.statusText, '已发布');
  childDetail.openSection({ currentTarget: { dataset: { section: 'summary' } } });
  assert.equal(navigated, '/pages/summaries/index?studentId=1&date=2026-11-04', '家长仅进入查看模式');

  for (const role of ['SUPER_ADMIN', 'ADMIN']) {
    const value = identity(role);
    value.user.campusIds = [1, 2];
    value.user.campuses = [{ id: 1, name: 'A校区' }, { id: 2, name: 'B校区' }];
    app.setSession(value);
    api.dashboard = async () => ({ studentCount: 8, presentCount: 5, teacherCount: 3, checkedInTeacherCount: 2, todayConsumed: 4 });
    const adminDashboard = loadPage('dashboard');
    adminDashboard.onLoad({ date: '2026-10-27' });
    await adminDashboard.onShow();
    assert.equal(adminDashboard.data.admin, true);
    assert.equal(adminDashboard.data.teacher, true, '老板不能误显示为家长端');
    assert.deepEqual(app.globalData.session.user.campusIds, [1, 2], '刷新首页保留校区授权');
    assert.ok(adminDashboard.data.applications.concat(adminDashboard.data.management).some(item => item.page === 'campuses'));
    assert.equal(adminDashboard.data.satisfactionStats[0].satisfactionPercent, 50);
    assert.equal(adminDashboard.data.operationMetrics.find(item => item.page === 'teacher-attendance').value, 2);
    adminDashboard.go({ currentTarget: { dataset: { page: 'attendance' } } });
    assert.equal(navigated, '/pages/attendance/index?date=2026-10-27');
    adminDashboard.go({ currentTarget: { dataset: { page: 'teacher-attendance' } } });
    assert.equal(navigated, '/pages/teacher-attendance/index?date=2026-10-27');
    const attendance = loadPage('attendance');
    attendance.onLoad({ date: '2026-10-27' });
    assert.equal(attendance.data.date, '2026-10-27');
    component.pageLifetimes.show.call(nav);
    assert.ok(nav.data.groups.flatMap(group => group.items).some(item => item.page === 'campuses'));
    nav.changeCampus({ detail: { value: '2' } });
    assert.equal(app.globalData.selectedCampusId, 2);
    assert.equal(nav.data.campusName, 'B校区');
  }
  api.campuses = async () => [{ id: 1, name: 'A校区', checkInRadius: 200 }, { id: 2, name: 'B校区', checkInRadius: 200 }];
  api.teacherAttendance = async () => [{ teacherId: 10, teacherName: '测试老师', campusId: 2, campusName: 'B校区', checkInTime: null, status: 'NOT_CHECKED_IN' }];
  const teacherAttendance = loadPage('teacher-attendance');
  teacherAttendance.setData(await teacherAttendance.loadData());
  assert.equal(teacherAttendance.data.records[0].canCheckIn, false, '管理员不能冒充老师打卡');
  app.setSession(identity('TEACHER'));
  teacherAttendance.setData(await teacherAttendance.loadData());
  assert.equal(teacherAttendance.data.records[0].canCheckIn, true);
  teacherAttendance.onLoad({ date: '2020-01-01' });
  teacherAttendance.setData(await teacherAttendance.loadData());
  assert.equal(teacherAttendance.data.records[0].canCheckIn, false, '不能补写历史签到');

  api.campuses = async () => [{ id: 1, name: 'A校区' }, { id: 2, name: 'B校区' }];
  api.users = async () => [{ id: 3, name: '校长', role: 'ADMIN', campusIds: [1], campusNames: ['A校区'] }];
  app.setSession(identity('SUPER_ADMIN'));
  const access = loadPage('campuses');
  access.setData(await access.loadData());
  access.edit({ currentTarget: { dataset: { id: 3 } } });
  access.toggleCampus({ currentTarget: { dataset: { id: 2 } } });
  let granted;
  api.updateAccess = async (id, ids) => { granted = { id, ids }; };
  wx.showModal = options => options.success({ confirm: true });
  await access.save();
  assert.deepEqual(granted, { id: 3, ids: [1, 2] }, '可将多个校区授权给同一校长');
  app.setSession(identity('ADMIN'));
  const principalAccess = loadPage('campuses');
  principalAccess.setData(await principalAccess.loadData());
  assert.equal(principalAccess.data.superAdmin, false);
  principalAccess.edit({ currentTarget: { dataset: { id: 3 } } });
  assert.equal(principalAccess.data.editingId, 0, '校长不可自行扩权');
  app.setSession(identity('PARENT'));
  component.pageLifetimes.show.call(nav);
  assert.ok(!nav.data.groups.flatMap(group => group.items).some(item => ['campuses', 'teacher-attendance'].includes(item.page)));

  api.health = async () => health;
  const login = loadPage('login');
  await login.checkHealth();
  assert.equal(login.data.demoAvailable, true);
  health = { status: 'UP', demo: false };
  await login.checkHealth();
  assert.equal(login.data.demoAvailable, false, '正式环境不显示演示身份入口');
  api.health = async () => { throw new Error('离线'); };
  await login.checkHealth();
  assert.equal(login.data.demoAvailable, false);
  assert.equal(login.data.connected, false);
  console.log('通过交互校验：学生名册单卡详情、作业/考勤/小结携学生日期跳转、权限失效与非法编号拦截、四角色首页/功能范围、老板运营统计、校区切换、多校区授权与防自行扩权、老师当天本人打卡条件、真实零数据、导航和断网恢复。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
