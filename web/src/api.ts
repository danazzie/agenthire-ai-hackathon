// The control plane's API, typed as the views it returns. Money is integer cents everywhere.

export type Jar = 'EMPTY' | 'HELD' | 'RELEASED' | 'CAPTURED' | 'PAID' | 'COMPENSATION'
export type Side = 'BUYER_FEE' | 'SELLER_BOND'

export interface Party { handle: string; displayName: string }

export interface Verifier { id: string; sha256: string; language: string; source: string; compiledAt: string; spec: string | null }

export interface Hold {
  id: string
  side: Side
  payer: string
  amountMinor: number
  state: string
  jar: Jar
  caption: string
  customId: string
  paypalOrderId: string | null
  approveUrl: string | null
  authorizationId: string | null
  authorizationExpiresAt: string | null
  captureId: string | null
  capturedMinor: number | null
  payoutBatchId: string | null
  payoutItemId: string | null
  needsAttention: boolean
  attentionReason: string | null
}

export interface Offer {
  id: string
  party: string
  kind: 'PROPOSE' | 'ACCEPT'
  feeMinor: number
  bondMinor: number
  deadlineAt: string
  verifierId: string
  note: string | null
  createdAt: string
}

export interface Check { name: string; passed: boolean; detail: string; expected: string | null; evidence: string | null }

export interface Run {
  id: string
  outcome: 'PASS' | 'FAIL' | 'ERROR'
  runtime: string
  harnessSha256: string
  sourceSha256: string
  inputSha256: string
  durationMs: number
  ranAt: string
  error: string | null
}

export interface Delivery {
  id: string
  submittedAt: string
  payloadSha256: string
  text: string
  verdict: 'PASS' | 'FAIL' | null
  failingCheck: string | null
  summary: string | null
  checks: Check[]
  injectionFlags: string[]
  verifiedAt: string | null
  run: Run | null
  attempts: number
  needsAttention: boolean
  attentionReason: string | null
}

export interface LedgerLine { event: string; direction: string; amountMinor: number; counterparty: string; paypalRef: string; occurredAt: string }

export interface InFlight { step: string; label: string; attempts: number; lastError: string | null }

export interface Contract {
  id: string
  state: string
  sessionId: string
  buyer: Party
  seller: Party
  sellerType: string
  jobText: string
  sentence: string | null
  feeCapMinor: number
  feeMinor: number | null
  bondMinor: number | null
  currency: string
  deadlineAt: string | null
  verifier: Verifier | null
  termsHash: string | null
  termsDocument: string | null
  holds: Hold[]
  offers: Offer[]
  transitions: { from: string; to: string; cause: string; occurredAt: string }[]
  ledger: LedgerLine[]
  delivery: Delivery | null
  awaitingConfirmation: boolean
  settlementPlannedAt: string | null
  settlementConfirmedAt: string | null
  needsAttention: boolean
  attentionReason: string | null
  inFlight: InFlight[]
  outcome: string | null
  createdAt: string
  updatedAt: string
}

export interface Session { id: string; startedAt: string; capMinor: number; maxJobs: number; firstCaptureConfirmedAt: string | null }

export interface Status { paypalConfigured: boolean; webhooksConfigured: boolean; agentsConfigured: boolean; parties: Party[]; session: Session; agentsHost?: 'in_process' | 'astropods'; runnerSeenAt?: string | null }

/** With the agents on Astropods: whether their runner has asked for work in the last minute. */
export function runnerConnected(status: Status): boolean {
  return !!status.runnerSeenAt && Date.now() - Date.parse(status.runnerSeenAt) < 60_000
}

export interface ResetResult { archivedSessionId: string; session: Session; cancelled: string[] }

export interface JobDraft { sentence: string; jobText: string; feeCapMinor: number; deadlineAt: string; verifier: Verifier }

export interface PayPalCall {
  side: Side
  operation: string
  method: string
  path: string
  requestId: string | null
  httpStatus: number | null
  debugId: string | null
  errorName?: string | null
  errorIssue?: string | null
}

/** A verifier run with nothing delivered and nothing at stake. */
export interface DryRun {
  outcome: 'PASS' | 'FAIL' | 'ERROR'
  checks: Check[]
  summary: string | null
  failingCheck: string | null
  error: string | null
  injectionFlags: string[]
  runtime: string
  harnessSha256: string
  sourceSha256: string
  durationMs: number
}

/** A refusal from the control plane, with its stable code (e.g. no_credentials, over_cap). */
export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(status: number, code: string, detail: string) {
    super(detail)
    this.status = status
    this.code = code
  }
}

// Local dev leaves this empty so Vite can proxy /api to the control plane.
// A production build with no VITE_API_URL still calls the deployed API.
const configured = (import.meta.env?.VITE_API_URL ?? '').replace(/\/$/, '')
const base = configured || (import.meta.env?.PROD ? 'https://hire-mind-api.onrender.com' : '')

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown, party?: string): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (party) headers['X-Party'] = party
  const res = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  const json = text ? JSON.parse(text) : null
  if (!res.ok) throw new ApiError(res.status, json?.code ?? 'error', json?.detail ?? res.statusText)
  return json as T
}

export const api = {
  status: () => call<Status>('GET', '/api/status'),
  contracts: () => call<Contract[]>('GET', '/api/contracts'),
  contract: (id: string) => call<Contract>('GET', `/api/contracts/${id}`),
  paypalCalls: (id: string, reads = false) => call<PayPalCall[]>('GET', `/api/contracts/${id}/paypal-calls${reads ? '?reads=true' : ''}`),
  verifier: (id: string) => call<Verifier>('GET', `/api/verifiers/${id}`),
  dryRun: (id: string, text: string) => call<DryRun>('POST', `/api/verifiers/${id}/dry-run`, { text }),
  compile: (jobText: string, test: unknown) => call<Verifier>('POST', '/api/verifiers/compile', { jobText, test }),
  open: (buyer: string, seller: string, jobText: string, feeCapMinor: number) =>
    call<Contract>('POST', '/api/contracts', { seller, jobText, feeCapMinor }, buyer),
  offer: (id: string, party: string, offer: { kind: 'PROPOSE' | 'ACCEPT'; feeMinor: number; bondMinor: number; deadlineAt: string; verifierId: string; note?: string }) =>
    call<Contract>('POST', `/api/contracts/${id}/offers`, offer, party),
  draftJob: (sentence: string) => call<JobDraft>('POST', '/api/agents/jobs/draft', { sentence }),
  startJob: (d: JobDraft) => call<Contract>('POST', '/api/agents/jobs', {
    sentence: d.sentence, jobText: d.jobText, feeCapMinor: d.feeCapMinor, deadlineAt: d.deadlineAt, verifierId: d.verifier.id,
  }),
  deliver: (id: string, mode: 'AGENT' | 'SCRIPTED_JUNK') => call<Contract>('POST', `/api/agents/contracts/${id}/deliver`, { mode }),
  deliverText: (id: string, party: string, text: string) => call<Contract>('POST', `/api/contracts/${id}/deliveries`, { text }, party),
  confirm: (id: string) => call<Contract>('POST', `/api/contracts/${id}/confirm`),
  cancel: (id: string, party: string) => call<Contract>('POST', `/api/contracts/${id}/cancel`, undefined, party),
  checkHold: (id: string) => call<{ hold: string; queued: string }>('POST', `/api/holds/${id}/check`),
  resetDemo: () => call<ResetResult>('POST', '/api/demo/reset'),
}
