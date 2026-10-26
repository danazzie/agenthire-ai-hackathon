import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Contract } from './api.ts'
import { JAR_LABEL, money, shortHash, stepOf } from './model.ts'

const deal = (over: Partial<Contract>): Contract => ({ state: 'DRAFT', delivery: null, settlementPlannedAt: null, inFlight: [], ...over }) as Contract

test('the step follows the deal, never ahead of it', () => {
  assert.equal(stepOf(null), 'Post job')
  assert.equal(stepOf(deal({ state: 'DRAFT' })), 'Negotiate')
  assert.equal(stepOf(deal({ state: 'TERMS_LOCKED' })), 'Fund')
  assert.equal(stepOf(deal({ state: 'BOTH_HELD' })), 'Deliver')
  assert.equal(stepOf(deal({ state: 'DELIVERED' })), 'Deliver', 'the verifier is still running')
  assert.equal(stepOf(deal({ state: 'FAIL' })), 'Verdict', 'a verdict waiting for the first-capture confirm')
  assert.equal(stepOf(deal({ state: 'FAIL', settlementPlannedAt: '2026-10-21T00:00:00Z' })), 'Settle')
  assert.equal(stepOf(deal({ state: 'SETTLED' })), 'Settle')
})

test('jar labels are PROTOTYPES words, plus Captured', () => {
  assert.deepEqual(Object.values(JAR_LABEL), ['Empty', 'Held', 'Captured', 'Paid', 'Released', 'Compensation'])
})

test('money is formatted from integer cents', () => {
  assert.equal(money(150), '$1.50')
  assert.equal(money(40), '$0.40')
  assert.equal(money(0), '$0.00')
  assert.equal(money(null), '—')
})

test('hashes are shortened the PROTOTYPES way', () => {
  assert.equal(shortHash('7f3a000000000000000000000000000000000000000000000000000000c9e1'), 'sha256:7f3a…c9e1')
})
