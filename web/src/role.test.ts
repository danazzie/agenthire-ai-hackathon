import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Contract, Hold } from './api.ts'
import { dealPath, deskHome, nextAction, parsePath, waitingForApprove } from './role.ts'

const hold = (over: Partial<Hold>): Hold => ({
  id: 'h', side: 'BUYER_FEE', payer: 'alex', amountMinor: 150, state: 'PENDING', jar: 'EMPTY',
  caption: 'not held yet', customId: 'ab', paypalOrderId: null, approveUrl: null, authorizationId: null,
  authorizationExpiresAt: null, captureId: null, capturedMinor: null, payoutBatchId: null, payoutItemId: null,
  needsAttention: false, attentionReason: null, ...over,
})

const deal = (over: Partial<Contract>): Contract => ({
  id: 'c', state: 'DRAFT', sessionId: 's',
  buyer: { handle: 'alex', displayName: "Alex's agent" },
  seller: { handle: 'maya', displayName: "Maya's agent" },
  sellerType: 'AGENT', jobText: '50-word coffee-shop blurb', sentence: null, feeCapMinor: 200,
  feeMinor: 150, bondMinor: 40, currency: 'USD', deadlineAt: null, verifier: null, termsHash: null,
  termsDocument: null, holds: [], offers: [], transitions: [], ledger: [], delivery: null,
  awaitingConfirmation: false, settlementPlannedAt: null, settlementConfirmedAt: null,
  needsAttention: false, attentionReason: null, inFlight: [], outcome: null, createdAt: '', updatedAt: '',
  ...over,
}) as Contract

test('paths name the desk, so two windows can be buyer and seller', () => {
  assert.deepEqual(parsePath('/'), { page: 'picker' })
  assert.deepEqual(parsePath('/buyer'), { page: 'home', desk: 'buyer' })
  assert.deepEqual(parsePath('/seller'), { page: 'home', desk: 'seller' })
  assert.deepEqual(parsePath('/judge'), { page: 'home', desk: 'judge' })
  assert.deepEqual(parsePath('/gallery'), { page: 'gallery' })
  const id = '9a5eba20-7112-489e-83e5-91852f9d3109'
  assert.deepEqual(parsePath(`/buyer/contracts/${id}`), { page: 'deal', desk: 'buyer', id })
  assert.deepEqual(parsePath(`/seller/contracts/${id}`), { page: 'deal', desk: 'seller', id })
  assert.deepEqual(parsePath(`/judge/contracts/${id}`), { page: 'deal', desk: 'judge', id })
  assert.deepEqual(parsePath(`/contracts/${id}`), { page: 'legacy-deal', id })
  assert.equal(deskHome('buyer'), '/buyer')
  assert.equal(dealPath('seller', id), `/seller/contracts/${id}`)
})

test('the buyer approves the fee; the seller never sees that button', () => {
  const funded = deal({
    state: 'TERMS_LOCKED',
    holds: [
      hold({ side: 'BUYER_FEE', state: 'ORDER_CREATED', approveUrl: 'https://sandbox.paypal.com/x', paypalOrderId: 'O1' }),
      hold({ side: 'SELLER_BOND', amountMinor: 40, payer: 'maya', state: 'PENDING' }),
    ],
  })
  assert.equal(nextAction('buyer', funded).kind, 'approve')
  assert.match(nextAction('buyer', funded).title, /\$1\.50/)
  assert.equal(nextAction('seller', funded).kind, 'wait')
  assert.equal(waitingForApprove(funded.holds[0]), true)
})

test('the seller approves the bond; the buyer waits', () => {
  const waiting = deal({
    state: 'TERMS_LOCKED',
    holds: [
      hold({ side: 'BUYER_FEE', jar: 'HELD', state: 'HELD', caption: '$1.50 held, not charged' }),
      hold({ side: 'SELLER_BOND', amountMinor: 40, payer: 'maya', state: 'ORDER_CREATED', approveUrl: 'https://sandbox.paypal.com/y' }),
    ],
  })
  assert.equal(nextAction('seller', waiting).kind, 'approve')
  assert.match(nextAction('seller', waiting).title, /\$0\.40/)
  assert.equal(nextAction('buyer', waiting).kind, 'wait')
  assert.match(nextAction('buyer', waiting).title, /Maya/)
})

test('only the seller delivers; only the buyer confirms the first capture', () => {
  const ready = deal({ state: 'BOTH_HELD', holds: [hold({ jar: 'HELD', state: 'HELD' }), hold({ side: 'SELLER_BOND', jar: 'HELD', state: 'HELD', amountMinor: 40 })] })
  assert.equal(nextAction('seller', ready).kind, 'deliver')
  assert.equal(nextAction('buyer', ready).kind, 'wait')

  const confirm = deal({ state: 'FAIL', awaitingConfirmation: true, delivery: { verdict: 'FAIL' } as Contract['delivery'] })
  assert.equal(nextAction('buyer', confirm).kind, 'confirm')
  assert.equal(nextAction('seller', confirm).kind, 'wait')
})

test('a settled deal is done on both desks', () => {
  const done = deal({ state: 'SETTLED', outcome: "Maya's agent was paid $1.50; Maya's agent's $0.40 bond was released, never charged." })
  assert.equal(nextAction('buyer', done).kind, 'done')
  assert.equal(nextAction('seller', done).kind, 'done')
  assert.match(nextAction('buyer', done).body, /paid \$1\.50/)
})
