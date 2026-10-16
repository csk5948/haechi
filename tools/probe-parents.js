(() => {
  const box = document.querySelector('div:has(> div > div > div > div > table > tbody > tr > td[colspan="2"] > div > a[href] > table)');
  const out = [];
  let e = box;
  for (let i = 0; e && i < 6; i++, e = e.parentElement) {
    const cs = getComputedStyle(e);
    out.push(`${i}: ${e.tagName.toLowerCase()} class="${e.className}" disp=${cs.display} h=${cs.height} minH=${cs.minHeight} pad=${cs.padding} mar=${cs.margin} kids=${[...e.children].map(c => c.tagName.toLowerCase() + ':' + getComputedStyle(c).height).join(',')}`);
  }
  return out.join('\n');
})()
