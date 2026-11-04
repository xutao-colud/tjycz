const api = require('../../utils/api');
Component({
  properties: {
    students: { type: Array, value: [] },
    selected: { type: Array, value: [] },
    multiple: { type: Boolean, value: false },
    title: { type: String, value: '选择学生' }
  },
  data: {
    rows: [],
    expanded: false,
    count: 0,
    chosen: '',
    chosenPreview: [],
    remainingCount: 0,
    listHeight: 0
  },
  observers: {
    'students, selected': function(students, selected) {
      const available = students || [];
      const allowed = new Set(available.map(item => String(item.id)));
      const ids = (selected || []).filter(id => allowed.has(String(id)));
      const rows = available.map(item => Object.assign({}, item, {
        avatarUrl: item.avatar ? api.fileUrl(item.avatar) : '',
        checked: ids.some(id => String(id) === String(item.id)),
        initial: (item.name || '').slice(0, 1),
        detail: [item.className, item.grade].filter(Boolean).join(' · ')
      }));
      const chosenRows = rows.filter(item => item.checked);
      this.setData({
        rows,
        count: ids.length,
        chosen: chosenRows.map(item => item.name).join('、'),
        chosenPreview: chosenRows.slice(0, 2),
        remainingCount: Math.max(0, chosenRows.length - 2),
        listHeight: Math.min(rows.length * 112, 448)
      });
    }
  },
  methods: {
    togglePanel() {
      this.setData({ expanded: !this.data.expanded });
    },
    choose(event) {
      const student = this.data.students.find(item => String(item.id) === String(event.currentTarget.dataset.id));
      if (!student) return;
      const allowed = new Set(this.data.students.map(item => String(item.id)));
      let ids = this.data.selected.filter(id => allowed.has(String(id)));
      if (this.data.multiple) {
        ids = ids.some(id => String(id) === String(student.id))
          ? ids.filter(id => String(id) !== String(student.id))
          : ids.concat(student.id);
      } else {
        ids = [student.id];
        this.setData({ expanded: false });
      }
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
