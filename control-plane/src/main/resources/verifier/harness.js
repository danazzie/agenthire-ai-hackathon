// Mutual Stake verifier harness. Fixed, and hashed into every run record.
//
// Input: one JSON message on stdin, {nonce, source, text}.
//   source  the agreed verifier: a function body that defines verify(text)
//   text    the deliverable. It is DATA: handed to verify() as a string, never evaluated, never obeyed.
// Output: exactly one line, MS-RESULT:<nonce>:<json>. The verifier cannot learn the nonce and cannot
// write to stdout, so nothing else can produce a line that counts.
//
// Deno runs this with no permissions: no files, no network, no environment, no subprocesses.

const write = Deno.stdout.writeSync.bind(Deno.stdout);
const encode = TextEncoder.prototype.encode.bind(new TextEncoder());
const stringify = JSON.stringify;

const message = JSON.parse(await new Response(Deno.stdin.readable).text());
const nonce = String(message.nonce);
const source = String(message.source);
const text = String(message.text);

function emit(result) {
  let line;
  try {
    line = stringify(result);
  } catch (_) {
    line = stringify({ ok: false, error: "the verifier returned a value that is not JSON" });
  }
  write(encode(`MS-RESULT:${nonce}:${line}\n`));
}

// Deterministic: no clock and no randomness, so the same text gets the same verdict, today and in a
// dispute replay.
const refuse = (what) =>
  function () {
    throw new Error(`nondeterministic: a verifier cannot use ${what}`);
  };
Math.random = refuse("Math.random");
const RealDate = Date;
globalThis.Date = class DeterministicDate extends RealDate {
  constructor(...args) {
    if (args.length === 0) throw new Error("nondeterministic: a verifier cannot read the current date");
    super(...args);
  }
  static now() {
    throw new Error("nondeterministic: a verifier cannot read the clock");
  }
};
if (globalThis.performance) performance.now = refuse("performance.now");
if (globalThis.crypto) {
  crypto.getRandomValues = refuse("crypto.getRandomValues");
  crypto.randomUUID = refuse("crypto.randomUUID");
}

// No I/O: console output is kept as a trace, and the Deno API is removed, together with Deno's Node
// compatibility globals (they load lazily, and need the Deno API to do it).
for (const name of ["process", "Buffer", "global", "setImmediate", "clearImmediate"]) delete globalThis[name];
const trace = [];
for (const level of ["log", "info", "warn", "error", "debug"]) {
  console[level] = (...args) => {
    if (trace.length < 50) trace.push(args.map((a) => String(a)).join(" ").slice(0, 500));
  };
}
delete globalThis.Deno;

try {
  const verify = new Function(`"use strict";\n${source}\n;return typeof verify === "function" ? verify : undefined;`)();
  if (typeof verify !== "function") {
    emit({ ok: false, error: "the verifier defines no verify(text) function" });
  } else {
    const result = verify(text);
    if (result !== null && typeof result === "object" && typeof result.then === "function") {
      emit({ ok: false, error: "verify(text) must return its result, not a promise" });
    } else {
      emit({ ok: true, result, trace });
    }
  }
} catch (e) {
  emit({ ok: false, error: `the verifier threw: ${String(e && e.message ? e.message : e).slice(0, 300)}`, trace });
}
