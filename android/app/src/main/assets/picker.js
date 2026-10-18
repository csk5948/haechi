// Element picker for the in-app browser (PickerActivity). Injected after every page load.
// Tap -> the element under the finger becomes the pick; a CSS selector for it is built here and
// handed to the app through the HaechiPicker interface. Nothing is sent anywhere else.
//
// A toggle in the app decides what a tap picks, and switching it never drops anything already picked:
//  - 숨기기: the tap sets the area to hide (red; ▲/▼ widen/narrow it).
//  - 남기기: the tap marks / unmarks something to keep (green, several; ▲/▼ resize the last one).
// No marks inside the area: the whole area gets one rule. Marks inside: only the rest of the area is hidden.
(function () {
  if (window.__haechi) return;
  var H = window.__haechi = {};
  var catcher = null, stack = [], useClass = true, keeps = [];

  function style(id) {
    var s = document.getElementById(id);
    if (!s) {
      s = document.createElement('style');
      s.id = id;
      (document.head || document.documentElement).appendChild(s);
    }
    return s;
  }

  function count(sel) {
    try { return document.querySelectorAll(sel).length; } catch (e) { return -1; }
  }

  /** Rules already in browser.txt for this site, so the page looks like it will in Samsung Internet. */
  H.setRules = function (list) {
    style('haechi-rules').textContent = list.map(function (s) { return s + '{display:none!important}'; }).join('\n');
  };

  /** Outline everything the selector matches; returns how many (-1 = invalid selector). */
  H.show = function (sel) {
    var n = sel ? count(sel) : 0;
    style('haechi-hl').textContent = n > 0
      ? sel + '{outline:3px solid #e53935!important;outline-offset:-3px!important;background-color:rgba(229,57,53,.25)!important}'
      : '';
    return n;
  };

  H.preview = function (sel, on) {
    style('haechi-prev').textContent = on && count(sel) > 0 ? sel + '{display:none!important}' : '';
  };

  H.clear = function () {
    stack = [];
    keeps = [];
    keepStack = [];
    marks();
    style('haechi-hl').textContent = '';
    style('haechi-prev').textContent = '';
  };

  // A transparent layer over the page takes the taps (so links don't open and iframes don't
  // swallow them); the element below is found by looking through it. Scrolling still works.
  H.start = function () {
    if (catcher) return;
    catcher = document.createElement('div');
    catcher.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:transparent';
    catcher.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      catcher.style.display = 'none';
      var el = document.elementFromPoint(e.clientX, e.clientY);
      catcher.style.display = '';
      if (!el || el === document.documentElement || el === document.body) return;
      tapped(el);
    }, true);
    document.documentElement.appendChild(catcher);
  };

  H.stop = function () {
    if (catcher) { catcher.remove(); catcher = null; }
  };

  // keepMode only decides what the NEXT tap picks; switching it never drops what is already picked.
  var keepMode = false, keepStack = [];   // keepStack: ▲/▼ history of the last thing marked to keep

  function tapped(el) {
    if (!keepMode) {
      stack = [el];   // 숨기기: the area to hide (marks to keep stay; only those inside it count)
    } else {
      // 남기기: mark / unmark something to keep (a mark inside another mark replaces it)
      for (var i = 0; i < keeps.length; i++) {
        if (keeps[i] === el || keeps[i].contains(el) || el.contains(keeps[i])) { keeps.splice(i, 1); keepStack = []; report(); return; }
      }
      keeps.push(el);
      keepStack = [el];
    }
    report();
  }

  H.keep = function (on) {
    keepMode = on;
    report();
  };

  // ▲/▼ resize whatever the toggle points at: the area (숨기기) or the last mark to keep (남기기)
  H.up = function () {
    if (keepMode) {
      var k = keepStack[keepStack.length - 1], kp = k && k.parentElement;
      if (!kp || kp === document.body || kp === document.documentElement) return;
      keeps[keeps.indexOf(k)] = kp;
      keepStack.push(kp);
    } else {
      var c = stack[stack.length - 1], p = c && c.parentElement;
      if (!p || p === document.body || p === document.documentElement) return;
      stack.push(p);
    }
    report();
  };

  H.down = function () {
    if (keepMode) {
      if (keepStack.length < 2) return;
      var cur = keepStack.pop();
      keeps[keeps.indexOf(cur)] = keepStack[keepStack.length - 1];
    } else {
      if (stack.length < 2) return;
      stack.pop();
    }
    report();
  };

  H.mode = function (cls) {
    useClass = cls;
    if (stack.length) report();
  };

  /** Blue outline on the area, green on what stays (only while picking, never part of a rule). */
  function marks() {
    var old = document.querySelectorAll('[data-haechi-area],[data-haechi-keep]');
    for (var i = 0; i < old.length; i++) { old[i].removeAttribute('data-haechi-area'); old[i].removeAttribute('data-haechi-keep'); }
    var area = inside().length ? stack[stack.length - 1] : null;   // the area outline only matters once something in it is kept
    if (area) area.setAttribute('data-haechi-area', '');
    for (var k = 0; k < keeps.length; k++) keeps[k].setAttribute('data-haechi-keep', '');
    style('haechi-keep').textContent =
      '[data-haechi-area]{outline:3px dashed #1e63d6!important;outline-offset:-3px!important}' +
      '[data-haechi-keep]{outline:3px solid #178759!important;outline-offset:-3px!important;background-color:rgba(23,135,89,.12)!important}';
  }

  // Names with a run of digits ("ad-1234", "x93847") are usually generated per page load.
  function stable(name) { return !/\d{3,}/.test(name); }

  function stepOf(el) {
    var tag = el.tagName.toLowerCase();
    if (useClass) {
      if (el.id && stable(el.id)) return { s: '#' + CSS.escape(el.id), stop: true };
      var s = tag;
      for (var i = 0; i < el.classList.length; i++) {
        if (stable(el.classList[i])) s += '.' + CSS.escape(el.classList[i]);
      }
      return { s: s };
    }
    // Structure only (sites that randomize class names): tag + position among same-tag siblings.
    var p = el.parentElement, same = 0, idx = 0;
    if (p) {
      for (var k = p.firstElementChild; k; k = k.nextElementSibling) {
        if (k.tagName === el.tagName) { same++; if (k === el) idx = same; }
      }
    }
    return { s: same > 1 ? tag + ':nth-of-type(' + idx + ')' : tag };
  }

  /** Full path from body (or the nearest stable id), then the shortest tail that matches the same elements. */
  function build(el) {
    var steps = [], anchored = false;
    for (var e = el; e && e !== document.documentElement; e = e.parentElement) {
      if (e === document.body) { steps.unshift('body'); anchored = true; break; }
      var st = stepOf(e);
      steps.unshift(st.s);
      if (st.stop) break;
    }
    var full = steps.join(' > '), want = count(full);
    if (want < 1) return full;
    for (var k = 1; k <= steps.length; k++) {
      if (anchored && k === steps.length) break;
      var s = steps.slice(steps.length - k).join(' > ');
      if (count(s) === want) return s;
    }
    return full;
  }

  var SKIP = /^(SCRIPT|STYLE|LINK|META|NOSCRIPT|TEMPLATE|BR|HEAD|TITLE)$/;

  /**
   * True if the selector is valid, matches something, stays inside the area, and never hides
   * something to keep (or an element holding one).
   */
  function safe(sel, area) {
    var m;
    try { m = document.querySelectorAll(sel); } catch (e) { return false; }
    if (!m.length) return false;
    for (var i = 0; i < m.length; i++) {
      if (!area.contains(m[i]) || m[i] === area) return false;
      for (var k = 0; k < keeps.length; k++) if (m[i] === keeps[k] || m[i].contains(keeps[k])) return false;
    }
    return true;
  }

  /**
   * Plain ## selectors (no :has) that hide, inside the area, every element that is neither kept nor on
   * the way from the area down to something kept. Class-based when that is safe, else structural.
   */
  /** Marks to keep that lie inside the current area (others are kept marked but play no part). */
  function inside() {
    var area = stack[stack.length - 1];
    return area ? keeps.filter(function (k) { return k !== area && area.contains(k); }) : [];
  }

  function keepRules(area) {
    var onPath = [], out = [], seen = {}, kin = inside();
    for (var k = 0; k < kin.length; k++) {
      for (var e = kin[k]; e && e !== area; e = e.parentElement) if (onPath.indexOf(e) < 0) onPath.push(e);
    }
    var parents = [area].concat(onPath.filter(function (e) { return kin.indexOf(e) < 0; }));
    for (var p = 0; p < parents.length; p++) {
      for (var c = parents[p].firstElementChild; c; c = c.nextElementSibling) {
        if (onPath.indexOf(c) >= 0 || SKIP.test(c.tagName) || /^haechi/.test(c.id || '')) continue;
        var sel = build(c);
        if (!safe(sel, area)) {
          var was = useClass; useClass = false;
          sel = build(c);
          useClass = was;
          if (!safe(sel, area)) continue;
        }
        if (!seen[sel]) { seen[sel] = 1; out.push(sel); }
      }
    }
    return out;
  }

  function size(el) {
    var r = el.getBoundingClientRect();
    return el.tagName.toLowerCase() + ' ' + Math.round(r.width) + '×' + Math.round(r.height);
  }

  function report() {
    marks();
    var el = stack[stack.length - 1];
    if (!el) {   // 남기기 before any area: nothing to hide yet
      H.show('');
      if (keeps.length) HaechiPicker.picked('', 0, '남길 것 ' + keeps.length + '개 · 이제 숨기기로 바꿔 숨길 영역을 누르세요', 0);
      return;
    }
    var up = stack.length > 1 ? ' (' + (stack.length - 1) + '단계 위)' : '';
    var kin = inside(), outside = keeps.length - kin.length;
    var note = outside > 0 ? ' · 영역 밖 남길 것 ' + outside + '개는 상관없음' : '';
    if (!kin.length) {   // hide the whole area; "what" is the info line shown under the buttons
      var one = build(el), n1 = H.show(one);
      HaechiPicker.picked(one, n1, '숨길 영역 ' + size(el) + up + ' · 일치 ' + n1 + '개' + note, stack.length - 1);
      return;
    }
    var rules = keepRules(el);
    var n = rules.length ? H.show(rules.join(', ')) : 0;
    HaechiPicker.picked(rules.join('\n'), n, '영역 ' + size(el) + up + ' 안에서 ' + kin.length + '개 남김 · 나머지 ' + n
      + '개 숨김 (규칙 ' + rules.length + '줄)' + note, stack.length - 1);
  }
})();
