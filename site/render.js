// Renders the documentation pages of isx.run: README.md at /docs, and every docs/*.md beside it at
// /docs/<name> (docs/CLI.md is /docs/cli). Maintainer notes live in docs/dev/ and are not published.
// The guides, one task each, are docs/guides/<slug>.md at /guides/<slug>, listed at /guides once
// there are SECTION_FROM of them; each states its own title and description in front matter, since
// those lines are what a search result shows.
// Every page's title ends with TITLE_SUFFIX, the brand last, except the home page's, which starts
// with "isx: "; the build fails on a title that does not, or is longer than TITLE_MAX, and
// SiteTitlesTest holds what it does not check: no guide's title: names isx itself.
// Links between the Markdown files become links between the pages; any other relative link goes to
// the file on GitHub.
//
// Usage: node render.js <project root> <output dir>, with marked resolvable (NODE_PATH).
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { marked } = require('marked');

const [root, out] = process.argv.slice(2);
const GITHUB = 'https://github.com/Sanne/incus-spawn/blob/main/';
const TITLE_SUFFIX = ' — isx';
// What a search result shows of a title before it cuts it, roughly
const TITLE_MAX = 65;
const tpl = fs.readFileSync(path.join(root, 'site/docs.html'), 'utf8');

// Every page: its source, relative to the root, and its address on the site. They are listed in
// reading order: the reference a user looks things up in, any page not named here, then the
// project's vision.
const FIRST = ['agent-install', 'templates', 'agents', 'tools', 'configuration', 'github', 'cli', 'mcp', 'integrations', 'credential-proofs', 'sandboxes', 'fedora', 'faq'];
const LAST = ['vision'];
const rank = name => {
  const first = FIRST.indexOf(name), last = LAST.indexOf(name);
  return first >= 0 ? first : last >= 0 ? FIRST.length + 1 + last : FIRST.length;
};
const pages = [{ src: 'README.md', url: '/docs' }].concat(
  fs.readdirSync(path.join(root, 'docs'))
    .filter(f => f.endsWith('.md'))
    .map(f => ({ src: 'docs/' + f, name: f.slice(0, -3).toLowerCase(), url: '/docs/' + f.slice(0, -3).toLowerCase() }))
    .sort((a, b) => rank(a.name) - rank(b.name) || a.name.localeCompare(b.name)));

// Sections that were on /docs before the reference moved to pages of its own, and are not found
// by their id on a page (#1440): the Installation tabs folded into the Quick Start, and the
// template section, now a page whose title gives it another id.
const RENAMED = {
  'macos-homebrew': '/docs#quick-start', 'fedora-rhel-dnf': '/docs#quick-start',
  'ubuntu-debian-apt': '/docs#quick-start', 'any-linux-distro-native-binary': '/docs#quick-start',
  'jvm-via-jbang': '/docs#quick-start', 'template-images': '/docs/templates',
};
for (const name of FIRST.concat(LAST)) {
  if (!pages.some(p => p.name === name)) throw new Error('render.js lists docs/' + name.toUpperCase() + '.md, which does not exist');
}

// A guide's front matter: the lines between a first line of --- and the next ---, as key: value
const frontMatter = md => {
  const m = md.match(/^---\n([\s\S]*?)\n---\n/);
  if (!m) return { fields: {}, md };
  const fields = {};
  for (const line of m[1].split('\n')) {
    const kv = line.match(/^([\w-]+):\s*(.*)$/);
    if (kv) fields[kv[1]] = kv[2].replace(/^(["'])(.*)\1$/, '$2');
  }
  return { fields, md: md.slice(m[0].length) };
};

// The guides, and their index page, which is written here from them once they are a section (SECTION_FROM)
const GUIDES = 'docs/guides';
const guides = (fs.existsSync(path.join(root, GUIDES)) ? fs.readdirSync(path.join(root, GUIDES)) : [])
  .filter(f => f.endsWith('.md'))
  .map(f => ({ src: GUIDES + '/' + f, name: f.slice(0, -3).toLowerCase(), url: '/guides/' + f.slice(0, -3).toLowerCase(), guide: true }));
for (const guide of guides) {
  const { fields, md } = frontMatter(fs.readFileSync(path.join(root, guide.src), 'utf8'));
  if (!fields.description) throw new Error(guide.src + ' has no description: in its front matter');
  if (!fields.title) throw new Error(guide.src + ' has no title: in its front matter');
  guide.md = md;
  guide.description = fields.description;
  guide.searchTitle = fields.title;
}
guides.sort((a, b) => a.name.localeCompare(b.name));
// The guides become a section of the site, an index at /guides and a group in the sidebar, once
// there are this many; until then each guide is a page of its own, reached by the links to it.
// The nav's Guides link comes with the section: GuidesTest reads this number to hold the two together.
const SECTION_FROM = 10;
const section = guides.length >= SECTION_FROM;
const guideIndex = {
  src: GUIDES, url: '/guides', guide: true, searchTitle: 'Guides to safe sandboxes for AI coding agents',
  description: 'Guides to isx, one task each: from an installed isx to an agent at work in a sandbox of its own.',
};
guideIndex.md = '# Guides\n\n' + guideIndex.description + '\n'
  + 'The [documentation](/docs) is the reference each guide links to for its options.\n\n'
  + guides.map(g => '- [' + (g.md.match(/^# (.*)$/m) || [, g.name])[1] + '](' + g.url + '): ' + g.description + '\n').join('');
const guidePages = section ? [guideIndex].concat(guides) : guides;
const all = pages.concat(guidePages);
const bySrc = new Map(all.map(p => [p.src, p]));

const decode = s => s.replace(/&quot;/g, '"').replace(/&#39;/g, "'")
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const text = html => decode(html.replace(/<[^>]+>/g, ''));
// The same id GitHub gives the heading, for the simple headings these files use.
const slug = s => s.toLowerCase().replace(/[^\w\s-]/g, '').replace(/\s+/g, '-').replace(/-+$/, '');

function rewriteLinks(body, page) {
  return body.replace(/href="([^"#:]*)(#[^"]*)?"/g, (m, target, hash) => {
    if (target === '' || target.startsWith('/')) return m;
    const file = path.posix.normalize(path.posix.join(path.posix.dirname(page.src), target));
    const linked = bySrc.get(file);
    return 'href="' + (linked ? linked.url : GITHUB + file) + (hash || '') + '"';
  });
}

// Transform <!-- tabs:os --> ... <!-- tabs:end --> blocks into tabbed UI
function classifyOS(t) {
  const l = t.toLowerCase();
  if (/mac/.test(l)) return 'macos';
  if (/fedora|ubuntu|debian|linux|arch|nix/.test(l)) return 'linux';
  return 'any';
}

function tabs(body, consumedIds) {
  return body.replace(/<!-- tabs:os -->[\s\S]*?<!-- tabs:end -->/g, match => {
    const headingRe = /<(h[1-6]) id="([^"]*)">(.*?)<\/h[1-6]>/g;
    const found = [];
    let m;
    while ((m = headingRe.exec(match)) !== null) {
      if (found.length > 0) found[found.length - 1].content = match.slice(found[found.length - 1].end, m.index);
      const t = text(m[3]);
      consumedIds.push(m[2]);
      found.push({ id: m[2], text: t, os: classifyOS(t), start: m.index, end: m.index + m[0].length });
    }
    if (found.length === 0) return match;
    found[found.length - 1].content = match.slice(found[found.length - 1].end).replace('<!-- tabs:end -->', '');

    // Any content before the first heading (intro text)
    let html = match.slice(match.indexOf('-->') + 3, found[0].start).trim();
    html += '<div class="docs-tabs" data-tabs="os">';
    html += '<div class="tab-buttons">';
    found.forEach((tab, i) => {
      html += '<button class="tab-btn' + (i === 0 ? ' active' : '') + '" data-tab="' + i + '" data-os="' + tab.os + '">' + escape(tab.text) + '</button>';
    });
    html += '</div>';
    found.forEach((tab, i) => {
      html += '<div class="tab-panel' + (i === 0 ? ' active' : '') + '" data-tab-panel="' + i + '" id="' + tab.id + '">' + tab.content + '</div>';
    });
    html += '</div>';
    return html;
  });
}

// A code block whose fence names a language (```shell, ```yaml, ```text) is something to run or
// paste, so it gets the Copy button the homepage's install box has (copy.js): it is wrapped in a
// .copy-box, which the script finds. A bare fence is output or a file tree, and gets none.
const copyBoxes = body => body.replace(/<pre><code class="language-[\s\S]*?<\/pre>/g, '<div class="copy-box">$&</div>');

const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');

for (const page of all) {
  page.md ??= fs.readFileSync(path.join(root, page.src), 'utf8');
  page.title = (page.md.match(/^# (.*)$/m) || [, page.src])[1].replace(/[`*]/g, '');
  // The title a search result shows: the brand goes last, so a heading's leading "isx: " is dropped
  page.headTitle = (page.searchTitle ?? page.title).replace(/^isx: /, '') + TITLE_SUFFIX;
}

// The page's introduction, its first paragraph before any section, cut at a word to the length
// search engines show; null when the page opens with a section
function description(body) {
  const intro = body.split(/<h2[ >]/)[0].match(/<p>([\s\S]*?)<\/p>/);
  if (!intro) return null;
  const first = text(intro[1]).replace(/\s+/g, ' ').trim();
  if (first.length <= 160) return first;
  const cut = first.lastIndexOf(' ', 157);
  return first.slice(0, cut > 0 ? cut : 157).replace(/[.,;:]$/, '') + '…';
}

for (const page of all) {
  let body = marked.parse(page.md, { gfm: true });
  page.tocItems = [];
  page.ids = new Set();
  page.oldIds = new Map(); // the id the inline renderer gave a heading before #1440, where it differs
  body = body.replace(/<(h[1-6])>(.*?)<\/h[1-6]>/g, (m, tag, inner) => {
    const t = text(inner);
    const id = slug(t);
    const level = parseInt(tag.substring(1));
    if (level === 1 && page.top === undefined) page.top = id;
    page.ids.add(id);
    const old = slug(inner.replace(/<[^>]+>/g, ''));
    if (old !== id) page.oldIds.set(old, id);
    // Only h2 and h3 in the TOC: h1 is the title, h4 and below are too granular
    if (level === 2 || level === 3) page.tocItems.push({ level, id, text: t });
    return '<' + tag + ' id="' + id + '">' + inner + '</' + tag + '>';
  });
  page.consumedIds = [];
  page.body = rewriteLinks(copyBoxes(tabs(body, page.consumedIds)), page);
}

// Where a link to /docs#<id> goes when the README no longer has that id: the page that has it
const moved = Object.assign({}, RENAMED);
for (const page of pages) {
  for (const [old, id] of page.oldIds) if (!pages[0].ids.has(old)) moved[old] ??= page.url + '#' + id;
}
for (const page of pages.slice(1)) {
  for (const id of page.ids) if (!pages[0].ids.has(id)) moved[id] ??= page.url + '#' + id;
}

// On /docs only: a link to a section that moved goes on to its page
const redirect = `<script>
(function() {
  var moved = ${JSON.stringify(moved).replace(/</g, '\\u003c')};
  function follow() {
    var id;
    try { id = decodeURIComponent(location.hash.slice(1)); } catch (e) { return; }
    if (id && !document.getElementById(id) && moved[id]) location.replace(moved[id]);
  }
  follow();
  window.addEventListener('hashchange', follow);
})();
</script>`;

// Each page is dated by the last commit that changed its content, for the sitemap and a guide's
// structured data. Needs the history: the deploy workflows check out with fetch-depth: 0.
const lastmod = (...files) => execFileSync('git', ['-C', root, 'log', '-1', '--format=%cs', '--', ...files]).toString().trim();
for (const page of all) page.mod = lastmod(page.src, 'site/docs.html');

// The groups of pages, each in its sidebar list: the reference, and the guides once they are a
// section, the group of the page shown first
const label = p => escape(p === pages[0] ? 'Start here' : p === guideIndex ? 'All guides' : p.title);
const groups = page => {
  const both = [{ heading: 'Documentation', list: pages }, { heading: 'Guides', list: guidePages }];
  if (!section) return both.slice(0, 1);
  return page.guide ? both.reverse() : both;
};

for (const page of all) {
  const { body, tocItems, consumedIds, top } = page;

  let toc = '<nav class="docs-toc" aria-label="Table of contents"><ul>';
  toc += '<li class="toc-item"><a href="#' + top + '">Overview</a></li>';
  tocItems.filter(item => !consumedIds.includes(item.id)).forEach(item => {
    toc += '<li class="toc-item' + (item.level === 3 ? ' toc-item-nested' : '') + '"><a href="#' + item.id + '">' + escape(item.text) + '</a></li>';
  });
  toc += '</ul>' + groups(page).map(g => '<p class="toc-heading">' + g.heading + '</p><ul>'
    + g.list.map(p => '<li class="toc-item"><a href="' + p.url + '"' + (p === page ? ' aria-current="page" class="active"' : '') + '>'
      + label(p) + '</a></li>').join('') + '</ul>').join('') + '</nav>';

  // The TOC is hidden on narrow screens, so the page lists also follow the content there
  const more = '<nav class="docs-pages" aria-label="Pages">' + groups(page).map(g => '<p class="toc-heading">' + g.heading + '</p><ul>'
    + g.list.map(p => '<li><a href="' + p.url + '">' + label(p) + '</a></li>').join('') + '</ul>').join('') + '</nav>';
  let html = tpl.replace('<!-- README_CONTENT -->', () => body + more).replace('<!-- TOC -->', () => toc)
    .replace('<!-- MOVED -->', () => page === pages[0] ? redirect : '');
  if (page !== pages[0]) {
    const url = 'https://isx.run' + page.url;
    const desc = page.guide ? page.description : description(body);
    html = html.replace(/<title>.*?<\/title>/, '<title>' + escape(page.headTitle) + '</title>')
      .replace(/(<meta (?:name="description"|property="og:description"|name="twitter:description") content=")[^"]*/g,
        (m, attr) => desc === null ? m : attr + escape(desc))
      .replace(/(<link rel="canonical" href=")[^"]*/, '$1' + url)
      .replace(/(<meta property="og:url" content=")[^"]*/, '$1' + url)
      .replace(/(<meta (?:property="og|name="twitter):title" content=")[^"]*/g, '$1' + escape(page.headTitle));
    // A guide is an article, and says so to search engines in structured data
    if (page.guide && page !== guideIndex) {
      const article = {
        '@context': 'https://schema.org', '@type': 'TechArticle', headline: page.title, description: desc,
        url, dateModified: page.mod, inLanguage: 'en',
        isPartOf: { '@type': 'WebSite', name: 'isx', url: 'https://isx.run/' },
      };
      html = html.replace('<meta property="og:type" content="website">', '<meta property="og:type" content="article">')
        .replace('</head>', () => '  <script type="application/ld+json">' + JSON.stringify(article).replace(/</g, '\\u003c') + '</script>\n</head>');
    }
  }
  const file = path.join(out, page.url.slice(1) + '.html');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, html);
}

// Sitemap, with each page's date
const entries = [{ url: '/', mod: lastmod('site/index.html') }]
  .concat(all.map(p => ({ url: p.url, mod: p.mod })));
fs.writeFileSync(path.join(out, 'sitemap.xml'), '<?xml version="1.0" encoding="UTF-8"?>\n'
  + '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
  + entries.map(e => '  <url>\n    <loc>https://isx.run' + e.url + '</loc>\n    <lastmod>' + e.mod + '</lastmod>\n  </url>\n').join('')
  + '</urlset>\n');

// llms.txt (https://llmstxt.org) links here: the full docs as one Markdown file, every page in order
fs.writeFileSync(path.join(out, 'llms-full.txt'), all
  .map(p => (p === pages[0] ? '' : '\n\n<!-- ' + p.src + ': https://isx.run' + p.url + ' -->\n\n')
    + p.md)
  .join(''));

// Every page's title, and the titles a shared link shows, end with the brand, the home page's
// alone starting with it, and fit in a search result. The pages copied as they are (404.html)
// are checked here too.
const titleProblems = [];
for (const f of fs.readdirSync(out, { recursive: true }).filter(f => f.endsWith('.html'))) {
  const html = fs.readFileSync(path.join(out, f), 'utf8');
  const titles = [(html.match(/<title>(.*?)<\/title>/) || [])[1]]
    .concat([...html.matchAll(/<meta (?:property="og|name="twitter):title" content="([^"]*)"/g)].map(m => m[1]));
  for (const raw of titles) {
    const title = raw === undefined ? undefined : decode(raw.replace(/&mdash;/g, '—'));
    if (title === undefined) titleProblems.push(f + ' has no <title>');
    else if (f === 'index.html' ? !title.startsWith('isx: ') : !title.endsWith(TITLE_SUFFIX)) {
      titleProblems.push(f + "'s title \"" + title + '" does not ' + (f === 'index.html' ? 'start with "isx: "' : 'end with "' + TITLE_SUFFIX + '"'));
    } else if (title.length > TITLE_MAX) titleProblems.push(f + "'s title \"" + title + '" is longer than ' + TITLE_MAX + ' characters');
  }
}
if (titleProblems.length) throw new Error('Page titles:\n  ' + titleProblems.join('\n  '));
