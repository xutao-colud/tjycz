const api = require('../../utils/api');
const { currentLocation } = require('../../utils/location');
const session = require('../../utils/session');
const format = require('../../utils/format');
const { definePage, confirm, toast } = require('../../utils/page');

definePage({
  teacherOnly: true,
  data: { records: [], visibleRecords: [], total: 0, checkedInCount: 0, statusIndex: 0, statusNames: ['全部老师', '已签到', '未签到'], isToday: false },
  async loadData() {
    const admin = session.isAdmin();
    const isToday = this.data.date === format.today();
    const [attendance, campuses] = await Promise.all([api.teacherAttendance(this.data.date), api.campuses()]);
    const records = attendance.map(row => Object.assign({}, row, {
      checkInAddress: (campuses.find(c => c.id === row.campusId) || {}).checkInAddress || '未设置签到地点，请联系校区管理员',
      checkInRadius: (campuses.find(c => c.id === row.campusId) || {}).checkInRadius || 0,
      key: row.teacherId + '-' + row.campusId,
      statusText: format.label(row.status),
      checkInText: row.checkInTime ? format.time(row.checkInTime) : '尚未签到',
      canCheckIn: !admin && isToday && !row.checkInTime
    }));
    const status = ['', 'CHECKED_IN', 'NOT_CHECKED_IN'][this.data.statusIndex];
    return { admin, records, isToday, visibleRecords: records.filter(row => !status || row.status === status), total: records.length, checkedInCount: records.filter(row => row.checkInTime).length };
  },
  changeStatus(event) { this.setData({ statusIndex: Number(event.detail.value) }); this.refresh(); },
  async checkIn(event) {
    const record = this.data.records.find(row => row.key === event.currentTarget.dataset.key);
    if (!record || !record.canCheckIn || this.data.busy) return;
    if (!record.checkInRadius) { toast('请先联系校区管理员设置签到地点'); return; }
    const context = session.context();
    if (!await confirm('确认本人签到', '在“' + record.campusName + '”登记今天的到校时间。地点：' + record.checkInAddress + '，允许范围' + record.checkInRadius + '米。确认后获取当前位置。')) return;
    if (!session.isCurrentContext(context)) return;
    await this.act(async () => { const location = await currentLocation(); if (!this._unloaded) return api.teacherCheckIn(record.campusId, location); }, '签到成功');
  }
});
