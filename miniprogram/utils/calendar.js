const MIN_YEAR = 1;
const MAX_YEAR = 9999;
const YEAR_PAGE_SIZE = 12;
const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function daysInMonth(year, month) {
  if (!Number.isInteger(year) || year < MIN_YEAR || year > MAX_YEAR || !Number.isInteger(month) || month < 1 || month > 12) return 0;
  return [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
}

function validParts(parts) {
  return parts && Number.isInteger(parts.day) && parts.day > 0 && parts.day <= daysInMonth(parts.year, parts.month);
}

function parseDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const parts = { year: Number(value.slice(0, 4)), month: Number(value.slice(5, 7)), day: Number(value.slice(8, 10)) };
  return validParts(parts) ? parts : null;
}

function formatDate(parts) {
  if (!validParts(parts)) return '';
  return String(parts.year).padStart(4, '0') + '-' + String(parts.month).padStart(2, '0') + '-' + String(parts.day).padStart(2, '0');
}

function normalizeDate(value, fallback) {
  return formatDate(parseDate(value) || parseDate(fallback));
}

function shiftMonth(parts, offset) {
  if (!validParts(parts) || !Number.isInteger(offset)) return null;
  const index = Math.max(0, Math.min(MAX_YEAR * 12 - 1, (parts.year - 1) * 12 + parts.month - 1 + offset));
  const year = Math.floor(index / 12) + 1;
  const month = index % 12 + 1;
  return { year, month, day: Math.min(parts.day, daysInMonth(year, month)) };
}

// Gregorian weekday arithmetic uses calendar fields only, with no UTC parsing or timezone conversion.
function mondayIndex(year, month, day) {
  const offsets = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
  const adjustedYear = year - (month < 3 ? 1 : 0);
  const sundayIndex = (adjustedYear + Math.floor(adjustedYear / 4) - Math.floor(adjustedYear / 100) + Math.floor(adjustedYear / 400) + offsets[month - 1] + day) % 7;
  return (sundayIndex + 6) % 7;
}

function describeDate(value) {
  const parts = parseDate(value);
  return parts ? parts.year + '年' + parts.month + '月' + parts.day + '日 ' + WEEKDAYS[mondayIndex(parts.year, parts.month, parts.day)] : '';
}

function buildMonth(year, month, selected, today) {
  const count = daysInMonth(year, month);
  if (!count) return [];
  const start = mondayIndex(year, month, 1);
  return Array.from({ length: Math.ceil((start + count) / 7) * 7 }, (_, index) => {
    const day = index - start + 1;
    if (day < 1 || day > count) return { key: 'blank-' + index, value: '', day: '', weekend: index % 7 > 4 };
    const value = formatDate({ year, month, day });
    return {
      key: value, value, day,
      weekend: index % 7 > 4,
      selected: value === selected,
      today: value === today,
      label: describeDate(value) + (value === today ? '，今天' : '') + (value === selected ? '，已选中' : '')
    };
  });
}

function yearPageStart(year) {
  return Math.floor((year - MIN_YEAR) / YEAR_PAGE_SIZE) * YEAR_PAGE_SIZE + MIN_YEAR;
}

function buildYears(start, selected) {
  return Array.from({ length: Math.min(YEAR_PAGE_SIZE, MAX_YEAR - start + 1) }, (_, index) => ({ value: start + index, selected: start + index === selected }));
}

module.exports = { MIN_YEAR, MAX_YEAR, YEAR_PAGE_SIZE, WEEKDAYS, isLeapYear, daysInMonth, parseDate, formatDate, normalizeDate, shiftMonth, describeDate, buildMonth, yearPageStart, buildYears };
