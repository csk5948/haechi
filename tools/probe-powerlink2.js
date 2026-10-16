(() => {
  const e0 = document.elementFromPoint(205, 400).closest('a');
  const out = ['a attrs: ' + [...e0.attributes].map(a => a.name + '=' + a.value.slice(0, 50)).join(' ')];
  let e = e0;
  for (let i = 0; e && i < 7; i++, e = e.parentElement) {
    const r = e.getBoundingClientRect();
    const kids = [...e.children].map(c => c.tagName.toLowerCase() + (c.childElementCount ? '(' + c.childElementCount + ')' : '')).join(',');
    out.push(`${i}: ${e.tagName.toLowerCase()} class="${e.className}" ${Math.round(r.width)}x${Math.round(r.height)} attrs=[${[...e.attributes].filter(a => a.name !== 'class').map(a => a.name).join(' ')}] kids=[${kids.slice(0, 120)}]`);
  }
  // 상자 머리('파워링크' 글자/이미지) 후보
  const box = e0.parentElement.parentElement.parentElement;
  const first = box.querySelector('*');
  out.push('first child html: ' + box.firstElementChild.outerHTML.slice(0, 400));
  const cs = getComputedStyle(box.firstElementChild, '::before');
  out.push('::before content: ' + cs.content);
  return out.join('\n');
})()
