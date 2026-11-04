const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
function moveRegion(region, dx, dy, resize) {
  if (resize) return Object.assign({}, region, { width: clamp(region.width + dx, .025, 1 - region.x), height: clamp(region.height + dy, .025, 1 - region.y) });
  return Object.assign({}, region, { x: clamp(region.x + dx, 0, 1 - region.width), y: clamp(region.y + dy, 0, 1 - region.height) });
}
function decorate(job, selected, active) {
  const chosen = selected || [];
  const questions = (job.questions || []).map((q, index) => Object.assign({}, q, {
    caption: /^\d/.test(q.displayNumber) ? '第' + q.displayNumber + '题' : q.displayNumber,
    key: q.id || 'new-' + index, selected: chosen.includes(index) && !q.importedMistakeId, active: index === active,
    regions: q.regions.map((r, regionIndex) => Object.assign({}, r, { regionIndex, style: 'left:' + r.x * 100 + '%;top:' + r.y * 100 + '%;width:' + r.width * 100 + '%;height:' + r.height * 100 + '%;' }))
  }));
  return { questions, activeQuestion: questions[active] || null, selectedCount: questions.filter(q => q.selected).length };
}
function payload(questions) {
  return questions.map(q => Object.assign(q.id ? { id: q.id } : {}, {
    displayNumber: q.displayNumber, groupName: q.groupName || '', questionText: q.questionText || '',
    regions: q.regions.map(r => ({ x: r.x, y: r.y, width: r.width, height: r.height }))
  }));
}
module.exports = { moveRegion, decorate, payload };
