import assert from 'node:assert/strict'
import { test } from 'node:test'
import { deal, hold } from './deal.ts'

test('a fixture deal is a draft between Alex and Maya with cents, not dollars', () => {
  const row = deal()
  assert.equal(row.buyer.handle, 'alex')
  assert.equal(row.seller.handle, 'maya')
  assert.equal(row.feeMinor, 150)
  assert.equal(row.bondMinor, 40)
  assert.equal(row.state, 'DRAFT')
  const fee = hold({ side: 'BUYER_FEE', jar: 'HELD', state: 'HELD' })
  assert.equal(fee.jar, 'HELD')
  assert.equal(deal({ holds: [fee] }).holds[0].side, 'BUYER_FEE')
})
