// Read-only geometry checks for the real WXML/WXSS preview, including style v2 defaults.
module.exports = function probeLayout() {
  const roots = [document], nodes = [];
  for (let i = 0; i < roots.length; i++) for (const node of roots[i].querySelectorAll('*')) {
    nodes.push(node); if (node.shadowRoot) roots.push(node.shadowRoot);
  }
  const sheets = nodes.filter(n => n.matches('.select-sheet') && n.getBoundingClientRect().width);
  const failures = [], measures = [];
  for (const sheet of sheets) {
    const close = sheet.querySelector('.select-close'), options = sheet.querySelector('.select-options');
    const closeBox = close.getBoundingClientRect(), optionsBox = options.getBoundingClientRect();
    const rows = [...sheet.querySelectorAll('.select-option')].map(n => n.getBoundingClientRect());
    measures.push({ closeWidth: closeBox.width, closeHeight: closeBox.height, optionsWidth: optionsBox.width, rowWidths: rows.map(r => r.width) });
    if (closeBox.width > 48 || closeBox.height > 48) failures.push('关闭按钮被拉宽，应保持不超过48px的独立触控区');
    if (rows.some(r => r.width < optionsBox.width - 2 || r.left < optionsBox.left - 1 || r.right > optionsBox.right + 1)) failures.push('选择项没有对齐列表左右边缘，出现多余留白或溢出');
  }
  if (!sheets.length) failures.push('未打开选择弹层，布局检查没有覆盖到目标');
  const triggers = nodes.filter(n => n.matches('.select-caption'));
  if (triggers.some(n => !n.textContent.trim() && ![...n.querySelectorAll('slot')].flatMap(s => s.assignedNodes({ flatten: true })).some(item => item.textContent.trim()))) failures.push('未传插槽的下拉框显示空白，缺少当前选中值');
  return { passed: failures.length === 0, failures, measures };
};
