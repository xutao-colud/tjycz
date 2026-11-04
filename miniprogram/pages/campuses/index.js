const api = require('../../utils/api');
const session = require('../../utils/session');
const { definePage, confirm } = require('../../utils/page');
const { currentLocation } = require('../../utils/location');

definePage({
  adminOnly: true,
  data: { campuses: [], principals: [], superAdmin: false, editingId: 0, editingName: '', choices: [], locationCampusId: 0, locationAddress: '', locationLatitude: '', locationLongitude: '', radiusIndex: 2, radiusOptions: [50,100,200,300,500,1000,2000,3000], radiusNames: ['50米','100米','200米','300米','500米','1000米','2000米','3000米'] },
  async loadData() {
    const superAdmin = session.current().user.role === 'SUPER_ADMIN';
    const [campuses, members] = await Promise.all([api.campuses(), superAdmin ? api.users() : Promise.resolve([])]);
    return { campuses, superAdmin, principals: members.filter(row => row.role === 'ADMIN').map(row => Object.assign({}, row, { campusText: (row.campusNames || []).join('、') || '未授权校区' })) };
  },
  edit(event) {
    if (!this.data.superAdmin || this.data.busy) return;
    const principal = this.data.principals.find(row => Number(row.id) === Number(event.currentTarget.dataset.id));
    if (!principal) return;
    this.setData({ editingId: principal.id, editingName: principal.name, choices: this.data.campuses.map(row => ({ id: row.id, name: row.name, checked: principal.campusIds.some(id => Number(id) === Number(row.id)) })) });
  },
  toggleCampus(event) {
    if (this.data.busy) return;
    const id = Number(event.currentTarget.dataset.id);
    this.setData({ choices: this.data.choices.map(row => Object.assign({}, row, { checked: Number(row.id) === id ? !row.checked : row.checked })) });
  },
  cancel() { if (!this.data.busy) this.setData({ editingId: 0, editingName: '', choices: [] }); },
  async save() {
    if (!this.data.superAdmin || !this.data.editingId || this.data.busy) return;
    const context = session.context();
    const id = this.data.editingId;
    const selected = this.data.choices.filter(row => row.checked);
    if (!await confirm('保存校区权限', selected.length ? this.data.editingName + '将可管理：' + selected.map(row => row.name).join('、') : '将取消' + this.data.editingName + '的全部校区权限。')) return;
    if (!session.isCurrentContext(context)) return;
    await this.act(async () => {
      await api.updateAccess(id, selected.map(row => row.id));
      this.setData({ editingId: 0, editingName: '', choices: [] });
    }, '校区权限已更新');
  },
  viewCampus(event) {
    if (this.data.busy) return;
    const id = Number(event.currentTarget.dataset.id);
    if (!this.data.campuses.some(row => Number(row.id) === id)) return;
    getApp().setCampus(id);
    wx.navigateTo({ url: '/pages/' + session.homePage() + '/index' });
  },
  editLocation(event) {
    const campus = this.data.campuses.find(row => Number(row.id) === Number(event.currentTarget.dataset.id));
    if (!campus || this.data.busy) return;
    const radiusOptions = Array.from(new Set([50,100,200,300,500,1000,2000,3000,campus.checkInRadius || 200])).sort((a,b) => a-b);
    this.setData({ radiusOptions, radiusNames: radiusOptions.map(value => value + '米'), locationCampusId: campus.id, locationAddress: campus.checkInAddress || campus.address || '', locationLatitude: campus.checkInLatitude == null ? '' : campus.checkInLatitude, locationLongitude: campus.checkInLongitude == null ? '' : campus.checkInLongitude, radiusIndex: radiusOptions.indexOf(campus.checkInRadius || 200) });
  },
  changeRadius(event) { this.setData({ radiusIndex: Number(event.detail.value) }); },
  cancelLocation() { if (!this.data.busy) this.setData({ locationCampusId: 0 }); },
  async locate() {
    await this.act(async () => {
      const point = await currentLocation();
      if (!this._unloaded) this.setData({ locationLatitude: point.latitude, locationLongitude: point.longitude });
    }, '已获取中心点，请核对地址后保存', false);
  },
  async saveLocation() {
    if (!this.data.locationCampusId) return;
    await this.act(async () => {
      if (!String(this.data.locationAddress).trim() || String(this.data.locationLatitude).trim() === '' || String(this.data.locationLongitude).trim() === '') throw new Error('请填写签到地址与中心点');
      await api.updateCampusLocation(this.data.locationCampusId, { address: this.data.locationAddress, latitude: Number(this.data.locationLatitude), longitude: Number(this.data.locationLongitude), radiusMeters: this.data.radiusOptions[this.data.radiusIndex] });
      this.setData({ locationCampusId: 0 });
    }, '签到地点已保存');
  }
});
