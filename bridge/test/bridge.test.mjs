import { existsSync } from 'node:fs';
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium, firefox } from 'playwright';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const bridgeScriptPath = path.resolve(__dirname, '../src/bridge.js');
const fixturesDir = path.resolve(__dirname, './fixtures');

// 1x1 transparent PNG for satisfying local fixture <img> loads offline without network access
const TINY_PNG_BUFFER = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64'
);

const BROWSER_TARGETS = [
  {
    name: 'chromium',
    launch: () =>
      chromium.launch({
        // The agent sandbox has system Chrome; elsewhere use Playwright's bundled Chromium.
        executablePath: process.env.CHROME_PATH || (existsSync('/usr/bin/google-chrome') ? '/usr/bin/google-chrome' : undefined),
        args: ['--no-sandbox', '--disable-dev-shm-usage']
      })
  },
  {
    name: 'firefox',
    launch: () =>
      firefox.launch({
        env: {
          ...process.env,
          MOZ_DISABLE_CONTENT_SANDBOX: '1',
          MOZ_DISABLE_GMP_SANDBOX: '1',
          MOZ_DISABLE_RDD_SANDBOX: '1',
          MOZ_DISABLE_SOCKET_PROCESS_SANDBOX: '1',
          MOZ_DISABLE_UTILITY_SANDBOX: '1'
        },
        firefoxUserPrefs: {
          'security.sandbox.content.level': 0,
          'media.RDD-process.enabled': false
        }
      })
  }
];

async function openFixturePage(browser, fixtureFilename) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const externalRequests = [];

  await context.route('**/*', async route => {
    const reqUrl = route.request().url();
    if (reqUrl.startsWith('file://') && reqUrl.endsWith('.html')) {
      return route.continue();
    }
    if (reqUrl.startsWith('data:')) {
      return route.continue();
    }
    // Stub image requests in fixtures locally so no real network traffic ever occurs
    if (route.request().resourceType() === 'image' || /\.(png|jpe?g|gif|svg|webp)$/i.test(reqUrl)) {
      return route.fulfill({
        status: 200,
        contentType: 'image/png',
        body: TINY_PNG_BUFFER
      });
    }
    externalRequests.push(reqUrl);
    return route.abort();
  });

  await context.addInitScript({ path: bridgeScriptPath });
  const page = await context.newPage();
  const fixtureUrl = pathToFileURL(path.join(fixturesDir, fixtureFilename)).href;
  await page.goto(fixtureUrl, { waitUntil: 'load' });

  return { context, page, externalRequests };
}

describe('Static Policy & Generalization Checks on bridge/src/bridge.js', () => {
  test('bridge.js contains zero site-specific hostnames and makes zero network calls', async () => {
    const code = await fs.readFile(bridgeScriptPath, 'utf8');
    // Strip comments before checking for banned APIs/hostnames
    const codeNoComments = code
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');

    const forbiddenHostnames = [
      'xkcd.com',
      'wikipedia.org',
      'wikimedia.org',
      'archive.org',
      'gutenberg.org',
      'openstax.org',
      'books.google.com',
      'loc.gov'
    ];
    for (const host of forbiddenHostnames) {
      assert.equal(
        codeNoComments.toLowerCase().includes(host),
        false,
        `bridge.js must not mention hostname ${host}`
      );
    }

    const forbiddenNetApis = ['fetch(', 'XMLHttpRequest', 'navigator.sendBeacon'];
    for (const api of forbiddenNetApis) {
      assert.equal(
        codeNoComments.includes(api),
        false,
        `bridge.js must not invoke network API ${api}`
      );
    }
  });
});

for (const target of BROWSER_TARGETS) {
  describe(`[${target.name}] Monarch Bridge Headless Browser Suite`, () => {
    let browser;

    before(async () => {
      browser = await target.launch();
    });

    after(async () => {
      if (browser) {
        await browser.close();
      }
    });

    test(`[${target.name}] xkcd-327 fixture: promotes comic first in reading order, exposes hover title text, skips decorative icons`, async () => {
      const { context, page, externalRequests } = await openFixturePage(browser, 'xkcd-327.html');
      try {
        const textView = await page.evaluate(() => window.__monarchBridge.exportTextView());
        assert.equal(externalRequests.length, 0, 'bridge must not issue network requests');

        // Comic must be the first button in reading order
        assert.ok(textView.buttons.length >= 1, 'Expected at least the comic button');
        const comicBtn = textView.buttons[0];
        assert.ok(
          comicBtn.label.includes('Exploits of a Mom'),
          `Expected comic label to include 'Exploits of a Mom', got: ${comicBtn.label}`
        );
        assert.equal(
          comicBtn.titleText,
          "Her daughter is named Help I'm trapped in a driver's license factory."
        );
        assert.ok(
          comicBtn.buttonText.includes("Her daughter is named Help I'm trapped in a driver's license factory."),
          `Button text must expose hover title text for Braille readers: ${comicBtn.buttonText}`
        );

        // Decorative tiny icons (favicon.png, spacer.gif) and role="presentation" (pixel.png) must be skipped
        const srcs = textView.buttons.map(b => b.src || '');
        assert.equal(srcs.some(s => s.includes('favicon.png')), false);
        assert.equal(srcs.some(s => s.includes('spacer.gif')), false);
        assert.equal(srcs.some(s => s.includes('pixel.png')), false);

        // Original <img> stays in DOM with aria-hidden="true" and кнопки are focusable
        const origAriaHidden = await page.getAttribute('#comic-img', 'aria-hidden');
        assert.equal(origAriaHidden, 'true');
        await page.focus('button.monarch-tactile-btn');
        const focusedClass = await page.evaluate(() => document.activeElement?.className);
        assert.equal(focusedClass, 'monarch-tactile-btn');
      } finally {
        await context.close();
      }
    });

    test(`[${target.name}] wikipedia-heart fixture: thumbnail caption div, filename fallback, figcaption, >=35 lazy images, skips padlock & <32px logos`, async () => {
      const { context, page } = await openFixturePage(browser, 'wikipedia-heart.html');
      try {
        const textView = await page.evaluate(() => window.__monarchBridge.exportTextView());
        assert.ok(
          textView.buttons.length >= 35,
          `Expected at least 35 buttons on wikipedia-heart fixture, got ${textView.buttons.length}`
        );

        // 1. Thumbnail block with empty alt="" and caption in .thumbcaption
        const thumbBtn = textView.buttons.find(b =>
          (b.src || '').includes('Diagram_of_the_human_heart_(cropped).svg')
        );
        assert.ok(thumbBtn, 'Expected button for Diagram_of_the_human_heart_(cropped).svg thumbnail');
        for (const word of ['heart', 'valves', 'arteries', 'veins']) {
          assert.ok(
            thumbBtn.label.toLowerCase().includes(word),
            `Expected thumbnail button label '${thumbBtn.label}' to include '${word}'`
          );
        }

        // 2. Infobox image with no alt and no caption -> falls back to humanized filename
        const infoboxBtn = textView.buttons.find(b =>
          (b.src || '').includes('Heart_anterior_exterior_view.png')
        );
        assert.ok(infoboxBtn, 'Expected button for infobox Heart_anterior_exterior_view.png');
        assert.equal(infoboxBtn.label, 'Heart anterior exterior view');

        // 3. <figure> with <figcaption> "Human heart during an autopsy"
        const autopsyBtn = textView.buttons.find(b => b.label.toLowerCase().includes('autopsy'));
        assert.ok(autopsyBtn, 'Expected button for autopsy figure');

        // 4. Padlock and <32px Commons/Wikibooks icons must be skipped
        for (const btn of textView.buttons) {
          const s = (btn.src || '').toLowerCase();
          assert.equal(s.includes('padlock'), false, 'Must skip protection padlock');
          assert.equal(s.includes('commons-logo'), false, 'Must skip <32px Commons logo');
          assert.equal(s.includes('wikibooks-logo'), false, 'Must skip <32px Wikibooks logo');
        }

        // 5. Clicking a lazy-loaded image button promotes loading="eager" and populates src from data-src
        const lazyCheck = await page.evaluate(() => {
          const lazyBtn = [...document.querySelectorAll('button.monarch-tactile-btn')].find(b =>
            (b.getAttribute('data-monarch-label') || '').includes('Cardiac cycle diagram 1')
          );
          lazyBtn.click();
          const targetId = lazyBtn.getAttribute('data-monarch-target-id');
          const img = document.querySelector(`[data-monarch-id="${targetId}"]`);
          return {
            loading: img.getAttribute('loading'),
            src: img.getAttribute('src'),
            lastCapture: window.__monarchBridge.lastActivatedCapture
          };
        });
        assert.equal(lazyCheck.loading, 'eager');
        assert.equal(lazyCheck.src, 'gallery/cardiac_cycle_phase_1.png');
        assert.equal(lazyCheck.lastCapture.lazyLoadedTriggered, true);
      } finally {
        await context.close();
      }
    });

    test(`[${target.name}] books fixture: Gutenberg drop-cap merged into 'Alice', OpenStax long alt in description, Archive/Google Books page labels + dynamic lazy pages`, async () => {
      const { context, page } = await openFixturePage(browser, 'gutenberg-openstax-books.html');
      try {
        const textView = await page.evaluate(() => window.__monarchBridge.exportTextView());

        // 1. Gutenberg drop-cap image ('A') must NOT become a button, and paragraph must read 'Alice...'
        const dropCapBtn = textView.buttons.find(b => (b.src || '').endsWith('a.png'));
        assert.equal(dropCapBtn, undefined, 'Drop-cap image a.png must not become a button');
        const paraText = await page.textContent('#opening-para');
        assert.ok(
          paraText.trim().startsWith('Alice was beginning'),
          `Expected paragraph to read as one word 'Alice...', got: '${paraText.trim()}'`
        );

        // 2. Gutenberg illustrations ('Advice from a Caterpillar', 'Cheshire cat') promoted
        assert.ok(
          textView.buttons.some(b => b.label.includes('Advice from a Caterpillar')),
          'Expected Caterpillar button'
        );
        assert.ok(
          textView.buttons.some(b => b.label.includes('Cheshire cat')),
          'Expected Cheshire cat button'
        );

        // 3. OpenStax Figure 40.11: label from concise <figcaption>, long alt stored in description
        const openstaxBtn = textView.buttons.find(b => (b.src || '').includes('fig40_11.webp'));
        assert.ok(openstaxBtn, 'Expected OpenStax Figure 40.11 button');
        assert.ok(openstaxBtn.label.includes('Figure 40.11'));
        assert.ok(openstaxBtn.label.toLowerCase().includes('heart'));
        assert.ok(
          openstaxBtn.description && openstaxBtn.description.includes('superior vena cava'),
          'Long alt text must be offered as the description rather than crammed into the button label'
        );

        // 4. Archive.org BookReader page scan includes 'page 8' and book title
        const archiveBtn = textView.buttons.find(b => (b.src || '').includes('n34_w800.jpg'));
        assert.ok(archiveBtn, 'Expected Archive.org page scan button');
        assert.ok(
          archiveBtn.label.toLowerCase().includes('page 8'),
          `Expected archive button label to include 'page 8', got: ${archiveBtn.label}`
        );

        // 5. Google Books continuous scroll reader ('page 2 of 3 loaded')
        const gbPage8Btn = textView.buttons.find(b => (b.src || '').includes('page8_PA8.png'));
        assert.ok(gbPage8Btn, 'Expected Google Books PA8 button');
        assert.equal(gbPage8Btn.label, 'Page (page 2 of 3 loaded)');

        // 6. Dynamically appended lazy page image automatically gets a button via MutationObserver
        await page.evaluate(() => {
          const reader = document.getElementById('continuous-reader');
          const newImg = document.createElement('img');
          newImg.id = 'gb-page-10';
          newImg.src = 'page10.png';
          newImg.width = 400;
          newImg.height = 600;
          newImg.alt = 'Newly scrolled page 10';
          reader.appendChild(newImg);
        });
        await page.waitForSelector('button.monarch-tactile-btn[data-monarch-src="page10.png"]');
        const updatedView = await page.evaluate(() => window.__monarchBridge.exportTextView());
        assert.ok(updatedView.buttons.some(b => b.src === 'page10.png'));
      } finally {
        await context.close();
      }
    });

    test(`[${target.name}] svg-and-canvas fixture: captures inline SVG XML + components and document-start Canvas2D draw calls + text`, async () => {
      const { context, page } = await openFixturePage(browser, 'svg-and-canvas.html');
      try {
        const result = await page.evaluate(() => {
          const buttons = window.__monarchBridge.exportTextView().buttons;
          const svgBtn = buttons.find(b => b.label.includes('four heart chambers'));
          const canvasBtn = buttons.find(b => b.label.includes('Four-panel comic canvas'));
          const ariaBtn = buttons.find(b => b.label.includes('equilateral triangle'));

          return {
            buttonCount: buttons.length,
            svgCapture: svgBtn ? window.__monarchBridge.captureElementGeometry(svgBtn.targetId) : null,
            canvasCapture: canvasBtn ? window.__monarchBridge.captureElementGeometry(canvasBtn.targetId) : null,
            ariaCapture: ariaBtn ? window.__monarchBridge.captureElementGeometry(ariaBtn.targetId) : null
          };
        });

        assert.equal(result.buttonCount, 3, 'Expected SVG, Canvas, and role="img" buttons');

        // 1. Verify inline SVG capture
        assert.ok(result.svgCapture, 'Expected SVG geometry capture');
        assert.equal(result.svgCapture.geometryType, 'svg');
        assert.deepEqual(result.svgCapture.sourceSize, [200, 200]);
        assert.ok(result.svgCapture.svgXml.includes('<svg'));
        assert.ok(result.svgCapture.svgXml.includes('Right atrium'));
        const svgLabels = result.svgCapture.components.map(c => c.label);
        for (const expected of [
          'Pericardium',
          'Right atrium',
          'Left atrium',
          'Right ventricle',
          'Left ventricle',
          'Septum'
        ]) {
          assert.ok(
            svgLabels.includes(expected),
            `Expected SVG component '${expected}' in [${svgLabels.join(', ')}]`
          );
        }

        // 2. Verify Canvas2D draw-call capture (intercepted at document start)
        assert.ok(result.canvasCapture, 'Expected Canvas2D geometry capture');
        assert.equal(result.canvasCapture.geometryType, 'canvas2d');
        assert.deepEqual(result.canvasCapture.sourceSize, [400, 200]);
        // 4 shape draw operations + 1 text operation = 5 (since clearRect wiped the initial scratch stroke and full-canvas fillRect was ignored)
        assert.equal(result.canvasCapture.operationCount, 5);
        assert.equal(result.canvasCapture.strokes.length, 4);

        const textComponents = result.canvasCapture.components.filter(c => c.role === 'text');
        assert.equal(textComponents.length, 1);
        assert.ok(textComponents[0].embeddedText.includes('DROP TABLE Students'));
        assert.equal(textComponents[0].strokes.length, 0, 'Text components must have no strokes');

        const objectComponents = result.canvasCapture.components.filter(c => c.role === 'object');
        assert.equal(objectComponents.length, 4);
        // Panel 1 box: strokeRect(10, 10, 175, 180) -> [10, 10, 185, 190]
        assert.deepEqual(objectComponents[0].box, [10, 10, 185, 190]);
        // Panel 2 box: translate(205, 10) + rect(0, 0, 185, 180) -> [205, 10, 390, 190]
        assert.deepEqual(objectComponents[1].box, [205, 10, 390, 190]);

        // 3. Verify role="img" promotion
        assert.ok(result.ariaCapture);
        assert.equal(result.ariaCapture.label, 'Warning symbol: equilateral triangle');
      } finally {
        await context.close();
      }
    });

    test(`[${target.name}] promoteImages idempotence, 2048 Canvas2D command cap, and data:image/svg+xml URI capture`, async () => {
      const { context, page } = await openFixturePage(browser, 'svg-and-canvas.html');
      try {
        const check = await page.evaluate(() => {
          const firstCount = window.__monarchBridge.promoteImages().length;
          const secondCount = window.__monarchBridge.promoteImages().length;
          const domBtnCount = document.querySelectorAll('button.monarch-tactile-btn').length;

          // Test data:image/svg+xml <img>
          const dataImg = document.createElement('img');
          dataImg.width = 48;
          dataImg.height = 48;
          dataImg.alt = 'Data URI red square';
          dataImg.src =
            "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='40' height='40'%3E%3Crect width='40' height='40' fill='red'/%3E%3C/svg%3E";
          document.body.appendChild(dataImg);
          window.__monarchBridge.promoteImages();
          const dataTargetId = dataImg.getAttribute('data-monarch-id');
          const dataCapture = window.__monarchBridge.captureElementGeometry(dataTargetId);

          // Test 2048 command cap on animated canvas
          const animCanvas = document.createElement('canvas');
          animCanvas.width = 200;
          animCanvas.height = 200;
          animCanvas.setAttribute('aria-label', 'Animated stress canvas');
          document.body.appendChild(animCanvas);
          const ctx = animCanvas.getContext('2d');
          for (let i = 0; i < 3000; i++) {
            ctx.strokeRect(i % 100, (i * 3) % 100, 20, 20);
          }
          window.__monarchBridge.promoteImages();
          const animTargetId = animCanvas.getAttribute('data-monarch-id');
          const animCapture = window.__monarchBridge.captureElementGeometry(animTargetId);

          return {
            firstCount,
            secondCount,
            domBtnCount,
            dataCapture,
            animOperationCount: animCapture.operationCount
          };
        });

        assert.equal(check.firstCount, 3);
        assert.equal(check.secondCount, 3);
        assert.equal(check.domBtnCount, 3);
        assert.equal(check.dataCapture.geometryType, 'svg-data-uri');
        assert.ok(check.dataCapture.svgXml.includes('<rect'));
        assert.equal(check.animOperationCount, 2048, 'Canvas2D log must be capped at 2048 operations');
      } finally {
        await context.close();
      }
    });

    test(`[${target.name}] engine-test/test.html compatibility: promotes img[alt], svg[aria-labelledby], and div[role="img"]`, async () => {
      const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
      try {
        await context.route('https://**/*', route => route.abort());
        await context.addInitScript({ path: bridgeScriptPath });
        const page = await context.newPage();
        const engineTestHtml = pathToFileURL(
          path.resolve(__dirname, '../../engine-test/app/src/main/assets/test.html')
        ).href;
        await page.goto(engineTestHtml, { waitUntil: 'domcontentloaded' });

        const textView = await page.evaluate(() => window.__monarchBridge.exportTextView());
        const labels = textView.buttons.map(b => b.label);
        assert.deepEqual(labels, [
          'Image alt text: a red square',
          'SVG title: a blue circle',
          'ARIA image: a green triangle'
        ]);
      } finally {
        await context.close();
      }
    });
  });
}
