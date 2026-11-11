const api = require('../../utils/api');
const session = require('../../utils/session');
const format = require('../../utils/format');
Component({
  properties: { studentId: Number, endDate: String },
  data: { days: 7, points: [], loading: false, error: '', hasData: false, details: false },
  observers: { 'studentId,endDate'() { this.refresh(); } },
  lifetimes: {
    attached() { this._gone = false; this.refresh(); },
    detached() { this._gone = true; this._generation = (this._generation || 0) + 1; }
  },
  methods: {
    async refresh() {
      const generation = this._generation = (this._generation || 0) + 1;
      if (!this.data.studentId || this._gone) return;
      this.setData({ loading: true, error: '', points: [], hasData: false });
      const context = session.context();
      try {
        const result = await api.homeworkTrend({ studentId: this.data.studentId, days: this.data.days, date: this.data.endDate || format.today() });
        if (this._gone || generation !== this._generation || !session.isCurrentContext(context)) return;
        this.setData({ loading: false, points: result.points, hasData: result.points.some(p => p.accuracy !== null) }, () => this.draw());
      } catch (error) {
        if (!this._gone && generation === this._generation && session.isCurrentContext(context)) this.setData({ loading: false, error: error.message });
      }
    },
    period(event) {
      const days = Number(event.currentTarget.dataset.days);
      if (![7, 30].includes(days) || days === this.data.days) return;
      this.setData({ days }, () => this.refresh());
    },
    toggleDetails() { this.setData({ details: !this.data.details }); },
    draw() {
      if (!this.data.hasData || this._gone) return;
      const generation = this._generation;
      wx.createSelectorQuery().in(this).select('.trend-canvas').boundingClientRect(rect => {
        if (!rect || !rect.width || this._gone || generation !== this._generation) return;
        const ctx = wx.createCanvasContext('homework-trend', this), points = this.data.points, width = rect.width;
        const x = index => 34 + index * (width - 46) / Math.max(points.length - 1, 1), y = value => 12 + (100 - value) * 1.48;
        ctx.clearRect(0, 0, width, 200); ctx.setFontSize(10);
        [0, 50, 100].forEach(value => {
          ctx.setStrokeStyle('#e7e8ee'); ctx.setLineWidth(1); ctx.beginPath(); ctx.moveTo(32, y(value)); ctx.lineTo(width - 8, y(value)); ctx.stroke();
          ctx.setFillStyle('#737783'); ctx.fillText(value + '%', 0, y(value) + 4);
        });
        [['accuracy', '#0071e3'], ['errorRate', '#e17a45']].forEach(([key, color]) => {
          ctx.setStrokeStyle(color); ctx.setLineWidth(2); ctx.beginPath(); let connected = false;
          points.forEach((point, index) => {
            if (point[key] === null) { connected = false; return; }
            if (connected) ctx.lineTo(x(index), y(point[key])); else ctx.moveTo(x(index), y(point[key]));
            connected = true;
          });
          ctx.stroke(); ctx.setFillStyle(color);
          points.forEach((point, index) => { if (point[key] !== null) { ctx.beginPath(); ctx.arc(x(index), y(point[key]), 2.5, 0, Math.PI * 2); ctx.fill(); } });
        });
        ctx.setFillStyle('#737783'); ctx.setTextAlign('center');
        points.forEach((point, index) => { if (points.length === 7 || index === 0 || index === points.length - 1 || index % 7 === 0 && points.length - index > 3) ctx.fillText(point.date.slice(5), Math.min(width - 18, x(index)), 185); });
        ctx.draw();
      }).exec();
    }
  }
});
