#!/usr/bin/env node
// US-4 sandbox spike. Proves, against the real PayPal sandbox, the integration
// the whole thesis depends on:
//
//   1. Orders v2 AUTHORIZE on both sides (buyer fee, seller bond), with the
//      verifier SHA-256 as custom_id on both orders before either hold exists.
//   2. FAIL branch: void the fee (the buyer is never charged), capture the bond,
//      read the captured amount back, and pay exactly that to the buyer with
//      Payouts v1.
//
// It also probes PayPal-Request-Id idempotency (US-17). The platform is the payee
// on both orders, so every capture lands on the platform and any dispute stays
// answerable with the platform's own credentials (US-6). PASS mirrors this run:
// capture the fee, pay it out to the seller, void the bond.
//
//   node docs/spikes/us4-sandbox.mjs          create holds, wait for approval, settle
//   node docs/spikes/us4-sandbox.mjs settle   resume a run whose holds are approved
//
// Reads PAYPAL_* from the repo-root .env. Sandbox only: any other host is refused.

import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const statePath = join(here, '.us4-state.json');
const logPath = join(here, 'us4-sandbox-run.json');
const reportPath = join(here, 'US-4-sandbox-run.md');

if (existsSync(join(root, '.env'))) process.loadEnvFile(join(root, '.env'));

function die(message) {
  console.error(`\nSTOP: ${message}`);
  if (log.run) writeReport(message);
  process.exit(1);
}

function env(key, fallback) {
  const value = process.env[key] || fallback;
  if (!value) die(`no_credentials: ${key} is ${existsSync(join(root, '.env')) ? 'empty in .env' : 'not set (copy .env.example to .env)'}`);
  return value;
}

const log = { run: null, startedAt: new Date().toISOString(), finishedAt: null, verifierSha256: null, app: null, calls: [], checks: [] };

const BASE = env('PAYPAL_BASE_URL', 'https://api-m.sandbox.paypal.com');
if (new URL(BASE).hostname !== 'api-m.sandbox.paypal.com') die(`refusing to run against ${BASE}: sandbox only`);
const CLIENT_ID = env('PAYPAL_CLIENT_ID');
const CLIENT_SECRET = env('PAYPAL_CLIENT_SECRET');
const BUYER = env('PAYPAL_BUYER_EMAIL');
const SELLER = env('PAYPAL_SELLER_EMAIL');
const PORT = 8765;

// Stand-in verifier source. The real verifier is compiled per job; here only its
// hash matters: it is written on both orders as custom_id before either hold.
const VERIFIER_SOURCE = `// Mutual Stake US-4 spike stand-in for the US-1 test:
//   words == 50 AND language == en AND not placeholder(text)
export function verify(text) { throw new Error('stand-in: compiled per job'); }
`;
const HASH = createHash('sha256').update(VERIFIER_SOURCE, 'utf8').digest('hex');
log.verifierSha256 = HASH;

// Money is integer minor units (BigInt), never a float.
const toValue = (minor) => `${minor / 100n}.${String(minor % 100n).padStart(2, '0')}`;
function toMinor(value) {
  const m = /^(\d+)\.(\d{2})$/.exec(value ?? '');
  if (!m) die(`unexpected amount format: ${value}`);
  return BigInt(m[1]) * 100n + BigInt(m[2]);
}

const HOLDS = [
  { key: 'FEE', side: 'BUYER_FEE', payer: 'buyer', minor: 150n, payee: null,
    description: 'Mutual Stake buyer fee hold (US-4 spike)', plan: 'void: FAIL branch, buyer never charged' },
  { key: 'BOND', side: 'SELLER_BOND', payer: 'seller', minor: 40n, payee: null,
    description: 'Mutual Stake seller bond hold (US-4 spike)', plan: 'capture, then Payouts v1 to the buyer' },
];
const holdDef = (key) => HOLDS.find((h) => h.key === key);

// Personal details of sandbox payers stay out of the committed log; ids stay in.
function redact(value) {
  if (typeof value === 'string') {
    if (value === BUYER) return '<buyer email>';
    if (value === SELLER) return '<seller email>';
    if (/@business\.example\.com$/.test(value)) return '<platform email>';
    return /@personal\.example\.com$/.test(value) ? '<sandbox email>' : value;
  }
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      if (k === 'access_token') continue;
      if (['name', 'address', 'phone', 'phone_number', 'birth_date', 'shipping'].includes(k) && typeof v === 'object') continue;
      out[k] = redact(v);
    }
    return out;
  }
  return value;
}

let token = null;

async function paypal(step, method, path, { body, requestId, form, quiet } = {}) {
  const headers = {
    Authorization: form ? `Basic ${Buffer.from(`${CLIENT_ID}:${CLIENT_SECRET}`).toString('base64')}` : `Bearer ${token}`,
  };
  if (form) headers['Content-Type'] = 'application/x-www-form-urlencoded';
  else {
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    headers.Prefer = 'return=representation';
  }
  if (requestId) headers['PayPal-Request-Id'] = requestId;
  const res = await fetch(BASE + path, { method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)) });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
  const debugId = res.headers.get('paypal-debug-id');
  if (!quiet) {
    log.calls.push({ step, at: new Date().toISOString(), method, path, requestId: requestId ?? null, status: res.status, debugId, body: redact(json) });
    const why = res.ok ? '' : `  debug_id=${debugId} ${json?.name ?? ''} ${json?.details?.[0]?.issue ?? json?.message ?? ''}`;
    console.log(`${res.ok ? 'ok ' : 'ERR'} ${res.status} ${method} ${path}${requestId ? `  [${requestId}]` : ''}${why}`);
  }
  return { ok: res.ok, status: res.status, body: json, debugId };
}

function check(name, passed, evidence) {
  log.checks.push({ name, passed: Boolean(passed), evidence: String(evidence ?? '') });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${evidence ? `  (${evidence})` : ''}`);
  return Boolean(passed);
}

async function authenticate() {
  const r = await paypal('oauth token', 'POST', '/v1/oauth2/token', { form: 'grant_type=client_credentials' });
  if (!r.ok) die(`sandbox_rejected: no access token for this client id/secret (debug_id ${r.debugId})`);
  token = r.body.access_token;
  const scopes = String(r.body.scope ?? '').split(' ');
  log.app = { appId: r.body.app_id ?? null, hasPayoutsScope: scopes.some((s) => s.endsWith('/payouts')), scopeCount: scopes.length };
  if (!log.app.hasPayoutsScope) console.log('note: token has no payouts scope; Payouts may be disabled on this sandbox app');
}

function orderBody(h) {
  return {
    intent: 'AUTHORIZE',
    purchase_units: [{
      reference_id: h.side,
      description: h.description,
      custom_id: HASH,
      amount: { currency_code: 'USD', value: toValue(h.minor) },
      ...(h.payee ? { payee: { email_address: h.payee } } : {}),
    }],
    payment_source: { paypal: { experience_context: {
      brand_name: 'Mutual Stake (sandbox)',
      shipping_preference: 'NO_SHIPPING',
      user_action: 'CONTINUE',
      landing_page: 'LOGIN',
      return_url: `http://localhost:${PORT}/approved?hold=${h.key}`,
      cancel_url: `http://localhost:${PORT}/cancelled?hold=${h.key}`,
    } } },
  };
}

async function create() {
  const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15);
  const run = `us4-${stamp}-${randomBytes(3).toString('hex')}`;
  log.run = run;
  console.log(`run ${run}\nverifier sha256 ${HASH}\n`);
  await authenticate();

  const state = { run, startedAt: log.startedAt, verifierSha256: HASH, holds: {} };
  for (const h of HOLDS) {
    const r = await paypal(`create ${h.key} order`, 'POST', '/v2/checkout/orders', { body: orderBody(h), requestId: `${run}:${h.key}:create` });
    if (!r.ok) {
      state.holds[h.key] = { error: r.body?.details?.[0]?.issue ?? r.body?.name ?? String(r.status), debugId: r.debugId };
      saveState(state);
      die(`sandbox_rejected: could not create the ${h.key} order (debug_id ${r.debugId})`);
    }
    const approveUrl = r.body.links?.find((l) => l.rel === 'payer-action' || l.rel === 'approve')?.href;
    state.holds[h.key] = { orderId: r.body.id, createStatus: r.body.status, approveUrl, customId: r.body.purchase_units?.[0]?.custom_id ?? null };
  }
  check('Verifier hash is on both orders (custom_id) before either hold exists',
    state.holds.FEE.customId === HASH && state.holds.BOND.customId === HASH,
    `FEE ${state.holds.FEE.orderId}, BOND ${state.holds.BOND.orderId}`);

  const replay = await paypal('create FEE order (replay, same request id)', 'POST', '/v2/checkout/orders', { body: orderBody(HOLDS[0]), requestId: `${run}:FEE:create` });
  check('Replaying order create with the same PayPal-Request-Id returns the same order, not a second one',
    replay.ok && replay.body?.id === state.holds.FEE.orderId, `replay returned ${replay.body?.id ?? replay.status}`);

  saveState(state);
  return state;
}

function saveState(state) {
  state.checks = log.checks;
  state.calls = log.calls;
  state.app = log.app;
  writeFileSync(statePath, JSON.stringify(state, null, 2));
}

function loadState() {
  if (!existsSync(statePath)) die('nothing to settle: run without arguments first');
  const state = JSON.parse(readFileSync(statePath, 'utf8'));
  log.run = state.run;
  log.startedAt = state.startedAt;
  log.checks = state.checks ?? [];
  log.calls = state.calls ?? [];
  log.app = state.app ?? null;
  if (state.verifierSha256 !== HASH) die('verifier stand-in changed since the orders were created');
  return state;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitForApproval(state) {
  const pending = new Set(Object.keys(state.holds).filter((k) => state.holds[k].orderId));
  const server = createServer((req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const hold = url.searchParams.get('hold') ?? '?';
    const approved = url.pathname === '/approved';
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`<!doctype html><meta charset="utf-8"><title>Mutual Stake spike</title>
<body style="font:16px system-ui;margin:3rem;max-width:36rem">
<h1>${approved ? 'Hold approved' : 'Approval cancelled'}: ${hold.replace(/[^A-Z_]/g, '')}</h1>
<p>${approved ? 'Nothing has been charged. The spike checks the status with PayPal itself, not this page.' : 'The spike will keep waiting. Open the link again to approve.'}</p>
<p>You can close this tab.</p></body>`);
  });
  // The return page is cosmetic: approval is read from PayPal, so a busy port only costs the page.
  server.on('error', (e) => console.log(`note: no local return page (${e.code}); approvals still register with PayPal`));
  await new Promise((resolve) => { server.once('listening', resolve); server.once('error', resolve); server.listen(PORT, '127.0.0.1'); });

  console.log(`\nApprove the FEE as the BUYER sandbox account (${BUYER}):\n  ${state.holds.FEE.approveUrl}`);
  console.log(`\nThen log out of sandbox PayPal (or use a private window) and approve the BOND as the SELLER (${SELLER}):\n  ${state.holds.BOND.approveUrl}`);
  console.log('\nWaiting for PayPal to report each order APPROVED (checked by GET order, every 3 s, up to 20 min)...');

  const deadline = Date.now() + 20 * 60 * 1000;
  while (pending.size && Date.now() < deadline) {
    for (const k of [...pending]) {
      const r = await paypal(`poll ${k}`, 'GET', `/v2/checkout/orders/${state.holds[k].orderId}`, { quiet: true });
      if (r.body?.status === 'APPROVED') { pending.delete(k); console.log(`approved: ${k} (${state.holds[k].orderId})`); }
    }
    if (pending.size) await sleep(3000);
  }
  server.close();
  if (pending.size) {
    saveState(state);
    console.log(`\nStill waiting on: ${[...pending].join(', ')}. Approve, then run: node docs/spikes/us4-sandbox.mjs settle`);
    process.exit(2);
  }
}

async function settle(state) {
  if (!token) await authenticate();
  const H = state.holds;
  const run = state.run;
  const stop = (message) => { saveState(state); die(message); };

  // 1. Approved, and by the right account.
  for (const [k, h] of Object.entries(H)) {
    if (!h.orderId) continue;
    const r = await paypal(`get ${k} order (approved?)`, 'GET', `/v2/checkout/orders/${h.orderId}`);
    const payerEmail = r.body?.payment_source?.paypal?.email_address ?? r.body?.payer?.email_address ?? '';
    const expected = holdDef(k).payer === 'buyer' ? BUYER : SELLER;
    h.approvedStatus = r.body?.status;
    h.payerIsExpected = payerEmail.toLowerCase() === expected.toLowerCase();
    h.payerId = r.body?.payer?.payer_id ?? r.body?.payment_source?.paypal?.account_id ?? null;
    check(`${k} approved by the ${holdDef(k).payer} sandbox account`, h.approvedStatus === 'APPROVED' && h.payerIsExpected,
      `status ${h.approvedStatus}, payer ${h.payerIsExpected ? 'matches' : 'does NOT match'} ${holdDef(k).payer}`);
  }
  for (const k of ['FEE', 'BOND']) {
    if (H[k].approvedStatus !== 'APPROVED' || !H[k].payerIsExpected) stop(`${k} is not approved by the ${holdDef(k).payer} account; nothing was authorized`);
  }

  // 2. Authorize: this places the hold. Nothing is captured.
  for (const [k, h] of Object.entries(H)) {
    if (h.approvedStatus !== 'APPROVED' || !h.payerIsExpected) continue;
    const r = await paypal(`authorize ${k}`, 'POST', `/v2/checkout/orders/${h.orderId}/authorize`, { body: {}, requestId: `${run}:${k}:authorize` });
    const a = r.body?.purchase_units?.[0]?.payments?.authorizations?.[0];
    if (!r.ok || !a) { h.authorizeError = r.body?.details?.[0]?.issue ?? r.body?.name ?? String(r.status); h.authorizeDebugId = r.debugId; continue; }
    Object.assign(h, { authorizationId: a.id, authStatus: a.status, authExpires: a.expiration_time ?? null, authCustomId: a.custom_id ?? null });
  }
  if (!H.FEE.authorizationId || !H.BOND.authorizationId) stop('sandbox_rejected: could not authorize both holds');
  check('Both holds are authorizations, neither captured', H.FEE.authStatus === 'CREATED' && H.BOND.authStatus === 'CREATED',
    `FEE auth ${H.FEE.authorizationId} ${H.FEE.authStatus}, BOND auth ${H.BOND.authorizationId} ${H.BOND.authStatus}`);
  check('custom_id on both authorizations equals the verifier SHA-256', H.FEE.authCustomId === HASH && H.BOND.authCustomId === HASH,
    `FEE ${H.FEE.authCustomId === HASH ? 'match' : H.FEE.authCustomId}, BOND ${H.BOND.authCustomId === HASH ? 'match' : H.BOND.authCustomId}`);

  // 3. FAIL branch, buyer side: void the fee. Void is not a refund; nothing was taken.
  const v = await paypal('void FEE authorization', 'POST', `/v2/payments/authorizations/${H.FEE.authorizationId}/void`, { requestId: `${run}:FEE:void` });
  const vReplay = await paypal('void FEE authorization (replay, same request id)', 'POST', `/v2/payments/authorizations/${H.FEE.authorizationId}/void`, { requestId: `${run}:FEE:void` });
  const vGet = await paypal('get FEE authorization', 'GET', `/v2/payments/authorizations/${H.FEE.authorizationId}`);
  H.FEE.finalAuthStatus = vGet.body?.status;
  check('FEE authorization is VOIDED (buyer never charged)', v.ok && H.FEE.finalAuthStatus === 'VOIDED', `status ${H.FEE.finalAuthStatus}`);
  // A replay may echo the original result or refuse with AUTHORIZATION_ALREADY_VOIDED; both mean
  // "done", and the control-plane client must treat them the same.
  const vReplayIssue = vReplay.body?.details?.[0]?.issue ?? null;
  check('Replaying the void with the same PayPal-Request-Id is harmless', vReplay.ok || vReplayIssue === 'AUTHORIZATION_ALREADY_VOIDED',
    vReplay.ok ? `replay HTTP ${vReplay.status}` : `replay refused: HTTP ${vReplay.status} ${vReplayIssue ?? vReplay.body?.name ?? ''}`);
  const late = await paypal('capture voided FEE (must be refused)', 'POST', `/v2/payments/authorizations/${H.FEE.authorizationId}/capture`,
    { body: { final_capture: true }, requestId: `${run}:FEE:capture-after-void` });
  check('A voided fee cannot be captured afterwards', !late.ok, `HTTP ${late.status} ${late.body?.details?.[0]?.issue ?? late.body?.name ?? ''}`);

  // 4. FAIL branch, seller side: capture the bond (lands on the platform account).
  const c = await paypal('capture BOND authorization', 'POST', `/v2/payments/authorizations/${H.BOND.authorizationId}/capture`,
    { body: { final_capture: true }, requestId: `${run}:BOND:capture` });
  if (!c.ok) stop(`sandbox_rejected: bond capture failed (debug_id ${c.debugId})`);
  H.BOND.captureId = c.body.id;
  H.BOND.captureStatus = c.body.status;
  const cReplay = await paypal('capture BOND authorization (replay, same request id)', 'POST', `/v2/payments/authorizations/${H.BOND.authorizationId}/capture`,
    { body: { final_capture: true }, requestId: `${run}:BOND:capture` });
  check('Replaying the capture with the same PayPal-Request-Id returns the same capture, not a second one',
    cReplay.ok && cReplay.body?.id === H.BOND.captureId, `replay returned ${cReplay.body?.id ?? cReplay.status}`);

  // US-17: the payout amount is read back from the capture, never recomputed.
  const cGet = await paypal('get BOND capture', 'GET', `/v2/payments/captures/${H.BOND.captureId}`);
  const captured = cGet.body?.amount;
  H.BOND.capturedMinor = String(toMinor(captured?.value));
  H.BOND.captureCustomId = cGet.body?.custom_id ?? null;
  H.BOND.breakdown = cGet.body?.seller_receivable_breakdown ?? null;
  H.BOND.captureStatus = cGet.body?.status ?? H.BOND.captureStatus;
  check('custom_id on the bond capture equals the verifier SHA-256', H.BOND.captureCustomId === HASH, H.BOND.captureCustomId === HASH ? 'match' : H.BOND.captureCustomId);
  // No payout until the bond has actually landed. A payout is funded from the platform's
  // balance, so paying against a PENDING capture would prove nothing about the bond.
  const fullBond = H.BOND.captureStatus === 'COMPLETED' && toMinor(captured.value) === holdDef('BOND').minor;
  check('Bond capture is COMPLETED for the full bond', fullBond,
    `capture ${H.BOND.captureId} ${H.BOND.captureStatus} ${captured?.value} ${captured?.currency_code}${cGet.body?.status_details?.reason ? ` reason ${cGet.body.status_details.reason}` : ''}`);
  if (!fullBond) stop(`needs attention: bond capture is ${H.BOND.captureStatus}${cGet.body?.status_details?.reason ? ` (${cGet.body.status_details.reason})` : ''}; no payout sent`);

  // 5. Pay the captured bond to the buyer (Payouts v1: amount.currency, not currency_code).
  const payoutBody = {
    sender_batch_header: {
      sender_batch_id: `${run}-BOND-payout`,
      email_subject: 'Mutual Stake: the seller bond is yours',
      email_message: 'The deliverable failed its agreed test, so the seller bond was captured and paid to you. Sandbox only.',
    },
    items: [{
      recipient_type: 'EMAIL',
      receiver: BUYER,
      amount: { value: captured.value, currency: captured.currency_code },
      sender_item_id: `${run}-BOND-payout-1`,
      note: 'Seller bond paid to the buyer as compensation (Mutual Stake sandbox).',
    }],
  };
  const p = await paypal('payout bond to buyer', 'POST', '/v1/payments/payouts', { body: payoutBody, requestId: `${run}:BOND:payout` });
  if (!p.ok) stop(`sandbox_rejected: payout failed after the bond was captured: needs attention (debug_id ${p.debugId})`);
  H.BOND.payoutBatchId = p.body?.batch_header?.payout_batch_id;
  const pReplay = await paypal('payout bond to buyer (replay, same request id and sender_batch_id)', 'POST', '/v1/payments/payouts', { body: payoutBody, requestId: `${run}:BOND:payout` });
  H.BOND.payoutReplay = { status: pReplay.status, batchId: pReplay.body?.batch_header?.payout_batch_id ?? null, error: pReplay.ok ? null : (pReplay.body?.name ?? null) };
  check('Replaying the payout cannot pay twice', (pReplay.ok && H.BOND.payoutReplay.batchId === H.BOND.payoutBatchId) || !pReplay.ok,
    pReplay.ok ? `replay returned batch ${H.BOND.payoutReplay.batchId}` : `replay refused: HTTP ${pReplay.status} ${pReplay.body?.name ?? ''}`);

  // Poll quietly until the batch settles (up to 2 min), then log one final read.
  for (let i = 0; i < 40; i++) {
    const g = await paypal('poll payout batch', 'GET', `/v1/payments/payouts/${H.BOND.payoutBatchId}`, { quiet: true });
    const status = g.body?.items?.[0]?.transaction_status;
    if (['SUCCESS', 'DENIED', 'CANCELED'].includes(g.body?.batch_header?.batch_status) && status && status !== 'PENDING') break;
    await sleep(3000);
  }
  const pGet = await paypal('get payout batch (final)', 'GET', `/v1/payments/payouts/${H.BOND.payoutBatchId}`);
  const batchStatus = pGet.body?.batch_header?.batch_status ?? null;
  const item = pGet.body?.items?.[0] ?? null;
  Object.assign(H.BOND, {
    payoutBatchStatus: batchStatus,
    payoutItemId: item?.payout_item_id ?? null,
    payoutTransactionId: item?.transaction_id ?? null,
    payoutItemStatus: item?.transaction_status ?? null,
    payoutAmount: item?.payout_item?.amount?.value ?? null,
  });
  check('Bond paid out to the buyer for exactly the captured amount',
    H.BOND.payoutItemStatus === 'SUCCESS' && H.BOND.payoutAmount === captured.value,
    `batch ${H.BOND.payoutBatchId} ${batchStatus}, item ${H.BOND.payoutItemId} ${H.BOND.payoutItemStatus} ${H.BOND.payoutAmount}`);

  // 6. Final state of every order, as PayPal reports it.
  for (const [k, h] of Object.entries(H)) {
    if (!h.orderId) continue;
    const g = await paypal(`get ${k} order (final)`, 'GET', `/v2/checkout/orders/${h.orderId}`);
    const pay = g.body?.purchase_units?.[0]?.payments ?? {};
    h.final = { order: g.body?.status, authorization: pay.authorizations?.[0]?.status ?? null, capture: pay.captures?.[0]?.status ?? null };
  }

  saveState(state);
  const gate = ['FEE authorization is VOIDED (buyer never charged)', 'Bond capture is COMPLETED for the full bond', 'Bond paid out to the buyer for exactly the captured amount',
    'Both holds are authorizations, neither captured', 'Verifier hash is on both orders (custom_id) before either hold exists']
    .every((name) => log.checks.find((c) => c.name === name)?.passed);
  writeReport(gate ? null : 'one or more gate checks failed');
  console.log(`\n${gate ? 'GATE PASSED' : 'GATE FAILED'}: report at docs/spikes/US-4-sandbox-run.md`);
  process.exit(gate ? 0 : 1);
}

function writeReport(failure) {
  log.finishedAt = new Date().toISOString();
  let state = null;
  try { state = JSON.parse(readFileSync(statePath, 'utf8')); } catch { /* no holds yet */ }
  writeFileSync(logPath, JSON.stringify({ ...log, holds: state ? redact(state.holds) : null }, null, 2) + '\n');

  const H = state?.holds ?? {};
  const cell = (v) => (v === null || v === undefined || v === '' ? '—' : `\`${v}\``);
  const rows = HOLDS.filter((h) => H[h.key]).map((h) => {
    const s = H[h.key];
    const outcome = s.final ? `order ${s.final.order}, auth ${s.final.authorization ?? '—'}${s.final.capture ? `, capture ${s.final.capture}` : ''}` : (s.error ?? s.authorizeError ?? s.approvedStatus ?? 'not settled');
    return `| ${h.key} | ${h.payer} → ${h.payee ? 'seller' : 'platform'} | ${toValue(h.minor)} USD | ${cell(s.orderId)} | ${cell(s.authorizationId)} | ${cell(s.captureId)} | ${outcome} |`;
  });
  const B = H.BOND ?? {};
  const report = `# US-4 sandbox spike: ${log.run}

${failure ? `**GATE FAILED:** ${failure}` : '**GATE PASSED.** Both holds were placed, the fee was voided, the bond was captured and paid out to the buyer, all in the PayPal sandbox.'}

- Started ${log.startedAt}, finished ${log.finishedAt}
- PayPal sandbox app ${cell(log.app?.appId)}; Payouts scope on token: ${log.app?.hasPayoutsScope ? 'yes' : 'no'}
- Verifier SHA-256 (spike stand-in source), written as \`custom_id\` on every order: \`${log.verifierSha256}\`
- Script: [us4-sandbox.mjs](us4-sandbox.mjs). Every call, status and PayPal debug id: [us4-sandbox-run.json](us4-sandbox-run.json) (payer names, emails and addresses redacted; ids kept).

## Holds

| Hold | Payer → payee | Amount | Order | Authorization | Capture | Final state (GET order) |
|---|---|---|---|---|---|---|
${rows.join('\n')}

## Bond compensation (FAIL branch)

| Step | Id | Status |
|---|---|---|
| Capture bond | ${cell(B.captureId)} | ${B.captureStatus ?? '—'} |
| Amount read back from the capture | ${B.capturedMinor ? `${toValue(BigInt(B.capturedMinor))} USD` : '—'} | — |
| Payouts v1 batch to buyer | ${cell(B.payoutBatchId)} | ${B.payoutBatchStatus ?? '—'} |
| Payout item | ${cell(B.payoutItemId)} | ${B.payoutItemStatus ?? '—'} (${B.payoutAmount ?? '—'} USD) |
| Payout transaction | ${cell(B.payoutTransactionId)} | — |
| Payout replay (same request id + sender_batch_id) | ${cell(B.payoutReplay?.batchId)} | ${B.payoutReplay ? `HTTP ${B.payoutReplay.status}${B.payoutReplay.error ? ` ${B.payoutReplay.error}` : ''}` : '—'} |

PayPal's fee on the bond capture (from \`seller_receivable_breakdown\`): ${B.breakdown ? `gross ${B.breakdown.gross_amount?.value}, fee ${B.breakdown.paypal_fee?.value ?? '—'}, net ${B.breakdown.net_amount?.value ?? '—'}` : '—'}. The payout pays the gross bond; the platform absorbs the fee.

## Checks

| Result | Check | Evidence |
|---|---|---|
${log.checks.map((c) => `| ${c.passed ? 'PASS' : 'FAIL'} | ${c.name} | ${c.evidence.replace(/\|/g, '\\|')} |`).join('\n')}

## Why the platform is the payee on both orders

Every capture lands on the platform's sandbox business account first (US-4 says so for the bond; PASS does the same for the fee, then pays the seller with Payouts v1). That keeps any PayPal dispute on a capture the platform owns, so the appeal (US-6) can answer it with \`provide-evidence\` using the platform's own credentials. A fee payable straight to the seller would put a buyer's dispute in the seller's account, out of the platform's reach.
`;
  writeFileSync(reportPath, report);
}

const mode = process.argv[2] ?? 'run';
if (mode === 'settle') {
  await settle(loadState());
} else if (mode === 'run') {
  const state = await create();
  await waitForApproval(state);
  await settle(state);
} else {
  die(`unknown mode ${mode}; use no argument or "settle"`);
}
