// Offline tests for bitchord-signatures worker.js — Commons API is mocked.
import worker from "./worker.js";

let failures = 0;
function check(name, cond, extra = "") {
  if (cond) console.log(`ok   ${name}`);
  else { failures++; console.log(`FAIL ${name} ${extra}`); }
}

const call = (path) => worker.fetch(new Request(`https://sig.test${path}`), {});

// ---- mock Commons API ----
const realFetch = globalThis.fetch;
function mockCommons(pages) {
  globalThis.fetch = async (u) => new Response(JSON.stringify({ batchcomplete: "", query: { pages } }), {
    status: 200, headers: { "content-type": "application/json" },
  });
}
const restoreFetch = () => { globalThis.fetch = realFetch; };

// health: the settings screen only saves the URL when this answers 2xx
{
  const r = await call("/v1/health");
  const j = await r.json();
  check("health 2xx", r.status === 200 && r.ok, r.status);
  check("health ok", j.ok === true);
}

// empty artist -> empty signatures (never null, never throws)
{
  const j = await (await call("/v1/signatures")).json();
  check("no artist -> []", Array.isArray(j.signatures) && j.signatures.length === 0);
  const j2 = await (await call("/v1/signatures?artist=%20%20")).json();
  check("blank artist -> []", j2.signatures.length === 0);
}

// parsing + precision guard + ranking (svg beats png; junk filtered)
{
  mockCommons({
    1: { title: "File:Ateez Jongho signature.webp", imageinfo: [{ url: "https://upload.wikimedia.org/wikipedia/commons/9/94/Ateez_Jongho_signature.webp", width: 800, height: 200 }] },
    2: { title: "File:Ateez logo signature.svg", imageinfo: [{ url: "https://upload.wikimedia.org/wikipedia/commons/a/b/Ateez_logo_signature.svg", width: 1200, height: 300 }] },
    3: { title: "File:Random Eyes signature.jpg", imageinfo: [{ url: "https://upload.wikimedia.org/wikipedia/commons/x/y/Random_Eyes_signature.jpg", width: 100, height: 100 }] },
  });
  const j = await (await call("/v1/signatures?artist=ATEEZ")).json();
  restoreFetch();
  check("finds 2 (junk filtered)", j.signatures.length === 2, JSON.stringify(j.signatures.map(s => s.title)));
  check("svg ranked first", j.signatures[0].asset.url.endsWith(".svg"), j.signatures[0].asset.url);
  const s = j.signatures[0];
  check("contract fields", /^https:\/\//.test(s.asset.url) && s.asset.format === "svg"
    && s.width === 1200 && s.height === 300
    && s.license.status === "known" && typeof s.verification === "string",
    JSON.stringify(s).slice(0, 120));
}

// no results -> []
{
  mockCommons({});
  const j = await (await call("/v1/signatures?artist=Close%20Your%20Eyes")).json();
  restoreFetch();
  check("unknown artist -> []", j.signatures.length === 0);
}

// Commons http error -> [] (never throws, app treats as no signature)
{
  globalThis.fetch = async () => new Response("err", { status: 500 });
  const j = await (await call("/v1/signatures?artist=NMIXX")).json();
  restoreFetch();
  check("commons 500 -> []", j.signatures.length === 0);
}

// root info
{
  const j = await (await call("/")).json();
  check("root info", j.endpoints.includes("/v1/health"));
}

// unknown path
{
  const r = await call("/nope");
  check("404", r.status === 404, r.status);
}

console.log(failures ? `\n${failures} FAILURES` : "\nALL PASS");
process.exit(failures ? 1 : 0);
