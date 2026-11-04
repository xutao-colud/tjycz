const api = require('../../utils/api');
const format = require('../../utils/format');
const { definePage, toast } = require('../../utils/page');
definePage({
  data: { students: [], classes: [], classNames: ['全部授权班级'], classIndex: 0, keyword: '' },
  async loadData() {
    const classes = this.data.teacher ? await api.classes() : [];
    const classId = this.data.classIndex ? (classes[this.data.classIndex - 1] || {}).id : '';
    const students = (await api.students({ classId, keyword: this.data.keyword.trim() })).map(format.decorate);
    const allowed = students.map(item => Number(item.id));
    const studentIds = (getApp().globalData.selectedStudentIds || []).filter(id => allowed.includes(Number(id)));
    return { students, classes, classNames: ['全部授权班级'].concat(classes.map(item => item.name)), studentIds };
  },
  changeClass(event) { this.setData({ classIndex: Number(event.detail.value) }); this.refresh(); },
  search() { this.refresh(); },
  pickStudents(event) { this.setData({ studentIds: event.detail.ids }); },
  useSelection(event) {
    if (!this.data.studentIds.length) { toast('请先选择学生'); return; }
    getApp().globalData.selectedStudentIds = this.data.studentIds;
    wx.navigateTo({ url: '/pages/' + event.currentTarget.dataset.page + '/index' });
  }
});
