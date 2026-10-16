(() => {
  // '파워링크' 상자: 글자가 DOM 텍스트에 없을 수 있어, 화면 위치로 찾는다
  const res = ['dpr=' + devicePixelRatio + ' w=' + innerWidth + ' h=' + innerHeight + ' scrollY=' + scrollY];
  for (const y of [250, 330, 400, 470]) {
    let e = document.elementFromPoint(205, y);
    res.push('--- y=' + y);
    for (let i = 0; e && i < 7; i++, e = e.parentElement) {
      const r = e.getBoundingClientRect();
      res.push(`${e.tagName.toLowerCase()} class="${String(e.className).slice(0, 50)}" ${Math.round(r.width)}x${Math.round(r.height)} top=${Math.round(r.top)} txt="${(e.innerText || '').replace(/\s+/g, ' ').slice(0, 30)}"`);
    }
  }
  return res.join('\n');
})()
