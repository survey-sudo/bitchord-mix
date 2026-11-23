// Build the signature-benchmark dataviz HTML from bench/top50.json
const fs = require("fs");
const data = JSON.parse(fs.readFileSync(__dirname + "/top50.json", "utf8"));
const artists = data.artists;

const totalScrobbles = artists.reduce((a, x) => a + x.scrobbles, 0);
const covered = artists.filter((x) => x.sig_files > 0);
const coveredScrobbles = covered.reduce((a, x) => a + x.scrobbles, 0);
const pct = Math.round((coveredScrobbles / totalScrobbles) * 100);
const maxS = Math.max(...artists.map((x) => x.scrobbles));

const fmt = (n) => n.toLocaleString("en-US");
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;");

const gaps = artists.filter((x) => x.sig_files === 0).slice(0, 3);

const rows = artists.map((a) => {
  const w = Math.max(2, (a.scrobbles / maxS) * 100);
  const has = a.sig_files > 0;
  const dup = a.dup_of ? ` <span class="dup">dup of ${esc(a.dup_of)}</span>` : "";
  return `<div class="row">
    <div class="rank">${a.rank}</div>
    <div class="name">${esc(a.name)}${dup}</div>
    <div class="barwrap"><div class="bar ${has ? "yes" : "no"}" style="width:${w.toFixed(1)}%"></div></div>
    <div class="count">${fmt(a.scrobbles)}</div>
    <div class="sig">${has ? `<span class="badge">${a.sig_files} sig</span>` : `<span class="none">—</span>`}</div>
  </div>`;
}).join("\n");

const html = `<!DOCTYPE html>
<html lang="id"><head><meta charset="utf-8">
<title>Signature coverage — top 50</title>
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body { background:#fafafa; color:#17171a; font-family:'Inter Tight','Inter',-apple-system,'Segoe UI',sans-serif; }
  .page { width:1080px; margin:0 auto; padding:64px 48px 56px; }
  .kicker { font-size:20px; font-weight:600; letter-spacing:.02em; color:#86868b; margin-bottom:16px; }
  h1 { font-size:64px; font-weight:800; letter-spacing:-0.03em; line-height:1.02; margin-bottom:12px; }
  h1 .accent { color:#ff6b1a; }
  .sub { font-size:21px; color:#86868b; margin-bottom:40px; }
  .stats { display:flex; gap:24px; margin-bottom:16px; }
  .stat { flex:1; background:#fff; padding:28px 32px; }
  .stat .n { font-size:56px; font-weight:800; letter-spacing:-0.03em; font-feature-settings:"tnum"; }
  .stat .n .accent { color:#ff6b1a; }
  .stat .l { font-size:19px; color:#86868b; margin-top:6px; }
  .gaps { background:#17171a; color:#fafafa; padding:26px 32px; margin-bottom:48px; display:flex; gap:32px; align-items:center; }
  .gaps .gl { font-size:19px; font-weight:700; white-space:nowrap; }
  .gaps .g { font-size:19px; color:#c6c6cd; }
  .gaps .g b { color:#fafafa; font-feature-settings:"tnum"; }
  .legend { display:flex; gap:28px; margin-bottom:20px; font-size:18px; color:#86868b; }
  .legend .sw { display:inline-block; width:34px; height:14px; margin-right:10px; vertical-align:baseline; }
  .sw.yes { background:#ff6b1a; } .sw.no { background:#ececf1; }
  .row { display:grid; grid-template-columns:44px 250px 1fr 92px 84px; gap:16px; align-items:center; height:46px; }
  .rank { font-size:17px; color:#86868b; font-feature-settings:"tnum"; text-align:right; }
  .name { font-size:19px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .dup { font-size:14px; font-weight:400; color:#86868b; }
  .barwrap { height:22px; background:transparent; }
  .bar { height:22px; }
  .bar.yes { background:#ff6b1a; }
  .bar.no { background:#ececf1; }
  .count { font-size:17px; text-align:right; font-feature-settings:"tnum"; color:#17171a; }
  .sig { text-align:right; }
  .badge { display:inline-block; font-size:15px; font-weight:700; color:#ff6b1a; border:2px solid #ff6b1a; padding:2px 10px; font-feature-settings:"tnum"; }
  .none { color:#c6c6cd; font-size:18px; }
  .foot { margin-top:44px; font-size:17px; color:#86868b; line-height:1.6; }
  .foot b { color:#17171a; }
</style></head><body><div class="page">
  <div class="kicker">BitChord · artist signatures</div>
  <h1>Signature coverage,<br><span class="accent">top 50 gue.</span></h1>
  <div class="sub">Wikimedia Commons vs 50 artis paling sering gue scrobble · ${data.as_of}</div>
  <div class="stats">
    <div class="stat"><div class="n"><span class="accent">${covered.length}</span>/50</div><div class="l">artis ada signature-nya di Commons</div></div>
    <div class="stat"><div class="n"><span class="accent">${pct}</span>%</div><div class="l">scrobbles ter-cover (${fmt(coveredScrobbles)} dari ${fmt(totalScrobbles)})</div></div>
  </div>
  <div class="gaps"><div class="gl">Lubang terbesar</div>${gaps.map((g) => `<div class="g">#${g.rank} ${esc(g.name)} · <b>${fmt(g.scrobbles)}</b></div>`).join("")}</div>
  <div class="legend"><div><span class="sw yes"></span>ada signature</div><div><span class="sw no"></span>kosong</div><div style="margin-left:auto">bar = scrobbles</div></div>
  ${rows}
  <div class="foot">Metode: <b>intitle:signature "nama artis"</b> di namespace File Commons, dicek presisi satu-satu (homonim dibuang). Signature yang ketemu itu <b>per member</b>, bukan per grup. Kategori <b>Signatures of &lt;grup&gt;</b> nggak ada buat K-pop — jadi file search emang jalur yang bener.<br>Worker API-nya udah jadi di <b>bitchord-signatures</b>, tinggal deploy nunggu token Cloudflare.</div>
</div></body></html>`;

fs.writeFileSync(__dirname + "/bench_top50.html", html);
console.log(`wrote bench_top50.html (${covered.length}/50, ${pct}% scrobbles)`);
