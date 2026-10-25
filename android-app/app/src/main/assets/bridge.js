// Adds an "Explore tactile image" button after every picture on the page.
// Same rule for every site: label from alt, title, aria-label or the nearest caption.
(function () {
  if (window.__monarchBridge) { window.__monarchBridge(); return; }
  function caption(el) {
    var fig = el.closest('figure');
    var cap = fig && fig.querySelector('figcaption');
    if (cap && cap.innerText.trim()) return cap.innerText.trim();
    var box = el.closest('div[class*="thumb"], div[class*="caption"], td, li');
    var text = box && box.innerText ? box.innerText.trim() : '';
    return text.length > 0 && text.length < 300 ? text : '';
  }
  function label(img) {
    var parts = [img.getAttribute('alt'), img.getAttribute('aria-label'), caption(img)]
      .map(function (s) { return (s || '').trim(); }).filter(Boolean);
    if (!parts.length) {
      var name = decodeURIComponent((img.currentSrc || img.src || '').split('/').pop().split('?')[0] || 'image');
      parts.push(name.replace(/\.[a-z0-9]+$/i, '').replace(/[_-]+/g, ' '));
    }
    return parts[0];
  }
  function scan() {
    var imgs = document.querySelectorAll('img:not([data-monarch])');
    for (var i = 0; i < imgs.length; i++) {
      var img = imgs[i];
      var w = img.naturalWidth || img.width, h = img.naturalHeight || img.height;
      if (!img.complete || w < 64 || h < 64) continue;   // icons, spacers, still loading
      img.setAttribute('data-monarch', '1');
      var name = label(img);
      var title = img.getAttribute('title') || '';
      var button = document.createElement('button');
      button.type = 'button';
      button.textContent = 'Explore tactile image: ' + name;
      button.style.cssText = 'display:block;margin:4px 0;font-size:16px';
      (function (img, src, name, title) {
        function open(e) {
          e.preventDefault(); e.stopPropagation();
          MonarchBridge.openImage(src, name + (title ? '. ' + title : ''));
        }
        button.addEventListener('click', open);
        // Activating the picture itself opens it too. KeySoft delivers that as
        // contextmenu on an <img>, not click (MonarchTempest hardware findings).
        img.addEventListener('contextmenu', open);
        img.addEventListener('click', open);
      })(img, img.currentSrc || img.src, name, title);
      var anchor = img.closest('a') || img;
      anchor.insertAdjacentElement('afterend', button);
    }
  }
  window.__monarchBridge = scan;
  scan();
  var pending = null;
  new MutationObserver(function () {
    clearTimeout(pending); pending = setTimeout(scan, 500);
  }).observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener('load', function () { clearTimeout(pending); pending = setTimeout(scan, 300); }, true);
})();
