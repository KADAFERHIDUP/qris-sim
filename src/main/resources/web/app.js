'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const rupiah = (n) => 'Rp' + Number(n).toLocaleString('id-ID');
  const digits = (s) => String(s || '').replace(/\D/g, '');
  const clock = (iso) => new Date(iso).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

  const MEANING = {
    '00': 'Versi format payload',
    '01': 'Cara pakai: 11 statis, 12 dinamis',
    '26': 'Akun merchant (penerbit simulator)',
    '51': 'Data nasional QRIS (NMID)',
    '52': 'Kode kategori merchant (MCC)',
    '53': 'Mata uang, 360 = rupiah',
    '54': 'Nominal',
    '58': 'Kode negara',
    '59': 'Nama merchant',
    '60': 'Kota merchant',
    '61': 'Kode pos',
    '62': 'Data tambahan',
    '63': 'CRC16 dari semua karakter sebelumnya',
  };
  const SUB_MEANING = {
    '26': { '00': 'Penerbit (GUID)', '01': 'PAN merchant', '02': 'ID merchant', '03': 'Kriteria merchant' },
    '51': { '00': 'Penerbit (GUID)', '02': 'NMID', '03': 'Kriteria merchant' },
    '62': { '01': 'Nomor tagihan', '07': 'ID terminal' },
  };
  const STATUS_STAMP = { PAID: ['LUNAS', 'paid'], EXPIRED: ['KEDALUWARSA', 'expired'], CANCELLED: ['DIBATALKAN', 'cancelled'] };

  const WALLET_KEY = 'qrissim.wallet';
  const state = {
    walletId: null,
    walletToken: null,
    cancelToken: null,
    merchants: [],
    invoice: null,
    payload: null,
    timer: null,
    phone: { qr: null, payload: null, amount: null, pin: '', key: null, busy: false },
  };

  // ---------------------------------------------------------------- api
  async function api(path, options = {}) {
    const init = { method: options.method || 'GET', headers: {} };
    if (options.body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(options.body);
    }
    if (options.key) init.headers['Idempotency-Key'] = options.key;
    if (options.wallet && state.walletToken) init.headers['X-Wallet-Token'] = state.walletToken;
    if (options.cancel) init.headers['X-Cancel-Token'] = options.cancel;
    let res;
    try {
      res = await fetch(path, init);
    } catch (e) {
      const err = new Error('Server tidak bisa dihubungi. Cek koneksi lalu coba lagi.');
      err.code = 'NETWORK';
      throw err;
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error((data.error && data.error.message) || 'Permintaan gagal (HTTP ' + res.status + ')');
      err.code = data.error && data.error.code;
      throw err;
    }
    return data;
  }

  // ---------------------------------------------------------------- QR drawing
  function drawQr(target, payload) {
    const qr = window.qrcode(0, 'M');
    qr.addData(payload);
    qr.make();
    const n = qr.getModuleCount();
    const quiet = 3;
    const size = n + quiet * 2;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + size + ' ' + size);
    svg.setAttribute('shape-rendering', 'crispEdges');
    svg.setAttribute('aria-hidden', 'true');
    const bg = document.createElementNS(ns, 'rect');
    bg.setAttribute('width', size);
    bg.setAttribute('height', size);
    bg.setAttribute('fill', '#fff');
    svg.appendChild(bg);
    let d = '';
    for (let r = 0; r < n; r++) {
      for (let c = 0; c < n; c++) {
        if (qr.isDark(r, c)) d += 'M' + (c + quiet) + ',' + (r + quiet) + 'h1v1h-1z';
      }
    }
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('d', d);
    path.setAttribute('fill', '#13181E');
    svg.appendChild(path);
    target.replaceChildren(svg);
  }

  // ---------------------------------------------------------------- kasir
  function readAmount(input) {
    const v = Number(digits(input.value));
    return Number.isFinite(v) ? v : 0;
  }

  function formatAmountInput(input) {
    const v = digits(input.value).replace(/^0+/, '');
    input.value = v ? Number(v).toLocaleString('id-ID') : '';
  }

  async function loadMerchants() {
    state.merchants = await api('/api/merchants');
    const sel = $('merchant');
    sel.replaceChildren(...state.merchants.map((m) => {
      const o = document.createElement('option');
      o.value = m.id;
      o.textContent = m.name + ', ' + m.city;
      return o;
    }));
  }

  async function printBill(ev) {
    ev.preventDefault();
    const err = $('bill-error');
    err.textContent = '';
    const merchantId = $('merchant').value;
    const isStatic = $('static').checked;
    const amount = readAmount($('amount'));
    if (!isStatic && (amount < 1 || amount > 10000000)) {
      err.textContent = 'Isi nominal antara Rp1 dan Rp10.000.000.';
      $('amount').focus();
      return;
    }
    const btn = $('print');
    btn.disabled = true;
    try {
      if (isStatic) {
        const r = await api('/api/merchants/' + merchantId + '/static-qr');
        showReceipt({ id: null, merchant_id: merchantId, amount: null, note: '', payload: r.payload, status: 'PENDING' });
      } else {
        const note = $('note').value.trim();
        const inv = await api('/api/invoices', {
          method: 'POST',
          body: { merchant_id: merchantId, amount, note: note || null, ttl_seconds: Number($('ttl').value) },
        });
        state.cancelToken = inv.cancel_token;
        showReceipt(inv);
      }
    } catch (e) {
      err.textContent = e.message;
    } finally {
      btn.disabled = false;
    }
  }

  function showReceipt(inv) {
    state.invoice = inv.id ? inv : null;
    state.payload = inv.payload;
    const m = state.merchants.find((x) => x.id === inv.merchant_id) || { name: '—', city: '—' };
    $('r-merchant').textContent = m.name;
    $('r-city').textContent = m.city;
    $('r-id').textContent = inv.id || 'QR statis';
    $('r-note').textContent = inv.note || '—';
    $('r-amount').textContent = inv.amount ? rupiah(inv.amount) : 'Nominal diisi pembeli';
    drawQr($('r-qr'), inv.payload);
    const stamp = $('r-stamp');
    stamp.hidden = true;
    stamp.className = 'stamp';
    const paper = $('receipt');
    paper.classList.remove('closed', 'printing');
    paper.hidden = false;
    $('receipt-empty').hidden = true;
    void paper.offsetWidth;
    paper.classList.add('printing');
    $('cancel').hidden = !inv.id;
    $('scan').disabled = false;
    startTimer(inv);
    showAnatomy(inv.payload);
  }

  function startTimer(inv) {
    clearInterval(state.timer);
    const el = $('r-timer');
    el.classList.remove('warn');
    if (!inv.expires_at) {
      el.textContent = 'Berlaku selama merchant aktif';
      return;
    }
    const end = new Date(inv.expires_at).getTime();
    const tick = () => {
      const left = Math.max(0, Math.round((end - Date.now()) / 1000));
      const mm = String(Math.floor(left / 60)).padStart(2, '0');
      const ss = String(left % 60).padStart(2, '0');
      el.textContent = 'Berlaku ' + mm + ':' + ss;
      el.classList.toggle('warn', left <= 60);
      if (left === 0) clearInterval(state.timer);
    };
    tick();
    state.timer = setInterval(tick, 1000);
  }

  function closeReceipt(status) {
    const s = STATUS_STAMP[status];
    if (!s) return;
    clearInterval(state.timer);
    $('r-timer').textContent = '';
    const stamp = $('r-stamp');
    stamp.textContent = s[0];
    stamp.className = 'stamp ' + s[1];
    stamp.hidden = false;
    void stamp.offsetWidth;
    stamp.classList.add('in');
    $('receipt').classList.add('closed');
    $('cancel').hidden = true;
    $('scan').disabled = true;
  }

  async function cancelBill() {
    if (!state.invoice) return;
    try {
      const inv = await api('/api/invoices/' + state.invoice.id + '/cancel', { method: 'POST', cancel: state.cancelToken });
      closeReceipt(inv.status);
    } catch (e) {
      $('bill-error').textContent = e.message;
    }
  }

  async function copyPayload() {
    if (!state.payload) return;
    const btn = $('copy');
    try {
      await navigator.clipboard.writeText(state.payload);
      btn.textContent = 'Tersalin';
    } catch (e) {
      $('paste').value = state.payload;
      btn.textContent = 'Disalin ke HP';
    }
    setTimeout(() => { btn.textContent = 'Salin payload'; }, 1600);
  }

  // ---------------------------------------------------------------- anatomy
  async function showAnatomy(payload) {
    let d;
    try {
      d = await api('/api/qris/decode', { method: 'POST', body: { payload } });
    } catch (e) {
      return;
    }
    $('raw').hidden = false;
    $('raw-code').textContent = payload;
    const rows = [];
    // JSON objects put number-like keys first, so restore the payload order by tag
    const byTag = (o) => Object.keys(o).sort().map((k) => [k, o[k]]);
    for (const [tag, value] of byTag(d.fields)) {
      rows.push(row(tag, value, MEANING[tag] || '', tag === '63' ? 'crc' : ''));
      const sub = d.templates[tag];
      if (sub) {
        for (const [st, sv] of byTag(sub)) {
          rows.push(row(tag + '.' + st, sv, (SUB_MEANING[tag] || {})[st] || '', 'nested'));
        }
      }
    }
    $('tlv').tBodies[0].replaceChildren(...rows);
  }

  function row(tag, value, meaning, cls) {
    const tr = document.createElement('tr');
    if (cls) tr.className = cls;
    for (const text of [tag, String(value.length).padStart(2, '0'), value, meaning]) {
      const td = document.createElement('td');
      td.textContent = text;
      tr.appendChild(td);
    }
    return tr;
  }

  // ---------------------------------------------------------------- phone
  // Every visitor gets a wallet of their own, so one person's wrong PIN never locks anyone else.
  // The wallet's secret token stays in this browser only; the server keeps just its hash.
  function remember(id, token) {
    try { localStorage.setItem(WALLET_KEY, JSON.stringify({ id, token })); } catch (e) { /* private mode: this tab only */ }
  }

  function recall() {
    try {
      const v = JSON.parse(localStorage.getItem(WALLET_KEY) || 'null');
      return v && typeof v.id === 'string' && typeof v.token === 'string' ? v : null;
    } catch (e) {
      return null;
    }
  }

  async function ensureWallet() {
    const saved = recall();
    if (saved) {
      state.walletId = saved.id;
      state.walletToken = saved.token;
      try {
        await api('/api/wallets/' + encodeURIComponent(saved.id), { wallet: true });
        return;
      } catch (e) {
        if (e.code !== 'NOT_FOUND') throw e;
      }
    }
    const w = await api('/api/wallets', { method: 'POST' });
    state.walletId = w.id;
    state.walletToken = w.token;
    remember(w.id, w.token);
  }

  async function resetWallet() {
    const btn = $('w-reset');
    btn.disabled = true;
    try {
      paintWallet(await api('/api/wallets/' + state.walletId + '/reset', { method: 'POST', wallet: true }));
    } catch (e) {
      $('home-error').textContent = e.message;
    } finally {
      btn.disabled = false;
    }
  }

  function paintWallet(w) {
    $('w-owner').textContent = w.owner;
    $('w-balance').textContent = rupiah(w.balance);
    $('w-lock').hidden = !w.locked;
  }

  async function loadWallet() {
    if (!state.walletId) return;
    paintWallet(await api('/api/wallets/' + state.walletId, { wallet: true }));
  }

  function showView(id) {
    for (const v of ['v-home', 'v-confirm', 'v-pin', 'v-done']) $(v).hidden = v !== id;
    const first = $(id).querySelector('button:not([disabled]), input, textarea');
    if (first && id !== 'v-home') first.focus();
  }

  function resetPhone() {
    state.phone = { qr: null, payload: null, amount: null, pin: '', key: null, busy: false };
    $('home-error').textContent = '';
    showView('v-home');
  }

  async function readQr(payload) {
    const err = $('home-error');
    err.textContent = '';
    if (!payload || !payload.trim()) {
      err.textContent = 'Tempel payload QRIS dulu.';
      return;
    }
    try {
      const d = await api('/api/qris/decode', { method: 'POST', body: { payload: payload.trim() } });
      if (!d.payable) {
        err.textContent = d.reason;
        return;
      }
      state.phone.qr = d;
      state.phone.payload = payload.trim();
      $('c-merchant').textContent = d.merchant_name;
      $('c-city').textContent = d.city;
      $('c-nmid').textContent = d.nmid || '—';
      $('c-bill').textContent = d.bill_number || 'QR statis';
      $('confirm-error').textContent = '';
      $('c-amount-fixed').hidden = !d.dynamic;
      $('c-amount-edit').hidden = d.dynamic;
      $('c-amount-fixed').textContent = d.amount ? rupiah(d.amount) : '';
      $('c-amount-input').value = '';
      showView('v-confirm');
    } catch (e) {
      err.textContent = e.message;
    }
  }

  function toPin() {
    const d = state.phone.qr;
    let amount = d.amount;
    if (!d.dynamic) {
      amount = readAmount($('c-amount-input'));
      if (amount < 1 || amount > 10000000) {
        $('confirm-error').textContent = 'Isi nominal antara Rp1 dan Rp10.000.000.';
        $('c-amount-input').focus();
        return;
      }
    }
    state.phone.amount = amount;
    state.phone.pin = '';
    state.phone.key = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();
    $('p-amount').textContent = rupiah(amount) + ' ke ' + d.merchant_name;
    $('pin-error').textContent = '';
    paintPin();
    showView('v-pin');
  }

  function paintPin() {
    const n = state.phone.pin.length;
    [...$('pin-dots').children].forEach((dot, i) => dot.classList.toggle('on', i < n));
    $('pin-count').textContent = n + ' dari 6 angka';
  }

  function pinKey(k) {
    if (state.phone.busy) return;
    if (k === 'del') {
      state.phone.pin = state.phone.pin.slice(0, -1);
    } else if (state.phone.pin.length < 6) {
      state.phone.pin += k;
    }
    paintPin();
    if (state.phone.pin.length === 6) submitPayment();
  }

  async function submitPayment() {
    const p = state.phone;
    p.busy = true;
    $('pin-error').textContent = 'Memproses…';
    try {
      const body = { payload: p.payload, wallet_id: state.walletId, pin: p.pin };
      if (!p.qr.dynamic) body.amount = p.amount;
      const pay = await api('/api/payments', { method: 'POST', body, key: p.key, wallet: true });
      $('d-amount').textContent = rupiah(pay.amount);
      $('d-merchant').textContent = pay.merchant_name;
      $('d-rrn').textContent = pay.rrn;
      $('d-time').textContent = clock(pay.paid_at);
      showView('v-done');
      loadWallet();
    } catch (e) {
      p.pin = '';
      paintPin();
      $('pin-error').textContent = e.message;
      if (e.code === 'WRONG_PIN' || e.code === 'PIN_LOCKED') {
        // a refused PIN never charged anything, so the next try is a new request
        p.key = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();
        const dots = $('pin-dots');
        dots.classList.remove('shake');
        void dots.offsetWidth;
        dots.classList.add('shake');
        loadWallet();
      }
    } finally {
      p.busy = false;
    }
  }

  // ---------------------------------------------------------------- webhook log
  function logItem(cols, sub, fresh) {
    const li = document.createElement('li');
    if (fresh) li.className = 'fresh';
    for (const c of cols) li.appendChild(c);
    if (sub) {
      const s = document.createElement('span');
      s.className = 'sub';
      s.textContent = sub;
      li.appendChild(s);
    }
    return li;
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    e.textContent = text;
    return e;
  }

  function sentRow(a, fresh) {
    const t = el('time', '', clock(a.at));
    t.dateTime = a.at;
    const what = el('span', 'what', a.event_type + (a.invoice_id ? ' ' + a.invoice_id : ''));
    let chip;
    if (a.delivered) chip = el('span', 'chip ok', a.status_code + ' terkirim');
    else if (a.next_retry_at) chip = el('span', 'chip wait', (a.status_code || 'tanpa jawaban') + ' ulang');
    else chip = el('span', 'chip bad', 'menyerah');
    const sub = 'Percobaan ' + a.attempt + ', ' + a.duration_ms + ' ms'
      + (a.error ? '. ' + a.error : '')
      + (a.next_retry_at ? '. Ulang pukul ' + clock(a.next_retry_at) : '');
    return logItem([t, what, chip], sub, fresh);
  }

  function recvRow(r) {
    const t = el('time', '', clock(r.at));
    const what = el('span', 'what', r.event_type + ' ' + r.event_id.slice(0, 12));
    const good = r.verdict === 'VALID' && r.answered_with === 200;
    const label = r.verdict !== 'VALID' ? 'tanda tangan ' + r.verdict.toLowerCase()
      : r.answered_with !== 200 ? 'jawab ' + r.answered_with : r.duplicate ? 'duplikat, diabaikan' : 'tanda tangan sah';
    const chip = el('span', 'chip ' + (good ? 'ok' : r.answered_with === 503 ? 'wait' : 'bad'), label);
    return logItem([t, what, chip], 'Percobaan ' + r.attempt, false);
  }

  function fill(list, items, render, empty) {
    if (!items.length) {
      list.replaceChildren(el('li', 'log-empty', empty));
      return;
    }
    list.replaceChildren(...items.map((x) => render(x)));
  }

  async function loadLogs() {
    const [sent, demo] = await Promise.all([api('/api/webhooks/deliveries'), api('/demo-merchant')]);
    fill($('log-sent'), sent, (a) => sentRow(a, false), 'Belum ada kiriman.');
    fill($('log-recv'), demo.received, recvRow, 'Belum ada yang masuk.');
    paintFailButton(demo.fail_next);
  }

  function paintFailButton(n) {
    $('fail-next').textContent = n > 0 ? n + ' kiriman berikutnya akan gagal' : 'Gagalkan 2 kiriman berikutnya';
  }

  async function failNext() {
    try {
      const s = await api('/demo-merchant', { method: 'POST', body: { fail_next: 2 } });
      paintFailButton(s.fail_next);
    } catch (e) {
      /* the log shows the state anyway */
    }
  }

  // ---------------------------------------------------------------- live stream
  function connect() {
    const live = $('live');
    const es = new EventSource('/api/events');
    es.onopen = () => {
      live.className = 'live on';
      $('live-text').textContent = 'Live';
    };
    es.onerror = () => {
      live.className = 'live off';
      $('live-text').textContent = 'Terputus, menyambung ulang…';
    };
    es.addEventListener('payment', (m) => {
      const e = JSON.parse(m.data);
      if (e.invoice && state.invoice && e.invoice.id === state.invoice.id) closeReceipt(e.invoice.status);
      if (e.type === 'invoice.paid' || e.type === 'payment.static') loadWallet();
    });
    es.addEventListener('webhook', (m) => {
      const a = JSON.parse(m.data);
      const list = $('log-sent');
      const empty = list.querySelector('.log-empty');
      if (empty) empty.remove();
      list.prepend(sentRow(a, true));
      api('/demo-merchant').then((d) => {
        fill($('log-recv'), d.received, recvRow, 'Belum ada yang masuk.');
        paintFailButton(d.fail_next);
      }).catch(() => {});
    });
  }

  // ---------------------------------------------------------------- wiring
  function wire() {
    $('bill-form').addEventListener('submit', printBill);
    $('amount').addEventListener('input', (e) => formatAmountInput(e.target));
    $('c-amount-input').addEventListener('input', (e) => formatAmountInput(e.target));
    document.querySelectorAll('.quick button').forEach((b) => b.addEventListener('click', () => {
      $('amount').value = Number(b.dataset.amount).toLocaleString('id-ID');
      $('static').checked = false;
      $('amount').disabled = false;
    }));
    $('static').addEventListener('change', (e) => { $('amount').disabled = e.target.checked; });
    $('cancel').addEventListener('click', cancelBill);
    $('copy').addEventListener('click', copyPayload);
    $('scan').addEventListener('click', () => readQr(state.payload));
    $('read').addEventListener('click', () => readQr($('paste').value));
    $('to-pin').addEventListener('click', toPin);
    document.querySelectorAll('[data-back]').forEach((b) => b.addEventListener('click', resetPhone));
    document.querySelectorAll('[data-k]').forEach((b) => b.addEventListener('click', () => pinKey(b.dataset.k)));
    $('v-pin').querySelector('[data-del]').addEventListener('click', () => pinKey('del'));
    $('fail-next').addEventListener('click', failNext);
    $('w-reset').addEventListener('click', resetWallet);
    document.addEventListener('keydown', (e) => {
      if ($('v-pin').hidden || e.target.closest('input, textarea, select')) return;
      if (/^\d$/.test(e.key)) pinKey(e.key);
      else if (e.key === 'Backspace') pinKey('del');
    });
  }

  async function boot() {
    wire();
    connect();
    try {
      await ensureWallet();
      await Promise.all([loadMerchants(), loadWallet(), loadLogs()]);
    } catch (e) {
      $('bill-error').textContent = e.message;
    }
  }

  boot();
})();
