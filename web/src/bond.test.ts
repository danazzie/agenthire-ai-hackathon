import assert from 'node:assert/strict'
import { test } from 'node:test'
import { bondCents, ratioOf } from './bond.ts'

test('40 cents on a 150 cent fee is 26.67 percent and still 40 cents', () => {
  assert.equal(ratioOf(40, 150), '26.67')
  assert.equal(bondCents(150, 26.67), 40)
})

test('a bond above half the fee is detectable before anyone posts it', () => {
  const cents = bondCents(150, 51)
  assert.equal(cents * 2 > 150, true)
})

test('half the fee is allowed', () => {
  assert.equal(bondCents(150, 50) * 2 > 150, false)
})
