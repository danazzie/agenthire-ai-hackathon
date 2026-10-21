#!/usr/bin/env node
// Runs the Mutual Stake demo against a running control plane and the real PayPal sandbox: the same job
// twice, once delivered badly (FAIL: fee voided, bond captured and paid to the buyer) and once well (PASS:
// fee captured and paid to the seller, bond voided). Both must show real sandbox ids.
//
//   node docs/demo/sandbox-run.mjs prepare   compile the test, negotiate two deals, print the 4 approval links
//   node docs/demo/sandbox-run.mjs finish    deliver both, confirm the session's first capture, settle, report
//
// The approval clicks are real sandbox checkouts and cannot be automated (US-2). Negotiation is scripted here;
// the Claude agents replace it later. API: MUTUALSTAKE_API (default http://localhost:8088/api).

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const API = process.env.MUTUALSTAKE_API ?? 'http://localhost:8088/api';
const statePath = join(here, '.sandbox-run-state.json');
const reportPath = join(here, 'sandbox-run.md');

const JOB = '50-word coffee-shop blurb, max $2, by 18:00, no lorem ipsum';
const TEST = { checks: [{ type: 'word_count', equals: 50 }, { type: 'language', equals: 'en' }, { type: 'not_placeholder' }] };
const prototypes = readFileSync(join(here, '..', '..', 'PROTOTYPES.md'), 'utf8');
const PASS_TEXT = /\| Pass \| \*(.+?)\* \(50 words\) \|/.exec(prototypes)[1];
const FAIL_TEXT = /\| Fail \| \*(.+?)\* \(12 words\) \|/.exec(prototypes)[1];

async function api(method, path, { party, body } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (party) headers['X-Party'] = party;
  const res = await fetch(API + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const json = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${json?.code ?? ''}: ${json?.detail ?? text}`);
  return json;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(what, read, done, { seconds = 600, every = 2000 } = {}) {
  const until = Date.now() + seconds * 1000;
  let last;
  while (Date.now() < until) {
    try {
      last = await read();
    } catch (e) {
      // The control plane restarting is not a failed run: keep waiting. Any HTTP error still stops it.
      if (!(e instanceof TypeError)) throw e;
      await sleep(every);
      continue;
    }
    if (done(last)) return last;
    await sleep(every);
  }
  throw new Error(`timed out waiting for ${what}`);
}

async function negotiate(verifierId, label) {
  const deal = await api('POST', '/contracts', { party: 'alex', body: { seller: 'maya', jobText: JOB, feeCapMinor: 200 } });
  const deadline = new Date(Date.now() + 3 * 3600 * 1000).toISOString();
  const offer = (party, kind, note) => api('POST', `/contracts/${deal.id}/offers`, {
    party, body: { kind, feeMinor: 150, bondMinor: 40, deadlineAt: deadline, verifierId, note },
  });
  await offer('alex', 'PROPOSE', `${label}: $1.50 and the test above; bond $0.40`);
  await offer('maya', 'ACCEPT', 'Accepted. $1.50 / bond $0.40');
  const locked = await offer('alex', 'ACCEPT', null);
  if (locked.state !== 'TERMS_LOCKED') throw new Error(`${label} did not lock: ${locked.state}`);
  return waitFor(`${label} orders`, () => api('GET', `/contracts/${deal.id}`), (c) => c.holds.every((h) => h.paypalOrderId && h.approveUrl), { seconds: 60 });
}

async function prepare() {
  const status = await api('GET', '/status');
  if (!status.paypalConfigured) throw new Error('no_credentials: the control plane has no PayPal sandbox credentials');
  const verifier = await api('POST', '/verifiers/compile', { body: { jobText: JOB, test: TEST } });
  console.log(`verifier sha256 ${verifier.sha256}`);
  const fail = await negotiate(verifier.id, 'Run 1 (junk delivery)');
  const pass = await negotiate(verifier.id, 'Run 2 (real blurb)');
  writeFileSync(statePath, JSON.stringify({ verifier: { id: verifier.id, sha256: verifier.sha256 }, fail: fail.id, pass: pass.id }, null, 2));
  const link = (c, side) => c.holds.find((h) => h.side === side).approveUrl;
  console.log(`\nApprove as the BUYER (alex), both fees:\n  run 1 fee  ${link(fail, 'BUYER_FEE')}\n  run 2 fee  ${link(pass, 'BUYER_FEE')}`);
  console.log(`\nThen as the SELLER (maya), both bonds:\n  run 1 bond ${link(fail, 'SELLER_BOND')}\n  run 2 bond ${link(pass, 'SELLER_BOND')}`);
  console.log('\nThen: node docs/demo/sandbox-run.mjs finish');
}

async function finish() {
  if (!existsSync(statePath)) throw new Error('run prepare first');
  const state = JSON.parse(readFileSync(statePath, 'utf8'));
  const get = (id) => api('GET', `/contracts/${id}`);
  const held = (c) => c.state === 'BOTH_HELD' || c.holds.some((h) => h.needsAttention);
  for (const [label, id] of [['run 1', state.fail], ['run 2', state.pass]]) {
    const c = await waitFor(`${label} BOTH_HELD`, () => get(id), held, { seconds: 7200 });
    if (c.state !== 'BOTH_HELD') throw new Error(`${label}: ${c.holds.map((h) => `${h.side} ${h.state} ${h.attentionReason ?? ''}`).join('; ')}`);
    console.log(`${label}: both holds placed`);
  }

  // Run 1: junk. The verdict is FAIL; this session's first capture waits for the operator's Confirm (US-16),
  // which this driver presses on the operator's behalf.
  await api('POST', `/contracts/${state.fail}/deliveries`, { party: 'maya', body: { text: FAIL_TEXT } });
  const failed = await waitFor('run 1 verdict', () => get(state.fail), (c) => c.state !== 'DELIVERED', { seconds: 120 });
  console.log(`run 1: ${failed.state}: ${failed.delivery?.summary}; awaiting confirmation: ${failed.awaitingConfirmation}`);
  if (failed.awaitingConfirmation) {
    await api('POST', `/contracts/${state.fail}/confirm`);
    console.log("run 1: first capture of the session released by the operator's Confirm, pressed by this driver (US-16)");
  }

  // Run 2: the 50-word blurb, delivered now rather than after run 1's payout lands: run 2's deadline is hard
  // (past it, the deal expires and the bond is captured), run 1's payout is not. PASS settles on its own
  // inside the confirmed session.
  await api('POST', `/contracts/${state.pass}/deliveries`, { party: 'maya', body: { text: PASS_TEXT } });
  const passed = await waitFor('run 2 verdict', () => get(state.pass), (c) => c.state !== 'DELIVERED', { seconds: 120 });
  console.log(`run 2: ${passed.state}: ${passed.delivery?.summary}; awaiting confirmation: ${passed.awaitingConfirmation}`);

  // Both settle in parallel. A slow sandbox payout is reported as it stands, not allowed to sink the other run.
  const done = (c) => c.state === 'SETTLED' || c.holds.some((h) => h.needsAttention);
  const settle = (label, id) =>
    waitFor(`${label} SETTLED`, () => get(id), done, { seconds: 1800 }).catch(async (e) => {
      console.warn(`${label}: ${e.message}; reporting its state as it stands`);
      return get(id);
    });
  const [settled1, settled2] = await Promise.all([settle('run 1', state.fail), settle('run 2', state.pass)]);

  const calls1 = await api('GET', `/contracts/${state.fail}/paypal-calls`);
  const calls2 = await api('GET', `/contracts/${state.pass}/paypal-calls`);
  writeReport(state, settled1, settled2, calls1, calls2);
  console.log(`\nrun 1 ${settled1.state}, run 2 ${settled2.state}. Report: docs/demo/sandbox-run.md`);
}

function writeReport(state, run1, run2, calls1, calls2) {
  const money = (m) => (m == null ? '—' : `$${(m / 100).toFixed(2)}`);
  const id = (v) => (v ? `\`${v}\`` : '—');
  const section = (title, c, calls) => `## ${title}

Contract \`${c.id}\`, final state **${c.state}**. Verdict: **${c.delivery?.verdict ?? '—'}** (${c.delivery?.summary ?? '—'}).

| Jar | Payer | Amount | Shows | Order | Authorization | Capture | Payout batch | Payout item |
|---|---|---|---|---|---|---|---|---|
${c.holds.map((h) => `| ${h.side === 'BUYER_FEE' ? 'Buyer fee' : 'Seller bond'} | ${h.payer} | ${money(h.amountMinor)} | **${h.jar}** | ${id(h.paypalOrderId)} | ${id(h.authorizationId)} | ${id(h.captureId)} | ${id(h.payoutBatchId)} | ${id(h.payoutItemId)} |`).join('\n')}

Ledger (PayPal-confirmed only):

| Event | Direction | Amount | Counterparty | PayPal id |
|---|---|---|---|---|
${c.ledger.map((e) => `| ${e.event} | ${e.direction} | ${money(e.amountMinor)} | ${e.counterparty} | \`${e.paypalRef}\` |`).join('\n')}

PayPal calls (writes), each with its PayPal-Request-Id and debug id:

| Hold | Call | HTTP | PayPal-Request-Id | debug id |
|---|---|---|---|---|
${calls.map((k) => `| ${k.side} | ${k.operation} | ${k.httpStatus ?? '—'} | ${id(k.requestId)} | ${id(k.debugId)} |`).join('\n')}

Transitions: ${c.transitions.map((t) => `${t.from}→${t.to}`).join(', ')}.
`;
  writeFileSync(reportPath, `# Sandbox run: the same job, twice

Run on ${new Date().toISOString()} against the PayPal sandbox. Verifier SHA-256 (\`custom_id\` on all four orders): \`${state.verifier.sha256}\`.

The four holds were approved by hand in PayPal sandbox checkout. Everything after that was scripted by \`docs/demo/sandbox-run.mjs\`, including the session's one Confirm (US-16), which the driver pressed on the operator's behalf ${run1.settlementConfirmedAt ? `at ${run1.settlementConfirmedAt} for run 1` : '(run 1 records no Confirm)'}. Run 2 ${run2.settlementConfirmedAt ? `also records a Confirm at ${run2.settlementConfirmedAt}` : 'settled inside the confirmed session without one'}.

${section('Run 1: junk delivery, FAIL', run1, calls1)}
${section('Run 2: the 50-word blurb, PASS', run2, calls2)}`);
}

const mode = process.argv[2];
if (mode === 'prepare') await prepare();
else if (mode === 'finish') await finish();
else {
  console.error('usage: node docs/demo/sandbox-run.mjs prepare|finish');
  process.exit(2);
}
