Component({
  properties: { students: { type: Array, value: [] }, selected: { type: Array, value: [] }, multiple: { type: Boolean, value: false }, title: { type: String, value: '选择学生' } },
  data: { rows: [], expanded: false, count: 0, chosen: '' },
  observers: { 'students, selected': function(students, selected) {
    const allowed = new Set((students || []).map(item => String(item.id)));
    const ids = (selected || []).filter(id => allowed.has(String(id)));
    this.setData({ rows: (students || []).map(item => Object.assign({}, item, { checked: ids.some(id => String(id) === String(item.id)) })), count: ids.length, chosen: (students || []).filter(item => ids.some(id => String(id) === String(item.id))).map(item => item.name).join('、') });
  } },
  methods: {
    togglePanel() { this.setData({ expanded: !this.data.expanded }); },
    choose(event) {
      const id = Number(event.currentTarget.dataset.id);
      let ids = this.data.selected.slice();
      if (this.data.multiple) ids = ids.includes(id) ? ids.filter(value => value !== id) : ids.concat(id);
      else ids = [id];
      getApp().globalData.selectedStudentIds = ids;
      this.triggerEvent('change', { ids });
    },
    all() {
      const ids = this.data.count === this.data.students.length ? [] : this.data.students.map(item => item.id);
      getApp().globalData.selectedStudentIds = ids;
      this.triggerEvent('change', { ids });
    }
  }
});
