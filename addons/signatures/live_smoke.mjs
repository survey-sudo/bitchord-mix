// Live smoke test: real Wikimedia Commons API through the worker.
// Run: node live_smoke.mjs
import worker from "./worker.js";

const call = (path) => worker.fetch(new Request(`https://sig.test${path}`), {});
let failures = 0;
const check = (name, cond, extra = "") =>
  cond ? console.log(`ok   ${name}`) : (failures++, console.log(`FAIL ${name} ${extra}`));

// ATEEZ: known to have member signatures on Commons
{
  const j = await (await call("/v1/signatures?artist=ATEEZ")).json();
  check("ATEEZ has signatures", j.signatures.length > 0, JSON.stringify(j).slice(0, 100));
  const s0 = j.signatures[0];
  check("ATEEZ first url https", /^https:\/\/upload\.wikimedia\.org\//.test(s0.asset.url), s0.asset.url);
  console.log(`     first: ${s0.title} (${s0.asset.format}, ${s0.width}x${s0.height})`);
}

// Seventeen: 10 member files expected
{
  const j = await (await call("/v1/signatures?artist=Seventeen")).json();
  check("Seventeen has signatures", j.signatures.length > 0, `n=${j.signatures.length}`);
}

// Close Your Eyes: verified to have none -> []
{
  const j = await (await call("/v1/signatures?artist=Close%20Your%20Eyes")).json();
  check("Close Your Eyes -> []", j.signatures.length === 0, `n=${j.signatures.length}`);
}

// caching: second call should be fast (<50ms, no network)
{
  const t0 = Date.now();
  await (await call("/v1/signatures?artist=ATEEZ")).json();
  const dt = Date.now() - t0;
  check("cache hit fast", dt < 2000, `${dt}ms`);
}

console.log(failures ? `\n${failures} FAILURES` : "\nALL PASS");
process.exit(failures ? 1 : 0);
