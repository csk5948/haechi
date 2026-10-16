(() => {
  // List-KR 의 namu.wiki 순수 CSS 규칙 두 개를 그대로 넣어 보고, 몇 개가 숨겨지는지 센다
  const rules = [
    'div[class] > div[class*=" "] > div[class*=" "] ~ div[class*=" "] > div[id][class]:not([class*=" "]):empty',
    'div[class*=" "]:not([class^="_"]) > div[class]:not([class*=" "]) > div[class*=" "]:has(> div[id][class] > div[id][class])',
  ];
  const out = [];
  for (const r of rules) {
    const els = [...document.querySelectorAll(r)];
    out.push(els.length + '개 ← ' + r.slice(0, 60));
    els.forEach(e => {
      const b = e.getBoundingClientRect();
      out.push(`   ${Math.round(b.width)}x${Math.round(b.height)} top=${Math.round(b.top + scrollY)}`);
    });
  }
  let s = document.getElementById('__probe_style');
  if (!s) { s = document.createElement('style'); s.id = '__probe_style'; document.head.appendChild(s); }
  s.textContent = rules.map(r => r + '{display:none !important}').join('\n');
  return out.join('\n');
})()
