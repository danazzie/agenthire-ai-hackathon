import type { Contract, Hold, Offer } from '../api.ts'

export function hold(over: Partial<Hold> = {}): Hold {
  return {
    id: 'h', side: 'BUYER_FEE', payer: 'alex', amountMinor: 150, state: 'PENDING', jar: 'EMPTY',
    caption: 'not held yet', customId: 'sha256:abc', paypalOrderId: null, approveUrl: null,
    authorizationId: null, authorizationExpiresAt: null, captureId: null, capturedMinor: null,
    payoutBatchId: null, payoutItemId: null, needsAttention: false, attentionReason: null,
    ...over,
  }
}

export function offer(over: Partial<Offer> = {}): Offer {
  return {
    id: 'o', party: 'alex', kind: 'PROPOSE', feeMinor: 150, bondMinor: 40,
    deadlineAt: '2026-11-04T18:00:00Z', verifierId: 'v', note: null, createdAt: '2026-11-04T12:00:00Z',
    ...over,
  }
}

export function deal(over: Partial<Contract> = {}): Contract {
  return {
    id: 'c', state: 'DRAFT', sessionId: 's',
    buyer: { handle: 'alex', displayName: "Alex's agent" },
    seller: { handle: 'maya', displayName: "Maya's agent" },
    sellerType: 'AGENT', jobText: '50-word coffee-shop blurb for Beanhouse, no placeholder text',
    sentence: null, feeCapMinor: 200, feeMinor: 150, bondMinor: 40, currency: 'USD',
    deadlineAt: '2026-11-04T18:00:00Z', verifier: null, termsHash: null, termsDocument: null,
    holds: [], offers: [], transitions: [], ledger: [], delivery: null,
    awaitingConfirmation: false, settlementPlannedAt: null, settlementConfirmedAt: null,
    needsAttention: false, attentionReason: null, inFlight: [], outcome: null,
    createdAt: '2026-11-04T12:00:00Z', updatedAt: '2026-11-04T12:00:00Z',
    ...over,
  }
}
