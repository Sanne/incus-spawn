// The Copy button on a code box: each command of the homepage's install box, and each docs block
// marked <!-- copy --> in its Markdown, which render.js wraps in a .copy-box. One component, so
// both look and behave the same.
document.querySelectorAll('.install-tabs .tab-panel, .copy-box').forEach(function(panel) {
  var btn = document.createElement('button');
  btn.className = 'copy-btn';
  btn.textContent = 'Copy';
  btn.addEventListener('click', function() {
    var code = panel.querySelector('code');
    if (!code) return;
    navigator.clipboard.writeText(code.textContent.replace(/\n$/, '')).then(function() {
      btn.textContent = 'Copied!';
      btn.classList.add('copied');
      setTimeout(function() { btn.textContent = 'Copy'; btn.classList.remove('copied'); }, 1500);
    });
  });
  panel.appendChild(btn);
});
