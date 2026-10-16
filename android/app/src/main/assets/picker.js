// Element picker for the in-app browser (PickerActivity). Injected after every page load.
// Tap -> the element under the finger becomes the pick; a CSS selector for it is built here and
// handed to the app through the HaechiPicker interface. Nothing is sent anywhere else.
(function () {
  if (window.__haechi) return;
  var H = window.__haechi = {};
  var catcher = null, stack = [], useClass = true;

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
      stack = [el];
      report();
    }, true);
    document.documentElement.appendChild(catcher);
  };

  H.stop = function () {
    if (catcher) { catcher.remove(); catcher = null; }
  };

  H.up = function () {
    var c = stack[stack.length - 1], p = c && c.parentElement;
    if (p && p !== document.body && p !== document.documentElement) { stack.push(p); report(); }
  };

  H.down = function () {
    if (stack.length > 1) { stack.pop(); report(); }
  };

  H.mode = function (cls) {
    useClass = cls;
    if (stack.length) report();
  };

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

  function report() {
    var el = stack[stack.length - 1];
    if (!el) return;
    var sel = build(el), n = H.show(sel), r = el.getBoundingClientRect();
    HaechiPicker.picked(sel, n, el.tagName.toLowerCase() + ' ' + Math.round(r.width) + '×' + Math.round(r.height),
      stack.length - 1);
  }
})();
