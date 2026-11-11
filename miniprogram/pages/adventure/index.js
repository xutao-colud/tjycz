const api = require('../../utils/api');
const { definePage, toast } = require('../../utils/page');
const pets = [{ key: 'CAT', name: '小猫', image: '/assets/adventure/cat-1.svg' }, { key: 'DOG', name: '小狗', image: '/assets/adventure/dog-1.svg' }, { key: 'DINO', name: '小恐龙', image: '/assets/adventure/dino-1.svg' }];
const chapters = ['启程营地', '阅读林地', '探索山谷', '勇气山坡'];
function decorate(value) {
  return Object.assign({}, value, {
    petImage: '/assets/adventure/' + value.pet.toLowerCase() + '-' + value.stage + '.svg',
    stageName: ['初见伙伴', '同行伙伴', '探险伙伴'][value.stage - 1],
    chapterName: chapters[(value.chapter - 1) % chapters.length],
    percent: Math.min(100, Math.round(value.levelXp / value.nextLevelXp * 100)),
    remaining: value.nextLevelXp - value.levelXp,
    doneCount: value.tasks.filter(item => item.done).length,
    sealsList: [0, 1, 2].map(i => ({ id: i, earned: i < value.seals })),
    recent: value.recent.map(item => Object.assign({}, item, { title: { LEARNING: '学习挑战', FITNESS: '体能活动', EFFORT: '努力鼓励' }[item.category] }))
  });
}
definePage({
  data: { studentId: '', students: [], childNames: [], childIndex: 0, adventure: null, pets, customizeOpen: false, nameDraft: '', rulesOpen: false, journalOpen: false, greeting: '' },
  onLoad(query) { this.setData({ studentId: query.studentId || '' }); },
  async loadData() {
    const students = await api.students();
    const student = this.data.studentId ? students.find(row => String(row.id) === String(this.data.studentId)) : students[0];
    if (!student) return { students, adventure: null, childNames: students.map(row => row.name) };
    const value = await api.request('GET', '/growth/adventure', { studentId: student.id });
    return { students, studentId: student.id, childNames: students.map(row => row.name), childIndex: students.indexOf(student), adventure: decorate(value), nameDraft: value.petName };
  },
  chooseChild(event) {
    if (this.data.busy) return;
    const student = this.data.students[Number(event.detail.value)];
    if (!student) return;
    this.setData({ studentId: student.id, adventure: null, customizeOpen: false, greeting: '' });
    return this.refresh();
  },
  async savePreference(patch) {
    if (this.data.teacher || !this.data.adventure || this.data.loading) return;
    const id = this.data.studentId;
    return this.act(() => api.request('PATCH', '/growth/adventure?studentId=' + encodeURIComponent(id), patch), '已保存，成长进度继续保留');
  },
  mode(event) { const mode = event.currentTarget.dataset.mode; if (['ADVENTURE', 'PET'].includes(mode) && this.data.adventure && mode !== this.data.adventure.mode) return this.savePreference({ mode }); },
  pet(event) { const pet = event.currentTarget.dataset.pet; if (pets.some(item => item.key === pet)) return this.savePreference({ pet }); },
  rename() { const name = this.data.nameDraft.trim(); if (!name || name.length > 12) { toast('名字请输入1至12个字'); return; } return this.savePreference({ petName: name }); },
  customize() { this.setData({ customizeOpen: !this.data.customizeOpen }); },
  rules() { this.setData({ rulesOpen: !this.data.rulesOpen }); },
  journal() { this.setData({ journalOpen: !this.data.journalOpen }); },
  greet() { this.setData({ greeting: '今天走一小步，也值得留下一个印章' }); },
  task(event) {
    const page = { LEARNING: 'homework', FITNESS: 'fitness', EFFORT: 'points' }[event.currentTarget.dataset.key];
    if (page && this.data.adventure) wx.navigateTo({ url: '/pages/' + page + '/index?studentId=' + this.data.studentId + '&date=' + this.data.adventure.date });
  },
  rewards() { if (this.data.adventure) wx.navigateTo({ url: '/pages/rewards/index?studentId=' + this.data.studentId }); }
});
