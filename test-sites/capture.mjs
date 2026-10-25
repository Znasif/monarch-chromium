// Capture local, git-ignored snapshots of the test pages listed in cases/*.json.
// Usage: node capture.mjs [case-id ...]   (no ids = all cases)
// Each page is fetched once per run, only when a human starts the run.
import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';

const casesDir = new URL('./cases/', import.meta.url).pathname;
const outRoot = new URL('./snapshots/', import.meta.url).pathname;

const ids = process.argv.slice(2);
const files = (await fs.readdir(casesDir)).filter(f => f.endsWith('.json'));
const cases = [];
for (const f of files) {
  const c = JSON.parse(await fs.readFile(path.join(casesDir, f), 'utf8'));
  if (!ids.length || ids.includes(c.id)) cases.push(c);
}

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });

for (const c of cases) {
  const out = path.join(outRoot, c.id);
  await fs.mkdir(path.join(out, 'images'), { recursive: true });
  const page = await context.newPage();
  await page.goto(c.url, { waitUntil: 'networkidle', timeout: 60000 });

  const cdp = await context.newCDPSession(page);
  const { data } = await cdp.send('Page.captureSnapshot', { format: 'mhtml' });
  await fs.writeFile(path.join(out, 'page.mhtml'), data);
  await page.screenshot({ path: path.join(out, 'page.png'), fullPage: true });

  // Inventory of image-like elements, used to write and check expectations.
  const inventory = await page.evaluate(() => {
    const label = el => el.getAttribute('alt') ?? el.getAttribute('aria-label') ??
      el.querySelector?.('title')?.textContent ?? el.getAttribute('title') ?? '';
    return [...document.querySelectorAll('img, svg, canvas, [role="img"]')]
      .filter(el => !el.closest('svg') || el.tagName.toLowerCase() === 'svg' && !el.parentElement.closest('svg'))
      .map((el, i) => {
        const r = el.getBoundingClientRect();
        return {
          i, tag: el.tagName.toLowerCase(), src: el.currentSrc || el.src || null,
          label: label(el).trim().slice(0, 200), title: el.getAttribute('title'),
          figcaption: el.closest('figure')?.querySelector('figcaption')?.textContent.trim().slice(0, 200) ?? null,
          natural: el.naturalWidth ? [el.naturalWidth, el.naturalHeight] : null,
          box: [Math.round(r.x), Math.round(r.y + scrollY), Math.round(r.width), Math.round(r.height)],
        };
      });
  });
  await fs.writeFile(path.join(out, 'inventory.json'), JSON.stringify(inventory, null, 2));

  // Save the images named in the case so tactile tests can run offline.
  for (const [name, src] of Object.entries(c.images ?? {})) {
    const res = await context.request.get(src);
    await fs.writeFile(path.join(out, 'images', name), await res.body());
  }
  await cdp.detach();
  await page.close();
  console.log(`${c.id}: ${inventory.length} image-like elements, ${Object.keys(c.images ?? {}).length} images saved`);
}
await browser.close();
