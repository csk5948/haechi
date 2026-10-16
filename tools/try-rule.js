(() => {
  // 클래스 이름 없이 구조만으로: '표를 감싼 링크'가 든 2칸짜리 표를 품은 상자
  const rule = 'div:has(> div > div > div > div > div > div > table > tbody > tr > td[colspan="2"] > div > a[href] > table)';
  const els = [...document.querySelectorAll(rule)];
  const out = [els.length + ' matches'];
  els.forEach(e => {
    const b = e.getBoundingClientRect();
    out.push(`  ${Math.round(b.width)}x${Math.round(b.height)} top=${Math.round(b.top + scrollY)} txt="${(e.innerText || '').replace(/\s+/g, ' ').slice(0, 50)}"`);
  });
  let s = document.getElementById('__probe_style');
  if (!s) { s = document.createElement('style'); s.id = '__probe_style'; document.head.appendChild(s); }
  s.textContent = rule + '{display:none !important}';
  return out.join('\n');
})()
