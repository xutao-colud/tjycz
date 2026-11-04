const api = require('./api');
const format = require('./format');
async function selection(page, multiple) {
  const students = (await api.students({})).map(format.decorate);
  const allowed = new Set(students.map(item => Number(item.id)));
  const saved = page.data.studentIds.length ? page.data.studentIds : getApp().globalData.selectedStudentIds;
  let studentIds = (saved || []).map(Number).filter(id => allowed.has(id));
  if (!multiple) studentIds = studentIds.length ? studentIds.slice(0, 1) : students.length ? [students[0].id] : [];
  return { students, studentIds };
}
module.exports = { selection };
