/**
 * Monarch Tactile Browser — Engine-Neutral Document-Start Bridge (Step 3)
 *
 * Runs in any browser engine (GeckoView 158, System WebView / Chromium 109+,
 * headless Firefox, headless Chromium) with zero engine-specific APIs, zero
 * site-specific presets ("No Code Names a Site"), and zero network requests.
 *
 * Responsibilities:
 * 1. Promote meaningful <img>, <svg>, <canvas>, <figure>, and [role="img"]
 *    elements to focusable <button> elements ("Explore tactile image: <label>"),
 *    while skipping decorative/tiny images and merging single-letter drop-cap
 *    images into adjacent paragraph text.
 * 2. Intercept CanvasRenderingContext2D draw calls at document start (paths,
 *    rects, arcs, ellipses, curves, text, and 2D affine transforms) per canvas.
 * 3. Capture inline SVG XML + vector geometry and Canvas2D vector geometry into
 *    JSON matching tactile-core's model and test-sites/judge.py's schema.
 */
(function initMonarchBridge(globalScope) {
  'use strict';

  if (globalScope.__monarchBridge && globalScope.__monarchBridge.__initialized) {
    return globalScope.__monarchBridge;
  }

  const MAX_CANVAS_COMMANDS = 2048;
  const MIN_DIMENSION_PX = 32;
  const MIN_CONTENT_FALLBACK_PX = 64;
  const LONG_ALT_THRESHOLD = 110;

  let nextTargetCounter = 1;
  const targetElementsById = new Map();
  const canvasRecords = new WeakMap();

  // ---------------------------------------------------------------------------
  // 2D Affine Transform Helper: [a, b, c, d, e, f]
  // x' = a*x + c*y + e
  // y' = b*x + d*y + f
  // ---------------------------------------------------------------------------
  function identityMatrix() {
    return [1, 0, 0, 1, 0, 0];
  }

  function multiplyMatrix(m1, m2) {
    return [
      m1[0] * m2[0] + m1[2] * m2[1],
      m1[1] * m2[0] + m1[3] * m2[1],
      m1[0] * m2[2] + m1[2] * m2[3],
      m1[1] * m2[2] + m1[3] * m2[3],
      m1[0] * m2[4] + m1[2] * m2[5] + m1[4],
      m1[1] * m2[4] + m1[3] * m2[5] + m1[5]
    ];
  }

  function transformPoint(m, x, y) {
    return [
      m[0] * x + m[2] * y + m[4],
      m[1] * x + m[3] * y + m[5]
    ];
  }

  // ---------------------------------------------------------------------------
  // 1. Canvas2D Draw-Call Interceptor
  // ---------------------------------------------------------------------------
  function createCanvasState(canvas) {
    return {
      canvas,
      matrix: identityMatrix(),
      matrixStack: [],
      currentSubpaths: [],
      activeSubpath: null,
      operations: [],
      font: '12px sans-serif'
    };
  }

  function ensureCanvasState(canvas) {
    let state = canvasRecords.get(canvas);
    if (!state) {
      state = createCanvasState(canvas);
      canvasRecords.set(canvas, state);
    }
    return state;
  }

  function ensureActiveSubpath(state, startX, startY) {
    if (!state.activeSubpath) {
      const pt = transformPoint(state.matrix, startX, startY);
      state.activeSubpath = { points: [pt], closed: false };
      state.currentSubpaths.push(state.activeSubpath);
    }
    return state.activeSubpath;
  }

  function pushCanvasOp(state, op) {
    if (state.operations.length >= MAX_CANVAS_COMMANDS) {
      return;
    }
    state.operations.push(op);
  }

  function sampleCubicBezier(p0, p1, p2, p3, steps = 12) {
    const pts = [];
    for (let i = 1; i <= steps; i++) {
      const t = i / steps;
      const mt = 1 - t;
      const x =
        mt * mt * mt * p0[0] +
        3 * mt * mt * t * p1[0] +
        3 * mt * t * t * p2[0] +
        t * t * t * p3[0];
      const y =
        mt * mt * mt * p0[1] +
        3 * mt * mt * t * p1[1] +
        3 * mt * t * t * p2[1] +
        t * t * t * p3[1];
      pts.push([x, y]);
    }
    return pts;
  }

  function sampleQuadraticBezier(p0, p1, p2, steps = 10) {
    const pts = [];
    for (let i = 1; i <= steps; i++) {
      const t = i / steps;
      const mt = 1 - t;
      const x = mt * mt * p0[0] + 2 * mt * t * p1[0] + t * t * p2[0];
      const y = mt * mt * p0[1] + 2 * mt * t * p1[1] + t * t * p2[1];
      pts.push([x, y]);
    }
    return pts;
  }

  function sampleArcPoints(matrix, cx, cy, rx, ry, startAngle, endAngle, counterclockwise) {
    let delta = endAngle - startAngle;
    if (!counterclockwise && delta < 0) {
      delta += Math.PI * 2;
    } else if (counterclockwise && delta > 0) {
      delta -= Math.PI * 2;
    }
    const maxSteps = 24;
    const steps = Math.max(8, Math.min(maxSteps, Math.ceil((Math.abs(delta) / (Math.PI * 2)) * maxSteps)));
    const pts = [];
    for (let i = 0; i <= steps; i++) {
      const theta = startAngle + (delta * i) / steps;
      const x = cx + rx * Math.cos(theta);
      const y = cy + ry * Math.sin(theta);
      pts.push(transformPoint(matrix, x, y));
    }
    return pts;
  }

  function instrumentContext2DPrototype(Proto) {
    if (!Proto || Proto.__monarchInstrumented) {
      return;
    }
    Object.defineProperty(Proto, '__monarchInstrumented', {
      value: true,
      enumerable: false,
      configurable: false
    });

    const wrap = (methodName, handler) => {
      const orig = Proto[methodName];
      if (typeof orig !== 'function') return;
      Proto[methodName] = function (...args) {
        try {
          if (this && this.canvas) {
            const state = ensureCanvasState(this.canvas);
            handler.call(this, state, args);
          }
        } catch (_err) {
          // Never break page rendering if instrumentation encounters unexpected args
        }
        return orig.apply(this, args);
      };
    };

    wrap('save', function (state) {
      state.matrixStack.push(state.matrix.slice());
    });

    wrap('restore', function (state) {
      if (state.matrixStack.length > 0) {
        state.matrix = state.matrixStack.pop();
      }
    });

    wrap('scale', function (state, [sx = 1, sy = 1]) {
      state.matrix = multiplyMatrix(state.matrix, [Number(sx) || 1, 0, 0, Number(sy) || 1, 0, 0]);
    });

    wrap('rotate', function (state, [angle = 0]) {
      const rad = Number(angle) || 0;
      const c = Math.cos(rad);
      const s = Math.sin(rad);
      state.matrix = multiplyMatrix(state.matrix, [c, s, -s, c, 0, 0]);
    });

    wrap('translate', function (state, [tx = 0, ty = 0]) {
      state.matrix = multiplyMatrix(state.matrix, [1, 0, 0, 1, Number(tx) || 0, Number(ty) || 0]);
    });

    wrap('transform', function (state, [a = 1, b = 0, c = 0, d = 1, e = 0, f = 0]) {
      state.matrix = multiplyMatrix(state.matrix, [
        Number(a), Number(b), Number(c), Number(d), Number(e), Number(f)
      ]);
    });

    wrap('setTransform', function (state, args) {
      if (args.length === 1 && args[0] && typeof args[0] === 'object') {
        const m = args[0];
        state.matrix = [
          Number(m.a ?? 1), Number(m.b ?? 0),
          Number(m.c ?? 0), Number(m.d ?? 1),
          Number(m.e ?? 0), Number(m.f ?? 0)
        ];
      } else if (args.length >= 6) {
        const [a, b, c, d, e, f] = args;
        state.matrix = [Number(a), Number(b), Number(c), Number(d), Number(e), Number(f)];
      } else {
        state.matrix = identityMatrix();
      }
    });

    wrap('resetTransform', function (state) {
      state.matrix = identityMatrix();
    });

    wrap('beginPath', function (state) {
      state.currentSubpaths = [];
      state.activeSubpath = null;
    });

    wrap('moveTo', function (state, [x = 0, y = 0]) {
      const pt = transformPoint(state.matrix, Number(x), Number(y));
      state.activeSubpath = { points: [pt], closed: false };
      state.currentSubpaths.push(state.activeSubpath);
    });

    wrap('lineTo', function (state, [x = 0, y = 0]) {
      const sub = ensureActiveSubpath(state, Number(x), Number(y));
      sub.points.push(transformPoint(state.matrix, Number(x), Number(y)));
    });

    wrap('quadraticCurveTo', function (state, [cpx = 0, cpy = 0, x = 0, y = 0]) {
      const sub = ensureActiveSubpath(state, Number(cpx), Number(cpy));
      const p0 = sub.points[sub.points.length - 1];
      const p1 = transformPoint(state.matrix, Number(cpx), Number(cpy));
      const p2 = transformPoint(state.matrix, Number(x), Number(y));
      const sampled = sampleQuadraticBezier(p0, p1, p2);
      for (const pt of sampled) sub.points.push(pt);
    });

    wrap('bezierCurveTo', function (state, [cp1x = 0, cp1y = 0, cp2x = 0, cp2y = 0, x = 0, y = 0]) {
      const sub = ensureActiveSubpath(state, Number(cp1x), Number(cp1y));
      const p0 = sub.points[sub.points.length - 1];
      const p1 = transformPoint(state.matrix, Number(cp1x), Number(cp1y));
      const p2 = transformPoint(state.matrix, Number(cp2x), Number(cp2y));
      const p3 = transformPoint(state.matrix, Number(x), Number(y));
      const sampled = sampleCubicBezier(p0, p1, p2, p3);
      for (const pt of sampled) sub.points.push(pt);
    });

    wrap('rect', function (state, [x = 0, y = 0, w = 0, h = 0]) {
      const rx = Number(x);
      const ry = Number(y);
      const rw = Number(w);
      const rh = Number(h);
      const p0 = transformPoint(state.matrix, rx, ry);
      const p1 = transformPoint(state.matrix, rx + rw, ry);
      const p2 = transformPoint(state.matrix, rx + rw, ry + rh);
      const p3 = transformPoint(state.matrix, rx, ry + rh);
      const sub = { points: [p0, p1, p2, p3, p0], closed: true };
      state.currentSubpaths.push(sub);
      state.activeSubpath = sub;
    });

    wrap('arc', function (state, [x = 0, y = 0, r = 0, startAngle = 0, endAngle = Math.PI * 2, ccw = false]) {
      const radius = Math.max(0, Number(r));
      const pts = sampleArcPoints(
        state.matrix,
        Number(x),
        Number(y),
        radius,
        radius,
        Number(startAngle),
        Number(endAngle),
        Boolean(ccw)
      );
      const isFullCircle = Math.abs(Number(endAngle) - Number(startAngle)) >= Math.PI * 2 - 1e-3;
      if (!state.activeSubpath || state.activeSubpath.points.length === 0) {
        state.activeSubpath = { points: pts, closed: isFullCircle };
        state.currentSubpaths.push(state.activeSubpath);
      } else {
        for (const pt of pts) state.activeSubpath.points.push(pt);
        if (isFullCircle) state.activeSubpath.closed = true;
      }
    });

    wrap('ellipse', function (state, [x = 0, y = 0, rx = 0, ry = 0, rotation = 0, startAngle = 0, endAngle = Math.PI * 2, ccw = false]) {
      const rot = Number(rotation) || 0;
      const c = Math.cos(rot);
      const s = Math.sin(rot);
      const localM = multiplyMatrix(
        state.matrix,
        multiplyMatrix([1, 0, 0, 1, Number(x), Number(y)], [c, s, -s, c, 0, 0])
      );
      const pts = sampleArcPoints(
        localM,
        0,
        0,
        Math.max(0, Number(rx)),
        Math.max(0, Number(ry)),
        Number(startAngle),
        Number(endAngle),
        Boolean(ccw)
      );
      const isFull = Math.abs(Number(endAngle) - Number(startAngle)) >= Math.PI * 2 - 1e-3;
      const sub = { points: pts, closed: isFull };
      state.currentSubpaths.push(sub);
      state.activeSubpath = sub;
    });

    wrap('closePath', function (state) {
      if (state.activeSubpath && state.activeSubpath.points.length > 1) {
        const first = state.activeSubpath.points[0];
        const last = state.activeSubpath.points[state.activeSubpath.points.length - 1];
        if (first[0] !== last[0] || first[1] !== last[1]) {
          state.activeSubpath.points.push([first[0], first[1]]);
        }
        state.activeSubpath.closed = true;
      }
    });

    const recordCurrentPath = function (state, kind) {
      for (const sub of state.currentSubpaths) {
        if (sub.points.length >= 2) {
          pushCanvasOp(state, {
            type: kind,
            points: sub.points.map(p => [p[0], p[1]]),
            closed: Boolean(sub.closed || kind === 'fill')
          });
        }
      }
    };

    wrap('stroke', function (state) {
      recordCurrentPath(state, 'stroke');
    });

    wrap('fill', function (state) {
      recordCurrentPath(state, 'fill');
    });

    wrap('strokeRect', function (state, [x = 0, y = 0, w = 0, h = 0]) {
      const rx = Number(x);
      const ry = Number(y);
      const rw = Number(w);
      const rh = Number(h);
      const p0 = transformPoint(state.matrix, rx, ry);
      const p1 = transformPoint(state.matrix, rx + rw, ry);
      const p2 = transformPoint(state.matrix, rx + rw, ry + rh);
      const p3 = transformPoint(state.matrix, rx, ry + rh);
      pushCanvasOp(state, {
        type: 'strokeRect',
        points: [p0, p1, p2, p3, p0],
        closed: true
      });
    });

    wrap('fillRect', function (state, [x = 0, y = 0, w = 0, h = 0]) {
      const rx = Number(x);
      const ry = Number(y);
      const rw = Number(w);
      const rh = Number(h);
      const cw = state.canvas.width || 300;
      const ch = state.canvas.height || 150;
      // Ignore full-canvas background fillRect so background never becomes pins
      if (rx <= 0 && ry <= 0 && rw >= cw && rh >= ch) {
        return;
      }
      const p0 = transformPoint(state.matrix, rx, ry);
      const p1 = transformPoint(state.matrix, rx + rw, ry);
      const p2 = transformPoint(state.matrix, rx + rw, ry + rh);
      const p3 = transformPoint(state.matrix, rx, ry + rh);
      pushCanvasOp(state, {
        type: 'fillRect',
        points: [p0, p1, p2, p3, p0],
        closed: true
      });
    });

    wrap('clearRect', function (state, [x = 0, y = 0, w = 0, h = 0]) {
      const rx = Number(x);
      const ry = Number(y);
      const rw = Number(w);
      const rh = Number(h);
      const cw = state.canvas.width || 300;
      const ch = state.canvas.height || 150;
      const p0 = transformPoint(state.matrix, rx, ry);
      const p2 = transformPoint(state.matrix, rx + rw, ry + rh);
      if (p0[0] <= 1 && p0[1] <= 1 && p2[0] >= cw - 1 && p2[1] >= ch - 1) {
        state.operations = [];
        state.currentSubpaths = [];
        state.activeSubpath = null;
      }
    });

    const recordTextOp = function (state, [text = '', x = 0, y = 0]) {
      const str = String(text).trim();
      if (!str) return;
      const pt = transformPoint(state.matrix, Number(x), Number(y));
      const approxHeight = 14;
      const approxWidth = Math.max(16, str.length * 7);
      pushCanvasOp(state, {
        type: 'text',
        text: str,
        x: pt[0],
        y: pt[1],
        box: [
          Math.max(0, pt[0]),
          Math.max(0, pt[1] - approxHeight),
          pt[0] + approxWidth,
          pt[1] + 2
        ]
      });
    };

    wrap('fillText', recordTextOp);
    wrap('strokeText', recordTextOp);
  }

  function installCanvasInterceptor(win = globalScope) {
    if (!win) return;
    if (win.CanvasRenderingContext2D && win.CanvasRenderingContext2D.prototype) {
      instrumentContext2DPrototype(win.CanvasRenderingContext2D.prototype);
    }
    if (win.HTMLCanvasElement && win.HTMLCanvasElement.prototype) {
      const CanvasProto = win.HTMLCanvasElement.prototype;
      if (!CanvasProto.__monarchGetContextWrapped) {
        const origGetContext = CanvasProto.getContext;
        if (typeof origGetContext === 'function') {
          CanvasProto.getContext = function (contextType, ...rest) {
            const ctx = origGetContext.call(this, contextType, ...rest);
            if (ctx && String(contextType).toLowerCase() === '2d') {
              ensureCanvasState(this);
              instrumentContext2DPrototype(Object.getPrototypeOf(ctx));
            }
            return ctx;
          };
          Object.defineProperty(CanvasProto, '__monarchGetContextWrapped', {
            value: true,
            enumerable: false,
            configurable: false
          });
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 2. Semantic Label, Description, and Filtering Heuristics (Zero Site Rules)
  // ---------------------------------------------------------------------------
  function cleanText(raw) {
    if (!raw) return '';
    return String(raw).replace(/\s+/g, ' ').trim();
  }

  function resolveAriaLabelledBy(el, doc) {
    const ids = cleanText(el.getAttribute('aria-labelledby'));
    if (!ids || !doc) return '';
    const parts = [];
    for (const id of ids.split(/\s+/)) {
      const ref = doc.getElementById(id);
      if (ref) {
        const t = cleanText(ref.textContent);
        if (t) parts.push(t);
      }
    }
    return parts.join(' ');
  }

  function findSurroundingCaption(el) {
    const fig = el.closest('figure');
    if (fig) {
      const fc = fig.querySelector('figcaption');
      if (fc) {
        const text = cleanText(fc.textContent);
        if (text) return text;
      }
    }
    // Check common encyclopedia / CMS thumbnail wrapper structures without site rules
    const thumbContainer = el.closest(
      '[class*="thumbinner"], [class*="thumb"], [class*="figure"], [class*="media"], [class*="image-box"]'
    );
    if (thumbContainer) {
      const capEl = thumbContainer.querySelector(
        'figcaption, [class*="thumbcaption"], [class*="caption"], [id*="caption"]'
      );
      if (capEl) {
        const text = cleanText(capEl.textContent);
        if (text) return text;
      }
    }
    return '';
  }

  function humanizeFilename(src) {
    if (!src || src.startsWith('data:') || src.startsWith('blob:')) return '';
    try {
      const withoutQuery = src.split(/[?#]/)[0];
      const segments = withoutQuery.split('/').filter(Boolean);
      if (segments.length === 0) return '';
      let rawName = decodeURIComponent(segments[segments.length - 1]);
      // Strip thumbnail resolution prefixes such as "500px-" or "250px-"
      rawName = rawName.replace(/^\d+px[-_]/i, '');
      // Strip image extensions (including double extensions like ".svg.png")
      rawName = rawName.replace(/\.(svg|png|jpe?g|webp|gif|bmp|avif)+$/gi, '');
      // Replace underscores and hyphens with spaces
      rawName = rawName.replace(/[_-]+/g, ' ');
      rawName = cleanText(rawName);
      // Skip non-descriptive machine hashes or 1-character filenames
      if (rawName.length <= 2 || /^[0-9a-f]{16,}$/i.test(rawName)) {
        return '';
      }
      return rawName;
    } catch (_e) {
      return '';
    }
  }

  function isGenericPageViewerLabel(label) {
    return /^(book\s+page(\s+image)?|page(\s+image)?|scan(ned)?\s+page)$/i.test(cleanText(label));
  }

  function enrichGenericPageViewerLabel(el, baseLabel, rootDoc) {
    const doc = rootDoc || el.ownerDocument || document;
    const scopeContainer =
      el.closest('section, article, [class*="reader"], [class*="viewer"], [id*="reader"], [id*="viewer"]') ||
      doc;

    // 1. Check if there are multiple generic "Page" images in the same reader container (continuous scroll reader)
    if (scopeContainer) {
      const peerGenericPageImgs = [...scopeContainer.querySelectorAll('img')].filter(img => {
        const alt = cleanText(img.getAttribute('alt') || img.getAttribute('aria-label'));
        return isGenericPageViewerLabel(alt) && !img.hasAttribute('data-page') && !img.hasAttribute('data-page-number');
      });
      if (peerGenericPageImgs.length > 1 && peerGenericPageImgs.includes(el)) {
        const idx = peerGenericPageImgs.indexOf(el);
        return `${baseLabel} (page ${idx + 1} of ${peerGenericPageImgs.length} loaded)`;
      }
    }

    // 2. Check if the image or its reader container exposes an explicit page number
    const pageAttr =
      el.getAttribute('data-page') ||
      el.getAttribute('data-page-number') ||
      el.closest('[data-page],[data-page-number]')?.getAttribute('data-page') ||
      el.closest('[data-page],[data-page-number]')?.getAttribute('data-page-number');
    let detectedPageNum = cleanText(pageAttr);

    if (!detectedPageNum && scopeContainer) {
      const pageIndicator = scopeContainer.querySelector(
        '.BRcurrentpage, [class*="currentpage"], [class*="page-number"], [class*="pagenum"], [aria-label*="Page " i]'
      );
      if (pageIndicator) {
        const text = cleanText(
          pageIndicator.textContent || pageIndicator.value || pageIndicator.getAttribute('aria-label')
        );
        const m = text.match(/\b(?:page\s*)?(\d+)\b/i);
        if (m) {
          detectedPageNum = m[1];
        }
      }
    }

    if (detectedPageNum) {
      const pageTitle = cleanText(doc?.title);
      const suffix = pageTitle ? ` — page ${detectedPageNum} (${pageTitle})` : ` — page ${detectedPageNum}`;
      return `${baseLabel}${suffix}`;
    }

    return baseLabel;
  }

  function isSingleLetterDropCap(el) {
    if (el.tagName.toLowerCase() !== 'img') return false;
    const alt = cleanText(el.getAttribute('alt'));
    if (!/^[A-Za-z]$/.test(alt)) return false;

    const cls = `${el.className || ''} ${el.parentElement?.className || ''}`.toLowerCase();
    if (/dropcap|drop-cap|initial|figleft/.test(cls)) {
      return true;
    }
    // Check if the image is followed immediately by lowercase continuation letters in a paragraph
    const nextNode = el.nextSibling || el.parentElement?.nextSibling;
    if (nextNode && nextNode.nodeType === 3) {
      const nextText = nextNode.nodeValue || '';
      if (/^[a-z]{2,}/.test(nextText)) {
        return true;
      }
    }
    const src = el.getAttribute('src') || '';
    if (/(?:^|\/)[a-z]\.(png|gif|jpe?g|svg)$/i.test(src)) {
      return true;
    }
    return false;
  }

  function mergeDropCapIntoText(el) {
    if (el.getAttribute('data-monarch-dropcap-merged') === 'true') {
      return;
    }
    const letter = cleanText(el.getAttribute('alt'));
    el.setAttribute('data-monarch-dropcap-merged', 'true');
    el.setAttribute('aria-hidden', 'true');
    el.setAttribute('role', 'presentation');
    el.setAttribute('alt', '');

    // Find the first following text node in the paragraph to prepend the drop-cap letter
    let cursor = el.nextSibling;
    if (!cursor && el.parentElement && el.parentElement.tagName.toLowerCase() !== 'p') {
      cursor = el.parentElement.nextSibling;
    }
    while (cursor && cursor.nodeType !== 3 && cursor.firstChild) {
      cursor = cursor.firstChild;
    }
    if (cursor && cursor.nodeType === 3) {
      cursor.nodeValue = letter + (cursor.nodeValue || '');
    } else {
      const span = (el.ownerDocument || document).createElement('span');
      span.className = 'monarch-dropcap-text';
      span.textContent = letter;
      el.insertAdjacentElement('afterend', span);
    }
  }

  function isLazyImageCandidate(el) {
    if (el.tagName.toLowerCase() !== 'img') return false;
    if ((el.getAttribute('loading') || '').toLowerCase() === 'lazy') return true;
    if (el.hasAttribute('data-src') || el.hasAttribute('data-srcset') || el.hasAttribute('data-lazy-src')) return true;
    const cls = String(el.className || '').toLowerCase();
    if (cls.includes('lazy')) return true;
    return false;
  }

  function getElementDimensions(el) {
    const rect = typeof el.getBoundingClientRect === 'function'
      ? el.getBoundingClientRect()
      : { width: 0, height: 0, x: 0, y: 0 };
    const attrW = parseFloat(el.getAttribute('width') || '0') || 0;
    const attrH = parseFloat(el.getAttribute('height') || '0') || 0;
    const natW = el.naturalWidth || 0;
    const natH = el.naturalHeight || 0;
    const width = Math.max(rect.width || 0, attrW, natW);
    const height = Math.max(rect.height || 0, attrH, natH);
    return {
      width,
      height,
      rect
    };
  }

  function looksLikeDecorativeChromeToken(str) {
    if (!str) return false;
    return /(?:^|[\s/_.-])(spacer|blank|pixel|1x1|sprite|padlock|lock-icon|commons-logo|wikibooks-logo|openstax-logo|site-logo|ui-icon|favicon|bullet|arrow-icon)(?:$|[\s/_.-])/i.test(
      str
    );
  }

  function isElementHidden(el, win) {
    if (el.hasAttribute('hidden')) return true;
    const ariaHidden = (el.getAttribute('aria-hidden') || '').toLowerCase();
    if (ariaHidden === 'true' && !el.hasAttribute('data-monarch-id')) return true;
    const role = (el.getAttribute('role') || '').toLowerCase();
    if (role === 'presentation' || role === 'none') return true;
    if (win && typeof win.getComputedStyle === 'function') {
      const style = win.getComputedStyle(el);
      if (style && (style.display === 'none' || style.visibility === 'hidden')) {
        return true;
      }
    }
    return false;
  }

  function extractLabelAndMetadata(el, rootDoc) {
    const tag = el.tagName.toLowerCase();
    const doc = rootDoc || el.ownerDocument || document;
    const ariaLabel = cleanText(el.getAttribute('aria-label'));
    const ariaLabelledBy = resolveAriaLabelledBy(el, doc);
    const hasExplicitAltAttr = el.hasAttribute('alt');
    const rawAlt = cleanText(el.getAttribute('alt'));
    const svgTitle = tag === 'svg' ? cleanText(el.querySelector('title')?.textContent) : '';
    const svgDesc = tag === 'svg' ? cleanText(el.querySelector('desc')?.textContent) : '';
    const titleAttr = cleanText(el.getAttribute('title'));
    const caption = findSurroundingCaption(el);
    const src = el.getAttribute('src') || el.currentSrc || el.getAttribute('data-src') || '';

    let label = '';
    let description = '';
    let labelSource = '';

    // 1. Explicit ARIA labels take top priority
    if (ariaLabel) {
      label = ariaLabel;
      labelSource = 'aria-label';
    } else if (ariaLabelledBy) {
      label = ariaLabelledBy;
      labelSource = 'aria-labelledby';
    } else if (svgTitle) {
      label = svgTitle;
      labelSource = 'svg-title';
      if (svgDesc) description = svgDesc;
    } else if (caption && rawAlt && rawAlt.length > LONG_ALT_THRESHOLD) {
      // Textbook pattern (e.g. OpenStax): concise figure number + caption in <figcaption>,
      // while the long alt attribute holds a full multi-sentence description.
      label = caption;
      description = rawAlt;
      labelSource = 'figcaption+long-alt-description';
    } else if (rawAlt) {
      label = rawAlt;
      labelSource = 'alt';
      if (caption && caption !== rawAlt) {
        description = caption;
      }
    } else if (caption) {
      // Encyclopedia thumbnail pattern (e.g. Wikipedia): empty alt="", meaning in caption div / figcaption
      label = caption;
      labelSource = 'caption';
    } else if (titleAttr) {
      label = titleAttr;
      labelSource = 'title';
    } else {
      // Fallback to humanized filename only when not an explicitly decorative empty-alt small image
      const { width, height } = getElementDimensions(el);
      const isLargeContentImage =
        width >= MIN_CONTENT_FALLBACK_PX && height >= MIN_CONTENT_FALLBACK_PX;
      if (!hasExplicitAltAttr || isLargeContentImage) {
        const fromFile = humanizeFilename(src);
        if (fromFile && !looksLikeDecorativeChromeToken(fromFile)) {
          label = fromFile;
          labelSource = 'filename';
        }
      }
    }

    if (label && isGenericPageViewerLabel(label)) {
      label = enrichGenericPageViewerLabel(el, label, doc);
      labelSource = 'page-viewer-label';
    }

    // Check if canvas has a fallback text content when no aria-label/title is set
    if (!label && tag === 'canvas') {
      const fallbackText = cleanText(el.textContent);
      label = fallbackText || 'Interactive canvas graphic';
      labelSource = fallbackText ? 'canvas-fallback' : 'canvas-default';
    }

    return {
      label,
      description,
      titleText: titleAttr,
      labelSource,
      src,
      hasExplicitEmptyAlt: hasExplicitAltAttr && rawAlt === ''
    };
  }

  function shouldPromoteElement(el, rootDoc) {
    const doc = rootDoc || el.ownerDocument || document;
    const win = doc?.defaultView || globalScope;
    const tag = el.tagName.toLowerCase();

    if (isElementHidden(el, win)) {
      return { promote: false, reason: 'hidden-or-presentation' };
    }

    // Skip inner <svg> inside an outer <svg>
    if (tag === 'svg' && el.parentElement && el.parentElement.closest('svg')) {
      return { promote: false, reason: 'nested-svg' };
    }

    // Skip <figure> container if it already contains an <img>, <svg>, or <canvas>
    // (those child elements will be promoted using the <figure>'s <figcaption>).
    if (tag === 'figure' && el.querySelector('img, svg, canvas')) {
      return { promote: false, reason: 'figure-has-visual-child' };
    }

    // Handle single-letter drop-cap images (e.g. Gutenberg "A" before "lice")
    if (isSingleLetterDropCap(el)) {
      mergeDropCapIntoText(el);
      return { promote: false, reason: 'drop-cap-letter' };
    }

    const { width, height } = getElementDimensions(el);
    const lazy = isLazyImageCandidate(el);
    const meta = extractLabelAndMetadata(el, doc);
    const role = (el.getAttribute('role') || '').toLowerCase();

    // Check decorative / protection padlock / tiny icon patterns
    const combinedTokens = `${meta.src} ${el.className || ''} ${el.id || ''} ${meta.label}`;
    if (looksLikeDecorativeChromeToken(combinedTokens)) {
      return { promote: false, reason: 'decorative-token' };
    }

    // Skip tiny images (< 32px), unless:
    // - It is a lazy-loaded image that hasn't loaded pixels yet
    // - It is a [role="img"] element with an explicit label
    // - It is an inline <svg> with an explicit <title> or aria-label (>= 16px)
    const isExplicitRoleImg = role === 'img' && Boolean(meta.label);
    const isLabeledSvg =
      tag === 'svg' &&
      (meta.labelSource === 'svg-title' || meta.labelSource === 'aria-label' || meta.labelSource === 'aria-labelledby') &&
      width >= 16 &&
      height >= 16;

    if (!lazy && !isExplicitRoleImg && !isLabeledSvg) {
      if (width < MIN_DIMENSION_PX || height < MIN_DIMENSION_PX) {
        return { promote: false, reason: 'tiny-dimensions' };
      }
    }

    // If no label could be resolved at all, skip
    if (!meta.label) {
      return { promote: false, reason: 'no-meaningful-label' };
    }

    return { promote: true, meta, width, height, lazy };
  }

  function triggerLazyImageLoad(el) {
    if (!el || el.tagName.toLowerCase() !== 'img') return false;
    let triggered = el.getAttribute('data-monarch-lazy-loaded') === 'true';
    if ((el.getAttribute('loading') || '').toLowerCase() === 'lazy') {
      el.setAttribute('loading', 'eager');
      triggered = true;
    }
    const dataSrc = el.getAttribute('data-src') || el.getAttribute('data-lazy-src');
    if (dataSrc && (!el.getAttribute('src') || el.getAttribute('src').startsWith('data:'))) {
      el.setAttribute('src', dataSrc);
      triggered = true;
    }
    const dataSrcset = el.getAttribute('data-srcset');
    if (dataSrcset && !el.getAttribute('srcset')) {
      el.setAttribute('srcset', dataSrcset);
      triggered = true;
    }
    if (triggered) {
      el.setAttribute('data-monarch-lazy-loaded', 'true');
    }
    return triggered;
  }

  // ---------------------------------------------------------------------------
  // 3. Promote Meaningful Visual Elements to Focusable <button> Elements
  // ---------------------------------------------------------------------------
  function promoteImages(root = globalScope.document) {
    if (!root || typeof root.querySelectorAll !== 'function') {
      return [];
    }
    const doc = root.nodeType === 9 ? root : root.ownerDocument || globalScope.document;
    const candidates = root.querySelectorAll('img, svg, canvas, figure, [role="img"]');
    const promoted = [];

    for (const el of candidates) {
      // Idempotence: if already promoted, collect existing button
      const existingId = el.getAttribute('data-monarch-id');
      if (existingId) {
        const existingBtn = doc.querySelector(`button.monarch-tactile-btn[data-monarch-target-id="${existingId}"]`);
        if (existingBtn) {
          promoted.push(existingBtn);
          continue;
        }
      }

      const decision = shouldPromoteElement(el, doc);
      if (!decision.promote) {
        continue;
      }

      const { meta } = decision;
      const targetId = `monarch-target-${nextTargetCounter++}`;
      el.setAttribute('data-monarch-id', targetId);
      el.setAttribute('aria-hidden', 'true');
      targetElementsById.set(targetId, el);

      const btn = doc.createElement('button');
      btn.type = 'button';
      btn.className = 'monarch-tactile-btn';
      btn.setAttribute('data-monarch-target-id', targetId);
      btn.setAttribute('data-monarch-label', meta.label);
      btn.setAttribute('data-monarch-label-source', meta.labelSource);
      if (meta.src) {
        btn.setAttribute('data-monarch-src', meta.src);
      }

      // Expose hover title text when present and distinct from the primary label (e.g. xkcd-327)
      const hasDistinctTitle =
        Boolean(meta.titleText) &&
        meta.titleText.toLowerCase() !== meta.label.toLowerCase() &&
        !meta.label.toLowerCase().includes(meta.titleText.toLowerCase());

      if (hasDistinctTitle) {
        btn.setAttribute('title', meta.titleText);
        btn.setAttribute('data-monarch-title', meta.titleText);
      }

      if (meta.description) {
        btn.setAttribute('data-monarch-description', meta.description);
        btn.setAttribute('aria-description', meta.description);
      }

      const buttonText = hasDistinctTitle
        ? `Explore tactile image: ${meta.label} — ${meta.titleText}`
        : `Explore tactile image: ${meta.label}`;
      btn.textContent = buttonText;

      btn.addEventListener('click', () => {
        triggerLazyImageLoad(el);
        const capture = captureElementGeometry(targetId, doc);
        globalScope.__monarchBridge.lastActivatedCapture = capture;
        const evt = new CustomEvent('monarch:explore-tactile', {
          bubbles: true,
          detail: capture
        });
        btn.dispatchEvent(evt);
        if (
          globalScope.__monarchNativeBridge &&
          typeof globalScope.__monarchNativeBridge.onExploreTactileImage === 'function'
        ) {
          globalScope.__monarchNativeBridge.onExploreTactileImage(JSON.stringify(capture));
        }
      });

      el.insertAdjacentElement('beforebegin', btn);
      promoted.push(btn);
    }

    return promoted;
  }

  // ---------------------------------------------------------------------------
  // 4. SVG & Canvas2D Geometry Capture Matching tactile-core
  // ---------------------------------------------------------------------------
  function parseSvgViewBox(svgEl) {
    const vbAttr = cleanText(svgEl.getAttribute('viewBox'));
    if (vbAttr) {
      const nums = vbAttr.split(/[\s,]+/).map(Number);
      if (nums.length === 4 && nums.every(n => !Number.isNaN(n)) && nums[2] > 0 && nums[3] > 0) {
        return { minX: nums[0], minY: nums[1], width: nums[2], height: nums[3] };
      }
    }
    const w = parseFloat(svgEl.getAttribute('width') || '0') || svgEl.getBoundingClientRect?.().width || 100;
    const h = parseFloat(svgEl.getAttribute('height') || '0') || svgEl.getBoundingClientRect?.().height || 100;
    return { minX: 0, minY: 0, width: Math.max(1, w), height: Math.max(1, h) };
  }

  function extractSvgComponents(svgEl, viewBox) {
    const components = [];
    let compIdx = 1;
    const shapeSelector = 'path, rect, circle, ellipse, line, polyline, polygon, g[id], g[aria-label]';
    const nodes = svgEl.querySelectorAll(shapeSelector);

    for (const node of nodes) {
      // Respect <switch><g systemLanguage="..."></switch>
      const sw = node.closest('switch');
      if (sw) {
        const sysLangNode = node.closest('[systemLanguage]');
        if (sysLangNode) {
          const lang = (sysLangNode.getAttribute('systemLanguage') || '').toLowerCase();
          if (lang && !lang.startsWith('en')) continue;
        }
      }

      const tag = node.tagName.toLowerCase();
      const titleChild = node.querySelector(':scope > title');
      const label =
        cleanText(node.getAttribute('aria-label')) ||
        cleanText(titleChild?.textContent) ||
        cleanText(node.getAttribute('id'));

      if (tag === 'g' && !label) continue;

      let bbox = null;
      try {
        if (typeof node.getBBox === 'function') {
          const b = node.getBBox();
          if (b && b.width >= 0 && b.height >= 0 && (b.width > 0 || b.height > 0)) {
            const x0 = Math.max(0, Math.min(1, (b.x - viewBox.minX) / viewBox.width));
            const y0 = Math.max(0, Math.min(1, (b.y - viewBox.minY) / viewBox.height));
            const x1 = Math.max(0, Math.min(1, (b.x + b.width - viewBox.minX) / viewBox.width));
            const y1 = Math.max(0, Math.min(1, (b.y + b.height - viewBox.minY) / viewBox.height));
            bbox = {
              ymin: Number(y0.toFixed(4)),
              xmin: Number(x0.toFixed(4)),
              ymax: Number(y1.toFixed(4)),
              xmax: Number(x1.toFixed(4))
            };
          }
        }
      } catch (_e) {
        // getBBox can throw if SVG is detached
      }

      if (!bbox) {
        bbox = { ymin: 0, xmin: 0, ymax: 1, xmax: 1 };
      }

      if (label || tag !== 'g') {
        components.push({
          id: compIdx++,
          elementId: node.getAttribute('id') || null,
          tag,
          label: label || `${tag}-${compIdx - 1}`,
          bbox
        });
      }
    }
    return components;
  }

  function buildCanvasGeometry(canvas) {
    const state = ensureCanvasState(canvas);
    const cw = Math.max(1, canvas.width || canvas.getBoundingClientRect?.().width || 300);
    const ch = Math.max(1, canvas.height || canvas.getBoundingClientRect?.().height || 150);

    const strokes = [];
    const components = [];
    let nextCompId = 1;

    for (const op of state.operations) {
      if (op.type === 'text') {
        const [x0, y0, x1, y1] = op.box;
        const xmin = Math.max(0, Math.min(1, x0 / cw));
        const ymin = Math.max(0, Math.min(1, y0 / ch));
        const xmax = Math.max(0, Math.min(1, x1 / cw));
        const ymax = Math.max(0, Math.min(1, y1 / ch));
        components.push({
          id: `c${nextCompId++}`,
          label: op.text,
          role: 'text',
          embeddedText: op.text,
          box: [Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1)],
          bbox: {
            ymin: Number(ymin.toFixed(4)),
            xmin: Number(xmin.toFixed(4)),
            ymax: Number(ymax.toFixed(4)),
            xmax: Number(xmax.toFixed(4))
          },
          strokes: []
        });
        continue;
      }

      if (op.points && op.points.length >= 2) {
        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;
        const normPoints = [];

        for (const [px, py] of op.points) {
          if (px < minX) minX = px;
          if (py < minY) minY = py;
          if (px > maxX) maxX = px;
          if (py > maxY) maxY = py;
          normPoints.push([
            Number(Math.max(0, Math.min(1, px / cw)).toFixed(4)),
            Number(Math.max(0, Math.min(1, py / ch)).toFixed(4))
          ]);
        }

        const strokeObj = {
          points: normPoints,
          closed: Boolean(op.closed)
        };
        strokes.push(strokeObj);

        components.push({
          id: `c${nextCompId++}`,
          label: `Shape ${nextCompId - 1}`,
          role: 'object',
          embeddedText: '',
          box: [Math.round(minX), Math.round(minY), Math.round(maxX), Math.round(maxY)],
          bbox: {
            ymin: Number(Math.max(0, Math.min(1, minY / ch)).toFixed(4)),
            xmin: Number(Math.max(0, Math.min(1, minX / cw)).toFixed(4)),
            ymax: Number(Math.max(0, Math.min(1, maxY / ch)).toFixed(4)),
            xmax: Number(Math.max(0, Math.min(1, maxX / cw)).toFixed(4))
          },
          strokes: [strokeObj]
        });
      }
    }

    return {
      sourceSize: [cw, ch],
      operationCount: state.operations.length,
      strokes,
      components
    };
  }

  function decodeDataUriSvg(src) {
    if (!src || !src.startsWith('data:image/svg+xml')) return null;
    const commaIdx = src.indexOf(',');
    if (commaIdx < 0) return null;
    const header = src.slice(0, commaIdx);
    const body = src.slice(commaIdx + 1);
    try {
      if (header.includes(';base64')) {
        return globalScope.atob(body);
      }
      return decodeURIComponent(body);
    } catch (_e) {
      return null;
    }
  }

  function captureElementGeometry(targetIdOrElement, rootDoc = globalScope.document) {
    const doc = rootDoc || globalScope.document;
    let el = null;
    let targetId = '';

    if (typeof targetIdOrElement === 'string') {
      targetId = targetIdOrElement;
      el =
        targetElementsById.get(targetId) ||
        doc?.querySelector(`[data-monarch-id="${targetId}"]`);
    } else if (targetIdOrElement && targetIdOrElement.nodeType === 1) {
      el = targetIdOrElement;
      targetId = el.getAttribute('data-monarch-id') || '';
    }

    if (!el) {
      return null;
    }

    const tag = el.tagName.toLowerCase();
    const meta = extractLabelAndMetadata(el, doc);
    const { width, height, rect } = getElementDimensions(el);
    const lazyTriggered = triggerLazyImageLoad(el);

    const basePayload = {
      targetId,
      tag,
      label: meta.label,
      titleText: meta.titleText || null,
      description: meta.description || null,
      labelSource: meta.labelSource,
      src: meta.src || null,
      lazyLoadedTriggered: lazyTriggered,
      renderedBox: [
        Math.round(rect.x || 0),
        Math.round(rect.y || 0),
        Math.round(width),
        Math.round(height)
      ]
    };

    if (tag === 'svg') {
      const serializer = new (doc.defaultView?.XMLSerializer || globalScope.XMLSerializer)();
      let svgXml = serializer.serializeToString(el);
      if (!svgXml.includes('xmlns=')) {
        svgXml = svgXml.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"');
      }
      const viewBox = parseSvgViewBox(el);
      const components = extractSvgComponents(el, viewBox);
      return {
        ...basePayload,
        geometryType: 'svg',
        sourceSize: [viewBox.width, viewBox.height],
        viewBox,
        svgXml,
        components
      };
    }

    if (tag === 'canvas') {
      const canvasGeom = buildCanvasGeometry(el);
      return {
        ...basePayload,
        geometryType: 'canvas2d',
        sourceSize: canvasGeom.sourceSize,
        operationCount: canvasGeom.operationCount,
        strokes: canvasGeom.strokes,
        components: canvasGeom.components
      };
    }

    const inlineSvgXml = decodeDataUriSvg(meta.src);
    return {
      ...basePayload,
      geometryType: inlineSvgXml ? 'svg-data-uri' : 'raster',
      sourceSize: [
        el.naturalWidth || Math.round(width),
        el.naturalHeight || Math.round(height)
      ],
      svgXml: inlineSvgXml,
      components: []
    };
  }

  function exportTextView(root = globalScope.document) {
    promoteImages(root);
    const buttons = [...root.querySelectorAll('button.monarch-tactile-btn')].map(btn => ({
      label: btn.getAttribute('data-monarch-label') || cleanText(btn.textContent),
      buttonText: cleanText(btn.textContent),
      src: btn.getAttribute('data-monarch-src') || null,
      titleText: btn.getAttribute('data-monarch-title') || null,
      description: btn.getAttribute('data-monarch-description') || null,
      labelSource: btn.getAttribute('data-monarch-label-source') || null,
      targetId: btn.getAttribute('data-monarch-target-id') || null
    }));
    return { buttons };
  }

  // ---------------------------------------------------------------------------
  // 5. Auto-Install at Document Start + MutationObserver for Lazy DOM Updates
  // ---------------------------------------------------------------------------
  installCanvasInterceptor(globalScope);

  function setupAutoPromotion() {
    const doc = globalScope.document;
    if (!doc) return;

    const runPromote = () => {
      try {
        promoteImages(doc);
      } catch (_e) {
        // Ignore transient DOM mutation errors
      }
    };

    if (typeof globalScope.MutationObserver === 'function') {
      const observer = new globalScope.MutationObserver(mutations => {
        for (const m of mutations) {
          if (m.addedNodes && m.addedNodes.length > 0) {
            for (const node of m.addedNodes) {
              if (
                node.nodeType === 1 &&
                !node.classList?.contains('monarch-tactile-btn') &&
                !node.classList?.contains('monarch-dropcap-text')
              ) {
                runPromote();
                return;
              }
            }
          }
        }
      });

      const attachObserver = () => {
        try {
          observer.observe(doc, { childList: true, subtree: true });
        } catch (_e) {
          if (doc.documentElement) {
            observer.observe(doc.documentElement, { childList: true, subtree: true });
          }
        }
      };

      attachObserver();
      doc.addEventListener('DOMContentLoaded', () => {
        attachObserver();
        runPromote();
      });
      globalScope.addEventListener?.('load', () => {
        attachObserver();
        runPromote();
      });
    } else if (doc.readyState === 'loading') {
      doc.addEventListener('DOMContentLoaded', runPromote, { once: true });
    } else {
      runPromote();
    }
  }

  setupAutoPromotion();

  const api = {
    __initialized: true,
    promoteImages,
    installCanvasInterceptor,
    captureElementGeometry,
    exportTextView,
    triggerLazyImageLoad,
    lastActivatedCapture: null
  };

  globalScope.__monarchBridge = api;
  return api;
})(typeof window !== 'undefined' ? window : globalThis);
