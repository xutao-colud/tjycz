Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    range: { type: Array, value: [] },
    rangeKey: { type: String, value: '' },
    value: { type: Number, value: 0 },
    label: { type: String, value: '选择选项' },
    plain: { type: Boolean, value: false },
    disabled: { type: Boolean, value: false }
  },
  data: { open: false, items: [] },
  observers: {
    'range, rangeKey, disabled'() {
      if (this.data.open) this.close();
    }
  },
  pageLifetimes: { hide() { this.close(); } },
  lifetimes: { detached() { this.close(); } },
  methods: {
    open() {
      if (this.data.disabled || !this.data.range.length || this.data.open) return;
      const { range, rangeKey } = this.data;
      this.setData({ open: true, items: range.map((item, index) => ({ index, label: rangeKey ? item[rangeKey] : item })) });
    },
    close() { if (this.data.open) this.setData({ open: false }); },
    swallow() {},
    choose(event) {
      const index = Number(event.currentTarget.dataset.index);
      if (!this.data.open || this.data.disabled || !Number.isInteger(index) || index < 0 || index >= this.data.range.length) return;
      this.close();
      if (index !== this.data.value) this.triggerEvent('change', { value: index });
    }
  }
});
