/* ==========================================================
   清课表 — 单文件课表 · ICS 导入
   数据只存在 localStorage，不发往任何服务器。
   ========================================================== */

const DAY_ABBR = ['周日','周一','周二','周三','周四','周五','周六'];
const DAY_LATIN = ['SUN','MON','TUE','WED','THU','FRI','SAT'];
const ICS_DAYS  = ['SU','MO','TU','WE','TH','FR','SA'];
const MS_DAY    = 86400000;
const STORE_KEY = 'qingkebiao.v1';

/* ---------- state ---------- */
let state = {
  sessions: [],          // {uid,title,location,teacher,desc,start:Date,end:Date}
  termStart: null,       // Date, 第1周周一 00:00 local
  weeks: 0,
  showWeekend: true,
  hourPx: 65,
  view: 'week',
  weekIdx: 1,
  dayDate: null,      // 今日视图当前停在哪天（不持久化）
  source: ''
};

/* ==========================================================
   ICS 解析
   ========================================================== */

function unfold(text){
  return text.replace(/\r\n/g,'\n').replace(/\r/g,'\n').replace(/\n[ \t]/g,'');
}

function splitOutsideQuotes(s, sep){
  const out = []; let cur = '', q = false;
  for (const c of s){
    if (c === '"') { q = !q; cur += c; }
    else if (c === sep && !q) { out.push(cur); cur = ''; }
    else cur += c;
  }
  out.push(cur);
  return out;
}

function parseLine(line){
  let i = 0, q = false;
  while (i < line.length){
    const c = line[i];
    if (c === '"') q = !q;
    else if (c === ':' && !q) break;
    i++;
  }
  if (i >= line.length) return null;
  const head = line.slice(0, i);
  const value = line.slice(i + 1);
  const parts = splitOutsideQuotes(head, ';');
  const params = {};
  for (const p of parts.slice(1)){
    const eq = p.indexOf('=');
    if (eq > 0) params[p.slice(0, eq).toUpperCase()] = p.slice(eq + 1).replace(/^"|"$/g, '');
  }
  return { name: parts[0].toUpperCase(), params, value };
}

function unescapeText(v){
  return v.replace(/\\([nN])/g, '\n')
          .replace(/\\([,;\\])/g, '$1');
}

/* 用 Intl 求命名时区在某一瞬间的偏移（分钟，东为正） */
const tzCache = new Map();
function tzOffsetMin(tzid, ts){
  let dtf = tzCache.get(tzid);
  if (dtf === undefined){
    try {
      dtf = new Intl.DateTimeFormat('en-US', {
        timeZone: tzid, hour12: false,
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      });
    } catch { dtf = null; }
    tzCache.set(tzid, dtf);
  }
  if (!dtf) return null;
  const p = {};
  for (const { type, value } of dtf.formatToParts(new Date(ts))) p[type] = value;
  let h = +p.hour; if (h === 24) h = 0;
  const asUTC = Date.UTC(+p.year, +p.month - 1, +p.day, h, +p.minute, +p.second);
  return (asUTC - Math.floor(ts / 1000) * 1000) / 60000;
}

function zonedToDate(y, mo, d, h, mi, s, tzid){
  const naive = Date.UTC(y, mo - 1, d, h, mi, s);
  let ts = naive;
  for (let k = 0; k < 2; k++){
    const off = tzOffsetMin(tzid, ts);
    if (off === null) return new Date(y, mo - 1, d, h, mi, s); // 未知时区 → 当成本地墙钟
    ts = naive - off * 60000;
  }
  return new Date(ts);
}

/* 解析 DTSTART/DTEND/EXDATE 单个值 */
function parseDateValue(v, params){
  v = v.trim();
  const m = v.match(/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?$/);
  if (!m) { const d = new Date(v); return isNaN(d) ? null : { date: d, allDay: false }; }
  const [, Y, M, D, hh, mm, ss, z] = m;
  const allDay = !hh || (params && params.VALUE === 'DATE');
  if (allDay) return { date: new Date(+Y, +M - 1, +D, 0, 0, 0), allDay: true };
  if (z) return { date: new Date(Date.UTC(+Y, +M - 1, +D, +hh, +mm, +ss)), allDay: false };
  const tzid = params && params.TZID;
  if (tzid) return { date: zonedToDate(+Y, +M, +D, +hh, +mm, +ss, tzid), allDay: false };
  return { date: new Date(+Y, +M - 1, +D, +hh, +mm, +ss), allDay: false }; // floating
}

function parseDuration(v){
  const m = v.match(/^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/);
  if (!m) return null;
  const sign = m[1] === '-' ? -1 : 1;
  const ms = (+(m[2] || 0) * 604800 + +(m[3] || 0) * 86400 +
              +(m[4] || 0) * 3600  + +(m[5] || 0) * 60 + +(m[6] || 0)) * 1000;
  return sign * ms;
}

function parseRRule(v){
  const o = {};
  for (const part of v.split(';')){
    const eq = part.indexOf('=');
    if (eq > 0) o[part.slice(0, eq).toUpperCase()] = part.slice(eq + 1);
  }
  return o;
}

const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, d.getHours(), d.getMinutes(), d.getSeconds());
const atMidnight = d => new Date(d.getFullYear(), d.getMonth(), d.getDate());
function mondayOf(d){
  const m = atMidnight(d);
  const shift = (m.getDay() + 6) % 7;   // Mon=0 … Sun=6
  return addDays(m, -shift);
}

/* 展开重复规则 */
function expandRRule(ev, horizonEnd){
  const r = parseRRule(ev.rrule);
  const freq = (r.FREQ || '').toUpperCase();
  const interval = Math.max(1, +(r.INTERVAL || 1));
  const count = r.COUNT ? +r.COUNT : null;
  const untilP = r.UNTIL ? parseDateValue(r.UNTIL, {}) : null;
  const until = untilP ? untilP.date : null;
  const hardStop = until || horizonEnd;
  const out = [];
  const LIMIT = 500;

  const push = d => { out.push(d); return !(count && out.length >= count); };

  if (freq === 'WEEKLY'){
    const wkst = (r.WKST || 'MO').toUpperCase();
    const wkstIdx = Math.max(0, ICS_DAYS.indexOf(wkst));
    const byday = r.BYDAY
      ? r.BYDAY.split(',').map(s => s.replace(/^[+-]?\d+/, '').toUpperCase()).filter(s => ICS_DAYS.includes(s))
      : [ICS_DAYS[ev.start.getDay()]];
    // DTSTART 所在周的起点
    const s0 = atMidnight(ev.start);
    let weekStart = addDays(s0, -((s0.getDay() - wkstIdx + 7) % 7));
    outer:
    for (let w = 0; w < 260; w++){
      const base = addDays(weekStart, w * interval * 7);
      if (base > hardStop && w > 0) break;
      for (const d of byday){
        const off = (ICS_DAYS.indexOf(d) - wkstIdx + 7) % 7;
        const day = addDays(base, off);
        const occ = new Date(day.getFullYear(), day.getMonth(), day.getDate(),
                             ev.start.getHours(), ev.start.getMinutes(), ev.start.getSeconds());
        if (occ < ev.start) continue;
        if (occ > hardStop) break outer;
        if (!push(occ) || out.length >= LIMIT) break outer;
      }
    }
  } else if (freq === 'DAILY'){
    for (let i = 0; i < 800; i++){
      const occ = addDays(ev.start, i * interval);
      if (occ > hardStop) break;
      if (!push(occ) || out.length >= LIMIT) break;
    }
  } else if (freq === 'MONTHLY' || freq === 'YEARLY'){
    const step = freq === 'MONTHLY' ? interval : interval * 12;
    for (let i = 0; i < 120; i++){
      const s = ev.start;
      const occ = new Date(s.getFullYear(), s.getMonth() + i * step, s.getDate(),
                           s.getHours(), s.getMinutes(), s.getSeconds());
      if (occ > hardStop) break;
      if (!push(occ) || out.length >= LIMIT) break;
    }
  } else {
    out.push(ev.start);
  }
  out.sort((a, b) => a - b);
  return out;
}

const TEACHER_RE = /(?:授课教师|任课教师|教师|老师|教员|讲师|Teacher|Instructor)\s*[：:]\s*([^\n;,，、]{1,24})/i;

/* DESCRIPTION 常见形如 "教师：李慧敏  学分：5.0  考核方式：考试"，
   要在下一个 "键：值" 或双空格处收住，别把整行吞掉。 */
function cleanTeacher(v){
  return v.split(/\s{2,}|\t/)[0]
          .replace(/\s*\S+\s*[：:].*$/, '')
          .trim();
}

function parseICS(text){
  if (!/BEGIN:VCALENDAR/i.test(text))
    throw new Error('这不像 ICS 日历文件 — 开头找不到 BEGIN:VCALENDAR。');

  const lines = unfold(text).split('\n');
  const raw = [];
  let cur = null, depth = null;

  for (const line of lines){
    if (!line.trim()) continue;
    const up = line.toUpperCase();
    if (up.startsWith('BEGIN:VEVENT')) { cur = { exdate: [], props: {} }; depth = null; continue; }
    if (up.startsWith('END:VEVENT'))   { if (cur) raw.push(cur); cur = null; continue; }
    if (!cur) continue;
    // 跳过 VEVENT 内嵌的 VALARM
    if (up.startsWith('BEGIN:'))  { depth = up.slice(6); continue; }
    if (up.startsWith('END:'))    { depth = null; continue; }
    if (depth) continue;

    const p = parseLine(line);
    if (!p) continue;
    switch (p.name){
      case 'SUMMARY':       cur.title = unescapeText(p.value); break;
      case 'LOCATION':      cur.location = unescapeText(p.value); break;
      case 'DESCRIPTION':   cur.desc = unescapeText(p.value); break;
      case 'UID':           cur.uid = p.value; break;
      case 'RRULE':         cur.rrule = p.value; break;
      case 'DTSTART':       { const r = parseDateValue(p.value, p.params); if (r) { cur.start = r.date; cur.allDay = r.allDay; } break; }
      case 'DTEND':         { const r = parseDateValue(p.value, p.params); if (r) cur.end = r.date; break; }
      case 'DURATION':      { const ms = parseDuration(p.value); if (ms) cur.durMs = ms; break; }
      case 'EXDATE':        for (const v of p.value.split(',')){ const r = parseDateValue(v, p.params); if (r) cur.exdate.push(+r.date); } break;
      case 'RECURRENCE-ID': { const r = parseDateValue(p.value, p.params); if (r) cur.recurId = +r.date; break; }
      case 'ORGANIZER':     cur.organizer = unescapeText(p.value).replace(/^mailto:/i, ''); break;
      case 'STATUS':        cur.status = p.value.toUpperCase(); break;
    }
  }

  const events = raw.filter(e => e.start && e.status !== 'CANCELLED');
  if (!events.length) throw new Error('文件解析成功，但里面没有任何日程（VEVENT）。');

  // RECURRENCE-ID 覆盖：把被覆盖的那一次从母事件里排除
  const overrideBy = new Map();  // uid -> Set(ms)
  for (const e of events){
    if (e.recurId != null && e.uid){
      if (!overrideBy.has(e.uid)) overrideBy.set(e.uid, new Set());
      overrideBy.get(e.uid).add(e.recurId);
    }
  }

  // 时间跨度上限：最晚的 UNTIL，否则 DTSTART 最大值 + 1 年
  let maxStart = Math.max(...events.map(e => +e.start));
  const horizon = new Date(maxStart + 400 * MS_DAY);

  const sessions = [];
  for (const e of events){
    const durMs = e.end ? (+e.end - +e.start) : (e.durMs || (e.allDay ? MS_DAY : 90 * 60000));
    const skip = new Set(e.exdate);
    if (e.recurId == null && e.uid && overrideBy.has(e.uid))
      for (const ms of overrideBy.get(e.uid)) skip.add(ms);

    const occs = (e.rrule && e.recurId == null) ? expandRRule(e, horizon) : [e.start];
    for (const st of occs){
      if (skip.has(+st)) continue;
      let teacher = '';
      const src = [e.desc, e.location].filter(Boolean).join('\n');
      const tm = src.match(TEACHER_RE);
      if (tm) teacher = cleanTeacher(tm[1]);
      else if (e.organizer && !/@/.test(e.organizer)) teacher = e.organizer;
      sessions.push({
        uid: e.uid || '',
        title: (e.title || '未命名').trim(),
        location: (e.location || '').trim(),
        teacher,
        desc: e.desc || '',
        allDay: !!e.allDay,
        start: st,
        end: new Date(+st + durMs)
      });
    }
  }
  sessions.sort((a, b) => a.start - b.start);
  if (!sessions.length) throw new Error('日程都被排除规则过滤掉了，没有可显示的课。');
  return sessions;
}

/* ==========================================================
   派生数据
   ========================================================== */

function recompute(keepTermStart){
  const s = state.sessions;
  if (!s.length){ state.weeks = 0; return; }
  if (!keepTermStart || !state.termStart) state.termStart = mondayOf(s[0].start);
  const last = Math.max(...s.map(x => +x.end));
  state.weeks = Math.max(1, Math.ceil((last - +state.termStart) / (7 * MS_DAY)));
}

const weekOf = d => Math.floor((+atMidnight(d) - +state.termStart) / (7 * MS_DAY)) + 1;
const weekMonday = w => addDays(state.termStart, (w - 1) * 7);

/* 手挑的十个色相，像地铁线路色：彼此分得开，且避开在浅底上发虚的黄绿带。
   课程超过十门时整环偏移 11°，继续错开。 */
const HUE_RING = [352, 22, 42, 96, 162, 196, 218, 256, 288, 322];
function courseIndex(){
  const names = [...new Set(state.sessions.map(s => s.title))].sort((a, b) => a.localeCompare(b, 'zh'));
  const map = new Map();
  names.forEach((n, i) => {
    const h = HUE_RING[i % HUE_RING.length] + Math.floor(i / HUE_RING.length) * 11;
    map.set(n, h % 360);
  });
  return map;
}
let HUES = new Map();
const hueOf = t => HUES.get(t) ?? 0;

/* 把周次数组压成 "1-8,10,12-16" */
function compressWeeks(ws){
  const a = [...new Set(ws)].sort((x, y) => x - y);
  const out = [];
  let i = 0;
  while (i < a.length){
    let j = i;
    while (j + 1 < a.length && a[j + 1] === a[j] + 1) j++;
    out.push(i === j ? `${a[i]}` : `${a[i]}-${a[j]}`);
    i = j + 1;
  }
  return out.join(',');
}

const pad = n => String(n).padStart(2, '0');
const hhmm = d => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const mdSlash = d => `${pad(d.getMonth() + 1)}/${pad(d.getDate())}`;
const mdDash = d => `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const isoDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const minsOf = d => d.getHours() * 60 + d.getMinutes();

/* ==========================================================
   持久化
   ========================================================== */

function save(){
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify({
      sessions: state.sessions.map(s => ({ ...s, start: +s.start, end: +s.end })),
      termStart: state.termStart ? +state.termStart : null,
      showWeekend: state.showWeekend,
      hourPx: state.hourPx,
      source: state.source
    }));
  } catch { /* 隐私模式 / 存储被禁用：内存里照常用 */ }
}

function load(){
  let raw;
  try { raw = localStorage.getItem(STORE_KEY); } catch { return false; }
  if (!raw) return false;
  try {
    const d = JSON.parse(raw);
    if (!Array.isArray(d.sessions) || !d.sessions.length) return false;
    state.sessions = d.sessions.map(s => ({ ...s, start: new Date(s.start), end: new Date(s.end) }));
    state.termStart = d.termStart ? new Date(d.termStart) : null;
    state.showWeekend = d.showWeekend !== false;
    state.hourPx = d.hourPx || 65;
    state.source = d.source || '';
    recompute(true);
    return true;
  } catch { return false; }
}

/* ==========================================================
   渲染
   ========================================================== */

const $ = id => document.getElementById(id);
const stage = $('stage');

function dayColumns(){
  return state.showWeekend ? [0,1,2,3,4,5,6] : [0,1,2,3,4];  // 相对周一的偏移
}

function timeBounds(){
  const s = state.sessions.filter(x => !x.allDay);
  if (!s.length) return [8 * 60, 21 * 60];
  let lo = Infinity, hi = -Infinity;
  for (const x of s){
    lo = Math.min(lo, minsOf(x.start));
    const e = minsOf(x.end) || 24 * 60;
    hi = Math.max(hi, e <= minsOf(x.start) ? 24 * 60 : e);
  }
  lo = Math.max(0, Math.floor(lo / 60) * 60 - 0);
  hi = Math.min(24 * 60, Math.ceil(hi / 60) * 60);
  if (hi - lo < 240) hi = Math.min(24 * 60, lo + 240);
  return [lo, hi];
}

/* 同一天内重叠课程的并排排布 */
function layoutDay(evs){
  const sorted = [...evs].sort((a, b) => a.start - b.start || b.end - a.end);
  let cluster = [], clusterEnd = -Infinity;
  const flush = () => {
    if (!cluster.length) return;
    const cols = [];
    for (const e of cluster){
      let c = cols.findIndex(end => end <= +e.start);
      if (c < 0) { cols.push(+e.end); c = cols.length - 1; } else cols[c] = +e.end;
      e._col = c;
    }
    for (const e of cluster) e._cols = cols.length;
    cluster = []; clusterEnd = -Infinity;
  };
  for (const e of sorted){
    if (cluster.length && +e.start >= clusterEnd) flush();
    cluster.push(e);
    clusterEnd = Math.max(clusterEnd, +e.end);
  }
  flush();
  return sorted;
}

function renderEmpty(){
  stage.innerHTML = `
    <div class="empty">
      <div class="big">还没有课表</div>
      <p style="margin:0 0 16px;font-size:13px">导入学校的 .ics 日历文件，或先看看示例长什么样。</p>
      <div class="row" style="justify-content:center">
        <button class="btn primary" id="e1">导入课表</button>
        <button class="btn" id="e2">载入示例课表</button>
      </div>
    </div>`;
  $('e1').onclick = () => $('importDlg').showModal();
  $('e2').onclick = () => { applySessions(parseICS(DEMO_ICS), '示例课表'); };
}

function renderWeek(){
  const [lo, hi] = timeBounds();
  const minpx = state.hourPx / 60;
  const height = (hi - lo) * minpx;
  const cols = dayColumns();
  const mon = weekMonday(state.weekIdx);
  const now = new Date();
  const todayKey = isoDate(now);

  // 按天分桶
  const buckets = cols.map(off => {
    const d = addDays(mon, off);
    const key = isoDate(d);
    return { date: d, key, items: state.sessions.filter(s => isoDate(s.start) === key) };
  });

  let head = `<div class="hcell corner"><span class="lbl" style="font-size:9px">${state.weekIdx}/${state.weeks}</span></div>`;
  for (const b of buckets){
    const t = b.key === todayKey ? ' is-today' : '';
    head += `<div class="hcell${t}">
        <span class="dw">${DAY_ABBR[b.date.getDay()]}</span>
        <span class="dd">${mdDash(b.date)}</span>
      </div>`;
  }

  // 时间轴
  let axis = '';
  for (let m = lo; m <= hi; m += 30){
    const top = (m - lo) * minpx;
    const isHour = m % 60 === 0;
    if (!isHour && state.hourPx < 76) continue;
    axis += `<div class="tick${isHour ? '' : ' half'}" style="top:${top}px">${isHour ? `${pad(m / 60)}:00` : pad(m % 60)}</div>`;
  }

  let body = `<div class="axis" style="height:${height}px">${axis}</div>`;

  for (const b of buckets){
    const cls = b.key === todayKey ? ' is-today' : ([0, 6].includes(b.date.getDay()) ? ' is-weekend' : '');
    let blocks = '';
    const timed = layoutDay(b.items.filter(s => !s.allDay));
    for (const s of timed){
      const st = Math.max(lo, minsOf(s.start));
      let en = minsOf(s.end); if (en <= minsOf(s.start)) en = 24 * 60;
      en = Math.min(hi, en);
      const top = (st - lo) * minpx;
      const h = Math.max(17, (en - st) * minpx - 1.5);
      const w = 100 / s._cols;
      const past = s.end < now ? ' past' : '';
      const short = h < 34;
      blocks += `<button class="blk${past}" style="--h:${hueOf(s.title)};top:${top}px;height:${h}px;left:${s._col * w}%;width:calc(${w}% - 2px)"
          data-k="${+s.start}|${encodeURIComponent(s.title)}" title="${esc(s.title)} · ${hhmm(s.start)}-${hhmm(s.end)}${s.location ? ' · ' + esc(s.location) : ''}">
          <span class="t">${esc(s.title)}</span>
          ${short ? '' : `<span class="m">${hhmm(s.start)}${s.location ? ' · ' + esc(s.location) : ''}</span>`}
        </button>`;
    }
    for (const s of b.items.filter(s => s.allDay)){
      blocks += `<button class="blk" style="--h:${hueOf(s.title)};top:0;height:17px;left:0;width:calc(100% - 2px)"
          data-k="${+s.start}|${encodeURIComponent(s.title)}"><span class="t" style="font-size:10px">${esc(s.title)}</span></button>`;
    }
    // “现在”线
    let nowEl = '';
    if (b.key === todayKey){
      const nm = minsOf(now);
      if (nm >= lo && nm <= hi){
        const top = (nm - lo) * minpx;
        nowEl = `<div class="nowline" style="top:${top}px"></div>`;
      }
    }
    body += `<div class="col${cls}" style="height:${height}px">${nowEl}${blocks}</div>`;
  }

  // 时间轴上的“现在”标签
  let nowTag = '';
  const wk = weekOf(now);
  if (wk === state.weekIdx){
    const nm = minsOf(now);
    if (nm >= lo && nm <= hi) nowTag = `<div class="nowtag" style="top:${(nm - lo) * minpx}px">${hhmm(now)}</div>`;
  }

  stage.innerHTML = `<div class="gridwrap" id="gw">
      <div class="grid" style="--cols:${cols.length};--minpx:${minpx}px">
        ${head}
        ${body}
      </div>
    </div>`;

  // 现在标签挂到 axis 上（它是 sticky 的）
  if (nowTag){
    const ax = stage.querySelector('.axis');
    if (ax) ax.insertAdjacentHTML('beforeend', nowTag);
  }

  stage.querySelectorAll('.blk').forEach(b => b.onclick = () => showDetail(b.dataset.k));

  // 竖直落点：让本周第一节课停在顶部附近，别把早八滚出屏幕；整天放得下就不滚
  const gw = $('gw');
  const first = timed0(buckets);
  if (first !== null) gw.scrollTop = Math.max(0, (Math.max(lo, first - 20) - lo) * minpx);
}

function timed0(buckets){
  let lo = null;
  for (const b of buckets) for (const s of b.items) if (!s.allDay) lo = lo === null ? minsOf(s.start) : Math.min(lo, minsOf(s.start));
  return lo;
}

function renderDay(){
  const now = new Date();
  const d = state.dayDate || now;
  const wk = weekOf(d);
  const key = isoDate(d);
  const items = state.sessions.filter(s => isoDate(s.start) === key)
                              .sort((a, b) => a.start - b.start);

  let rows = '';
  if (!items.length){
    rows = `<div class="empty"><div class="big">这天没课</div><p style="margin:0;font-size:13px">${DAY_ABBR[d.getDay()]} · ${mdDash(d)}</p></div>`;
  } else {
    for (const s of items){
      const live = s.start <= now && now < s.end;
      const soon = !live && s.start > now && (s.start - now) < 45 * 60000;
      const past = s.end < now;
      const meta = [s.location, s.teacher].filter(Boolean).join(' · ');
      rows += `<button class="dl-row${past ? ' past' : ''}" style="--h:${hueOf(s.title)}" data-k="${+s.start}|${encodeURIComponent(s.title)}">
          <div class="dl-time">${hhmm(s.start)}<span>${hhmm(s.end)}</span></div>
          <div class="dl-main">
            <div class="t">${esc(s.title)}
              ${live ? '<span class="pill">进行中</span>' : ''}
              ${soon ? '<span class="pill soon">即将开始</span>' : ''}
            </div>
            ${meta ? `<div class="s">${esc(meta)}</div>` : ''}
          </div>
        </button>`;
    }
  }

  const inTerm = wk >= 1 && wk <= state.weeks;
  stage.innerHTML = `<div class="daylist">
      <div class="dl-head">
        <h2>${DAY_ABBR[d.getDay()]}</h2>
        <span class="mono">${isoDate(d)} · ${inTerm ? `第 ${wk} 周` : '不在学期内'} · ${items.length} 节</span>
      </div>
      ${rows}
    </div>`;
  stage.querySelectorAll('.dl-row').forEach(b => b.onclick = () => showDetail(b.dataset.k));
}

function renderNextUp(){
  const el = $('nextup');
  if (!state.sessions.length){ el.innerHTML = `<span class="lbl">未导入课表</span>`; return; }
  const now = new Date();
  const live = state.sessions.find(s => s.start <= now && now < s.end);
  if (live){
    const left = Math.round((live.end - now) / 60000);
    el.innerHTML = `<span class="tag live">进行中</span>
      <span class="body"><strong>${esc(live.title)}</strong>
      ${live.location ? `<span class="sep">·</span>${esc(live.location)}` : ''}
      <span class="sep">·</span><span class="mono">还有 ${left} 分钟下课</span></span>`;
    return;
  }
  const next = state.sessions.find(s => s.start > now);
  if (!next){ el.innerHTML = `<span class="lbl">课表已结束 · 没有更多安排</span>`; return; }
  const mins = Math.round((next.start - now) / 60000);
  const when = mins < 60 ? `${mins} 分钟后`
    : isoDate(next.start) === isoDate(now) ? `今天 ${hhmm(next.start)}`
    : isoDate(next.start) === isoDate(addDays(now, 1)) ? `明天 ${hhmm(next.start)}`
    : `${mdDash(next.start)} ${DAY_ABBR[next.start.getDay()]} ${hhmm(next.start)}`;
  el.innerHTML = `<span class="tag">下一节</span>
    <span class="body"><strong>${esc(next.title)}</strong>
    ${next.location ? `<span class="sep">·</span>${esc(next.location)}` : ''}
    <span class="sep">·</span><span class="mono">${when}</span></span>`;
}

function renderChrome(){
  const has = state.sessions.length > 0;
  const dayMode = state.view === 'day';
  $('goToday').textContent = dayMode ? '今天' : '本周';

  if (!has){
    $('wkNum').textContent = '—';
    $('wkRange').textContent = '';
    $('termLabel').textContent = '';
  } else if (dayMode){
    const d = state.dayDate || new Date();
    $('wkNum').textContent = DAY_ABBR[d.getDay()];
    $('wkRange').textContent = mdSlash(d);
    $('termLabel').textContent = `${isoDate(state.termStart)} 起 · 共 ${state.weeks} 周`;
  } else {
    const mon = weekMonday(state.weekIdx);
    $('wkNum').textContent = `第 ${state.weekIdx} 周`;
    $('wkRange').textContent = `${mdSlash(mon)}–${mdSlash(addDays(mon, 6))}`;
    $('termLabel').textContent = `${isoDate(state.termStart)} 起 · 共 ${state.weeks} 周`;
  }

  if (!has){
    $('prevWk').disabled = $('nextWk').disabled = true;
  } else if (dayMode){
    const d = atMidnight(state.dayDate || new Date());
    $('prevWk').disabled = weekOf(addDays(d, -1)) < 1;
    $('nextWk').disabled = weekOf(addDays(d, 1)) > state.weeks;
  } else {
    $('prevWk').disabled = state.weekIdx <= 1;
    $('nextWk').disabled = state.weekIdx >= state.weeks;
  }
  $('goToday').disabled = !has;
  $('vDay').setAttribute('aria-pressed', String(state.view === 'day'));
  $('vWeek').setAttribute('aria-pressed', String(state.view === 'week'));
}

function render(){
  HUES = courseIndex();
  renderChrome();
  renderNextUp();
  if (!state.sessions.length) renderEmpty();
  else if (state.view === 'day') renderDay();
  else renderWeek();
}

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/* ---------- 课程详情 ---------- */
function showDetail(key){
  const [ms, encTitle] = key.split('|');
  const title = decodeURIComponent(encTitle);
  const s = state.sessions.find(x => +x.start === +ms && x.title === title);
  if (!s) return;
  const all = state.sessions.filter(x => x.title === title);
  const weeks = all.map(x => weekOf(x.start)).filter(w => w >= 1);
  const slots = [...new Set(all.map(x => `${DAY_ABBR[x.start.getDay()]} ${hhmm(x.start)}–${hhmm(x.end)}`))];
  const places = [...new Set(all.map(x => x.location).filter(Boolean))];
  const teachers = [...new Set(all.map(x => x.teacher).filter(Boolean))];
  const mins = Math.round((s.end - s.start) / 60000);

  $('detailHead').style.setProperty('--h', hueOf(title));
  $('detailHead').innerHTML = `<div class="swatch"></div>
    <div style="min-width:0">
      <h3>${esc(title)}</h3>
      <div class="sub">${isoDate(s.start)} ${DAY_ABBR[s.start.getDay()]} · ${hhmm(s.start)}–${hhmm(s.end)} · ${mins} 分钟 · 第 ${weekOf(s.start)} 周</div>
    </div>`;

  const rawDesc = s.desc && s.desc.replace(TEACHER_RE, '').trim();
  $('detailBody').innerHTML = `<dl class="dl">
      ${places.length ? `<dt>地点</dt><dd>${esc(places.join(' / '))}</dd>` : ''}
      ${teachers.length ? `<dt>教师</dt><dd>${esc(teachers.join(' / '))}</dd>` : ''}
      <dt>上课时间</dt><dd class="mono">${slots.map(esc).join('<br>')}</dd>
      <dt>周次</dt><dd class="mono">${weeks.length ? `${compressWeeks(weeks)} 周` : '—'}</dd>
      <dt>总节数</dt><dd class="mono">${all.length} 次</dd>
      ${rawDesc ? `<dt>备注</dt><dd><div class="raw">${esc(rawDesc)}</div></dd>` : ''}
    </dl>`;
  $('detailDlg').showModal();
}

/* ==========================================================
   导入
   ========================================================== */

function applySessions(sessions, sourceLabel){
  state.sessions = sessions;
  state.source = sourceLabel || '';
  // 周末一节课都没有就默认收起来，别让两列空格占掉半屏
  state.showWeekend = sessions.some(s => [0, 6].includes(s.start.getDay()));
  recompute(false);
  const today = new Date();
  state.dayDate = today;
  state.weekIdx = Math.min(Math.max(1, weekOf(today)), state.weeks);
  save();
  render();
}

function importText(text, label){
  const msg = $('importMsg');
  try {
    const sessions = parseICS(text);
    applySessions(sessions, label);
    const courses = new Set(sessions.map(s => s.title)).size;
    msg.className = 'msg ok';
    msg.innerHTML = `导入成功：<strong>${courses}</strong> 门课、<strong>${sessions.length}</strong> 节，学期从 <code>${isoDate(state.termStart)}</code> 起共 ${state.weeks} 周。
      <br>如果周次对不上，到设置里改"第 1 周的周一"。`;
    setTimeout(() => { $('importDlg').close(); msg.innerHTML = ''; msg.className = ''; }, 2200);
  } catch (e){
    msg.className = 'msg err';
    msg.textContent = '导入失败：' + e.message;
  }
}

function readFile(file){
  if (!file) return;
  const msg = $('importMsg');
  if (!/\.ics$/i.test(file.name) && file.type !== 'text/calendar'){
    msg.className = 'msg err';
    msg.textContent = `“${file.name}”看起来不是 .ics 文件。`;
    return;
  }
  const fr = new FileReader();
  fr.onload = () => importText(String(fr.result), file.name);
  fr.onerror = () => { msg.className = 'msg err'; msg.textContent = '文件读不出来，换一个试试。'; };
  fr.readAsText(file, 'utf-8');
}

async function fetchUrl(){
  const msg = $('importMsg');
  let url = $('urlIn').value.trim();
  if (!url) return;
  url = url.replace(/^webcal:\/\//i, 'https://');
  msg.className = 'msg';
  msg.textContent = '正在拉取…';
  $('fetchUrl').disabled = true;
  try {
    const res = await fetch(url, { redirect: 'follow' });
    if (!res.ok) throw new Error(`服务器返回 ${res.status} ${res.statusText}`);
    const text = await res.text();
    importText(text, url);
  } catch (e){
    msg.className = 'msg err';
    msg.innerHTML = `拉取失败：${esc(e.message)}<br>
      多数学校服务器不允许网页跨域读取（CORS），这不是链接写错了。改用文件导入即可：
      <ol>
        <li>在浏览器新标签页里打开那个链接 — 它会下载一个 <code>.ics</code> 文件</li>
        <li>把下载好的文件拖到本页最上方的虚线框里</li>
      </ol>`;
  } finally {
    $('fetchUrl').disabled = false;
  }
}

/* ==========================================================
   事件绑定
   ========================================================== */

/* 翻页：今日视图按天走，周视图按周走 */
function step(dir){
  if (state.view === 'day'){
    const nd = addDays(atMidnight(state.dayDate || new Date()), dir);
    const w = weekOf(nd);
    if (w < 1 || w > state.weeks) return;      // 不滑出学期
    state.dayDate = nd;
    state.weekIdx = w;
  } else {
    state.weekIdx = Math.min(state.weeks, Math.max(1, state.weekIdx + dir));
  }
  render();
}
$('prevWk').onclick = () => step(-1);
$('nextWk').onclick = () => step(1);
$('goToday').onclick = () => {
  const today = new Date();
  state.dayDate = today;
  state.weekIdx = Math.min(Math.max(1, weekOf(today)), state.weeks || 1);
  render();
};
$('vDay').onclick = () => {
  state.view = 'day';
  // 从周视图切过来：今天在这一周就停今天，否则停这一周的周一
  const today = new Date();
  state.dayDate = (state.sessions.length && weekOf(today) === state.weekIdx)
    ? today : weekMonday(state.weekIdx);
  render();
};
$('vWeek').onclick = () => {
  state.view = 'week';
  if (state.dayDate && state.sessions.length)
    state.weekIdx = Math.min(state.weeks, Math.max(1, weekOf(state.dayDate)));
  render();
};

/* 触屏左右滑动翻页。绑在 stage 上（它不随 render 重建）；
   竖向滚动优先，慢拖不算翻页。走 .click() 是为了自动尊重按钮的禁用状态。 */
(function bindSwipe(){
  let x0 = 0, y0 = 0, t0 = 0, tracking = false;
  stage.addEventListener('touchstart', e => {
    if (e.touches.length !== 1){ tracking = false; return; }
    x0 = e.touches[0].clientX; y0 = e.touches[0].clientY;
    t0 = Date.now(); tracking = true;
  }, { passive: true });
  stage.addEventListener('touchend', e => {
    if (!tracking) return;
    tracking = false;
    const dx = e.changedTouches[0].clientX - x0;
    const dy = e.changedTouches[0].clientY - y0;
    if (Date.now() - t0 > 600) return;
    if (Math.abs(dx) < 56 || Math.abs(dx) < Math.abs(dy) * 1.8) return;
    (dx < 0 ? $('nextWk') : $('prevWk')).click();
  }, { passive: true });
})();

$('openImport').onclick = () => $('importDlg').showModal();
$('openSettings').onclick = () => { syncSettings(); $('settingsDlg').showModal(); };
document.querySelectorAll('[data-close]').forEach(b => b.onclick = e => e.target.closest('dialog').close());

$('pickFile').onclick = () => $('fileIn').click();
$('fileIn').onchange = e => readFile(e.target.files[0]);
$('fetchUrl').onclick = fetchUrl;
$('urlIn').onkeydown = e => { if (e.key === 'Enter') fetchUrl(); };
$('parsePaste').onclick = () => {
  const t = $('pasteIn').value.trim();
  if (!t){ $('importMsg').className = 'msg err'; $('importMsg').textContent = '粘贴框是空的。'; return; }
  importText(t, '粘贴的文本');
};
$('loadDemo').onclick = () => importText(DEMO_ICS, '示例课表');

const drop = $('drop');
['dragenter', 'dragover'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave', 'drop'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.remove('over'); }));
drop.addEventListener('drop', e => readFile(e.dataTransfer.files[0]));
// 整页拖放也接受
window.addEventListener('dragover', e => e.preventDefault());
window.addEventListener('drop', e => {
  e.preventDefault();
  const f = e.dataTransfer && e.dataTransfer.files[0];
  if (f && /\.ics$/i.test(f.name)){ $('importDlg').showModal(); readFile(f); }
});

/* ---------- 设置 ---------- */
function syncSettings(){
  $('termStartIn').value = state.termStart ? isoDate(state.termStart) : '';
  $('weekCount').textContent = state.weeks || '—';
  $('swWeekend').setAttribute('aria-pressed', String(state.showWeekend));
  $('zoomIn').value = state.hourPx;
  const hues = courseIndex();
  const counts = new Map();
  for (const s of state.sessions) counts.set(s.title, (counts.get(s.title) || 0) + 1);
  $('legend').innerHTML = [...hues.keys()].length
    ? [...hues.keys()].map(n => `<div style="--h:${hues.get(n)}"><i></i>${esc(n)} <span class="mono">${counts.get(n)}</span></div>`).join('')
    : '<span class="lbl">未导入课表</span>';
}

$('termStartIn').onchange = e => {
  if (!e.target.value) return;
  const [y, m, d] = e.target.value.split('-').map(Number);
  state.termStart = mondayOf(new Date(y, m - 1, d));
  recompute(true);
  state.weekIdx = Math.min(Math.max(1, weekOf(new Date())), state.weeks);
  save(); render(); syncSettings();
};
$('swWeekend').onclick = e => {
  state.showWeekend = !state.showWeekend;
  e.currentTarget.setAttribute('aria-pressed', String(state.showWeekend));
  save(); render();
};
$('zoomIn').onchange = e => {
  state.hourPx = Math.min(200, Math.max(34, +e.target.value || 65));
  e.target.value = state.hourPx;
  save(); render();
};

function backupJson(){
  return JSON.stringify({
    app: '清课表', version: 1, exportedAt: new Date().toISOString(),
    termStart: state.termStart ? isoDate(state.termStart) : null,
    weeks: state.weeks, source: state.source,
    sessions: state.sessions.map(s => ({
      title: s.title, location: s.location, teacher: s.teacher, desc: s.desc,
      start: new Date(+s.start).toISOString(), end: new Date(+s.end).toISOString(), allDay: !!s.allDay
    }))
  }, null, 2);
}

$('copyJson').onclick = async () => {
  const m = $('settingsMsg');
  if (!state.sessions.length){ m.className = 'msg err'; m.textContent = '没有课表可备份。'; return; }
  const text = backupJson();
  const box = $('backupOut');
  try {
    await navigator.clipboard.writeText(text);
    box.hidden = true;
    m.className = 'msg ok';
    m.textContent = `已复制 ${state.sessions.length} 节课的 JSON 到剪贴板。`;
  } catch {
    // 剪贴板被拦（非 HTTPS、无用户手势等）→ 直接把文本摊开让人手动复制
    box.hidden = false;
    box.value = text;
    box.select();
    m.className = 'msg';
    m.textContent = '浏览器拒绝了剪贴板访问，JSON 已展开在下面，手动复制即可。';
  }
};

$('exportJson').onclick = () => {
  const m = $('settingsMsg');
  if (!state.sessions.length){ m.className = 'msg err'; m.textContent = '没有课表可备份。'; return; }
  const blob = new Blob([backupJson()], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `课表备份-${isoDate(new Date())}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  m.className = 'msg';
  m.textContent = '已请求下载。托管环境禁止页面下载时不会有任何反应 — 那就改用「复制备份」。';
};

$('importJsonBtn').onclick = () => $('jsonIn').click();
$('jsonIn').onchange = e => {
  const f = e.target.files[0]; if (!f) return;
  const fr = new FileReader();
  fr.onload = () => {
    const m = $('settingsMsg');
    try {
      const d = JSON.parse(String(fr.result));
      if (!Array.isArray(d.sessions) || !d.sessions.length) throw new Error('备份里没有课程');
      state.sessions = d.sessions.map(s => ({
        uid: '', title: s.title || '未命名', location: s.location || '', teacher: s.teacher || '',
        desc: s.desc || '', allDay: !!s.allDay, start: new Date(s.start), end: new Date(s.end)
      })).sort((a, b) => a.start - b.start);
      state.termStart = d.termStart ? mondayOf(new Date(d.termStart + 'T00:00:00')) : null;
      recompute(!!d.termStart);
      state.weekIdx = Math.min(Math.max(1, weekOf(new Date())), state.weeks);
      save(); render(); syncSettings();
      m.className = 'msg ok'; m.textContent = `已恢复 ${state.sessions.length} 节课。`;
    } catch (err){
      m.className = 'msg err'; m.textContent = '备份读取失败：' + err.message;
    }
  };
  fr.readAsText(f, 'utf-8');
};

$('clearAll').onclick = () => {
  const m = $('settingsMsg');
  if ($('clearAll').dataset.armed !== '1'){
    $('clearAll').dataset.armed = '1';
    $('clearAll').textContent = '再点一次确认清空';
    m.className = 'msg'; m.textContent = '清空后需要重新导入 .ics。建议先导出备份。';
    return;
  }
  state.sessions = []; state.termStart = null; state.weeks = 0; state.source = '';
  try { localStorage.removeItem(STORE_KEY); } catch {}
  $('clearAll').dataset.armed = '0';
  $('clearAll').textContent = '清空课表';
  m.className = ''; m.textContent = '';
  render(); syncSettings();
  $('settingsDlg').close();
};

/* 键盘：左右切周，T 回本周 */
window.addEventListener('keydown', e => {
  if (e.target.matches('input,textarea') || document.querySelector('dialog[open]')) return;
  if (e.key === 'ArrowLeft')  $('prevWk').click();
  if (e.key === 'ArrowRight') $('nextWk').click();
  if (e.key.toLowerCase() === 't') $('goToday').click();
});

/* 每分钟刷新“现在”线与下一节课 */
setInterval(() => { if (state.sessions.length) { renderNextUp(); if (state.view === 'day') renderDay(); else refreshNow(); } }, 60000);
function refreshNow(){
  // 轻量更新：只挪“现在”线，不重排整个网格
  const now = new Date();
  if (weekOf(now) !== state.weekIdx) return;
  const [lo, hi] = timeBounds();
  const minpx = state.hourPx / 60;
  const nm = minsOf(now);
  const line = stage.querySelector('.nowline');
  const tag = stage.querySelector('.nowtag');
  if (nm < lo || nm > hi) { line && line.remove(); tag && tag.remove(); return; }
  const top = (nm - lo) * minpx + 'px';
  if (line) line.style.top = top;
  if (tag) { tag.style.top = top; tag.textContent = hhmm(now); }
}
let resizeT;
window.addEventListener('resize', () => { clearTimeout(resizeT); resizeT = setTimeout(render, 160); });

/* ==========================================================
   示例课表（2026 秋季学期，开学 2026-09-07 周一）
   ========================================================== */
const DEMO_ICS = [
'BEGIN:VCALENDAR',
'VERSION:2.0',
'PRODID:-//qingkebiao//demo//CN',
'CALSCALE:GREGORIAN',
'X-WR-CALNAME:2026-2027 学年第一学期 课表',

'BEGIN:VEVENT','UID:demo-math@qkb','SUMMARY:高等数学 A(二)','LOCATION:教三-301',
'DESCRIPTION:教师：李慧敏  学分：5.0  考核方式：考试',
'DTSTART;TZID=Asia/Shanghai:20260907T080000','DTEND;TZID=Asia/Shanghai:20260907T094000',
'RRULE:FREQ=WEEKLY;COUNT=16','END:VEVENT',

'BEGIN:VEVENT','UID:demo-phys@qkb','SUMMARY:大学物理(上)','LOCATION:教二-208',
'DESCRIPTION:教师：陈立  学分：4.0','DTSTART;TZID=Asia/Shanghai:20260907T140000',
'DTEND;TZID=Asia/Shanghai:20260907T154000','RRULE:FREQ=WEEKLY;COUNT=16','END:VEVENT',

'BEGIN:VEVENT','UID:demo-ds@qkb','SUMMARY:数据结构','LOCATION:逸夫楼-402',
'DESCRIPTION:教师：王锐  学分：4.0','DTSTART;TZID=Asia/Shanghai:20260908T100000',
'DTEND;TZID=Asia/Shanghai:20260908T114000','RRULE:FREQ=WEEKLY;COUNT=16','END:VEVENT',

'BEGIN:VEVENT','UID:demo-la@qkb','SUMMARY:线性代数','LOCATION:教三-105',
'DESCRIPTION:教师：李慧敏  学分：2.5  前八周结课','DTSTART;TZID=Asia/Shanghai:20260909T080000',
'DTEND;TZID=Asia/Shanghai:20260909T094000','RRULE:FREQ=WEEKLY;COUNT=8','END:VEVENT',

'BEGIN:VEVENT','UID:demo-marx@qkb','SUMMARY:马克思主义基本原理','LOCATION:教一-201 大教室',
'DESCRIPTION:教师：周敏  学分：3.0','DTSTART;TZID=Asia/Shanghai:20260909T190000',
'DTEND;TZID=Asia/Shanghai:20260909T204000','RRULE:FREQ=WEEKLY;COUNT=16','END:VEVENT',

'BEGIN:VEVENT','UID:demo-pe@qkb','SUMMARY:体育(羽毛球)','LOCATION:风雨体育馆',
'DESCRIPTION:教师：张海','DTSTART;TZID=Asia/Shanghai:20260910T080000',
'DTEND;TZID=Asia/Shanghai:20260910T094000','RRULE:FREQ=WEEKLY;COUNT=16','END:VEVENT',

'BEGIN:VEVENT','UID:demo-dslab@qkb','SUMMARY:数据结构实验','LOCATION:计算机楼-机房3',
'DESCRIPTION:教师：王锐  双周上课','DTSTART;TZID=Asia/Shanghai:20260917T140000',
'DTEND;TZID=Asia/Shanghai:20260917T163000','RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=8','END:VEVENT',

'BEGIN:VEVENT','UID:demo-eng@qkb','SUMMARY:英语视听说','LOCATION:外语楼-语音室2',
'DESCRIPTION:教师：Sarah Wilson  国庆假期停课一次','DTSTART;TZID=Asia/Shanghai:20260911T100000',
'DTEND;TZID=Asia/Shanghai:20260911T114000','RRULE:FREQ=WEEKLY;COUNT=16',
'EXDATE;TZID=Asia/Shanghai:20261002T100000','END:VEVENT',

'BEGIN:VEVENT','UID:demo-midterm@qkb','SUMMARY:高等数学 期中考试','LOCATION:教三-301',
'DESCRIPTION:闭卷  带学生证和身份证','DTSTART;TZID=Asia/Shanghai:20261102T190000',
'DTEND;TZID=Asia/Shanghai:20261102T210000','END:VEVENT',

'END:VCALENDAR'
].join('\r\n');

/* ==========================================================
   启动
   ========================================================== */
if (window.innerWidth < 640) state.view = 'day';
if (!load()) {
  // 首次打开：装载示例，让人立刻看见这东西是什么样（不写入存储，导入真课表即覆盖）
  try {
    state.sessions = parseICS(DEMO_ICS);
    state.source = '示例课表';
    state.showWeekend = state.sessions.some(s => [0, 6].includes(s.start.getDay()));
    recompute(false);
  } catch {}
}
if (state.sessions.length){
  const today = new Date();
  state.dayDate = today;
  state.weekIdx = Math.min(Math.max(1, weekOf(today)), state.weeks);
}
render();
