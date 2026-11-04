const { today } = require('../../utils/format');
const calendar = require('../../utils/calendar');
const ANIMATION_MS = 200;

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    value: { type: String, value: '' },
    label: { type: String, value: '选择筛选日期' }
  },
  data: {
    open: false,
    closing: false,
    mode: 'days',
    draft: '',
    draftText: '',
    todayValue: '',
    viewYear: 0,
    viewMonth: 1,
    cursorDay: 1,
    yearStart: 1,
    yearEnd: 12,
    weekdays: calendar.WEEKDAYS,
    cells: [],
    bodyHeight: 0,
    months: [],
    years: [],
    previousDisabled: false,
    nextDisabled: false,
    previousLabel: '上个月',
    nextLabel: '下个月'
  },
  lifetimes: {
    detached() {
      this.dismissImmediately();
    }
  },
  pageLifetimes: {
    hide() {
      this.dismissImmediately();
    }
  },
  methods: {
    open() {
      if (this.data.open) return;
      clearTimeout(this._closeTimer);
      const todayValue = today();
      const draft = calendar.normalizeDate(this.data.value, todayValue);
      const parts = calendar.parseDate(draft);
      this._initialValue = draft;
      this.setData({
        open: true, closing: false, mode: 'days', draft, todayValue,
        draftText: calendar.describeDate(draft),
        viewYear: parts.year, viewMonth: parts.month, cursorDay: parts.day,
        yearStart: calendar.yearPageStart(parts.year)
      });
      this.refresh();
    },
    close() {
      if (!this.data.open || this.data.closing) return;
      this.setData({ closing: true });
      this._closeTimer = setTimeout(() => {
        this._closeTimer = null;
        this.setData({ open: false, closing: false, draft: '', draftText: '', mode: 'days' });
      }, ANIMATION_MS);
    },
    dismissImmediately() {
      clearTimeout(this._closeTimer);
      this._closeTimer = null;
      this.setData({ open: false, closing: false, draft: '', draftText: '', mode: 'days' });
    },
    swallow() {},
    refresh() {
      const { viewYear, viewMonth, draft, todayValue, mode, yearStart } = this.data;
      const yearsMode = mode === 'years';
      const monthsMode = mode === 'months';
      const yearEnd = Math.min(yearStart + calendar.YEAR_PAGE_SIZE - 1, calendar.MAX_YEAR);
      const cells = calendar.buildMonth(viewYear, viewMonth, draft, todayValue);
      const years = calendar.buildYears(yearStart, viewYear);
      const optionRows = Math.ceil((yearsMode ? years.length : 12) / 3);
      this.setData({
        cells,
        bodyHeight: mode === 'days' ? 56 + cells.length / 7 * 82 : 126 + optionRows * 82 + (optionRows - 1) * 18,
        months: Array.from({ length: 12 }, (_, index) => ({ value: index + 1, selected: index + 1 === viewMonth })),
        years,
        yearEnd,
        previousDisabled: yearsMode ? yearStart === calendar.MIN_YEAR : viewYear === calendar.MIN_YEAR && (monthsMode || viewMonth === 1),
        nextDisabled: yearsMode ? yearEnd === calendar.MAX_YEAR : viewYear === calendar.MAX_YEAR && (monthsMode || viewMonth === 12),
        previousLabel: yearsMode ? '上一组年份' : monthsMode ? '上一年' : '上个月',
        nextLabel: yearsMode ? '下一组年份' : monthsMode ? '下一年' : '下个月'
      });
    },
    previous() {
      this.navigate(-1);
    },
    next() {
      this.navigate(1);
    },
    navigate(direction) {
      if (!this.data.open || this.data.closing) return;
      if (direction < 0 ? this.data.previousDisabled : this.data.nextDisabled) return;
      const { mode, viewYear, viewMonth, cursorDay, yearStart } = this.data;
      if (mode === 'years') {
        this.setData({ yearStart: yearStart + direction * calendar.YEAR_PAGE_SIZE });
      } else {
        const parts = calendar.shiftMonth({ year: viewYear, month: viewMonth, day: cursorDay }, direction * (mode === 'months' ? 12 : 1));
        this.setData({ viewYear: parts.year, viewMonth: parts.month, cursorDay: parts.day });
      }
      this.refresh();
    },
    showYears() {
      if (this.data.closing) return;
      this.setData({ mode: this.data.mode === 'years' ? 'days' : 'years', yearStart: calendar.yearPageStart(this.data.viewYear) });
      this.refresh();
    },
    showMonths() {
      if (this.data.closing) return;
      this.setData({ mode: this.data.mode === 'months' ? 'days' : 'months' });
      this.refresh();
    },
    chooseYear(event) {
      if (this.data.closing) return;
      const year = Number(event.currentTarget.dataset.year);
      if (!Number.isInteger(year) || year < calendar.MIN_YEAR || year > calendar.MAX_YEAR) return;
      this.setData({ viewYear: year, cursorDay: Math.min(this.data.cursorDay, calendar.daysInMonth(year, this.data.viewMonth)), mode: 'months' });
      this.refresh();
    },
    chooseMonth(event) {
      if (this.data.closing) return;
      const month = Number(event.currentTarget.dataset.month);
      if (!Number.isInteger(month) || month < 1 || month > 12) return;
      this.setData({ viewMonth: month, cursorDay: Math.min(this.data.cursorDay, calendar.daysInMonth(this.data.viewYear, month)), mode: 'days' });
      this.refresh();
    },
    chooseDay(event) {
      if (!this.data.open || this.data.closing) return;
      const value = event.currentTarget.dataset.value;
      const parts = calendar.parseDate(value);
      if (!parts || parts.year !== this.data.viewYear || parts.month !== this.data.viewMonth) return;
      this.setData({ draft: value, draftText: calendar.describeDate(value), cursorDay: parts.day });
      this.refresh();
    },
    goToday() {
      if (!this.data.open || this.data.closing) return;
      const value = today();
      const parts = calendar.parseDate(value);
      this.setData({
        draft: value, draftText: calendar.describeDate(value), todayValue: value,
        viewYear: parts.year, viewMonth: parts.month, cursorDay: parts.day, mode: 'days'
      });
      this.refresh();
    },
    confirm() {
      if (!this.data.open || this.data.closing || !calendar.parseDate(this.data.draft)) return;
      const value = this.data.draft;
      const previousValue = calendar.normalizeDate(this.data.value, this._initialValue);
      this.close();
      if (value !== previousValue) this.triggerEvent('change', { value });
    }
  }
});
