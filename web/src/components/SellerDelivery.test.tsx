import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { deal, hold } from '../test/deal.ts'
import { SellerDelivery } from './SellerDelivery.tsx'

describe('seller delivery', () => {
  it('says a FAIL pays the bond to the buyer and voids the fee', () => {
    render(<SellerDelivery deal={deal({
      state: 'FAIL',
      feeMinor: 150,
      bondMinor: 40,
      delivery: { id: 'd', submittedAt: '', payloadSha256: '', text: 'x', verdict: 'FAIL', failingCheck: 'words', summary: null, checks: [], injectionFlags: [], verifiedAt: '', run: null, attempts: 1, needsAttention: false, attentionReason: null },
      holds: [
        hold({ side: 'BUYER_FEE', jar: 'RELEASED', state: 'VOIDED', amountMinor: 150 }),
        hold({ side: 'SELLER_BOND', jar: 'COMPENSATION', state: 'PAID_OUT', amountMinor: 40, payer: 'maya' }),
      ],
    })} />)
    const fail = screen.getByText(/FAIL:/)
    expect(fail.textContent).toMatch(/paid to the buyer/i)
    expect(fail.textContent).not.toMatch(/bond is released/i)
  })

  it('hides Ask PayPal again unless the hold is created or approved', () => {
    const { rerender } = render(<SellerDelivery deal={deal({
      state: 'TERMS_LOCKED',
      holds: [hold({ side: 'SELLER_BOND', state: 'HELD', jar: 'HELD', payer: 'maya' })],
    })} actions={{ checkHold: () => {} }} />)
    expect(screen.queryByRole('button', { name: /ask paypal again/i })).toBeNull()
    rerender(<SellerDelivery deal={deal({
      state: 'TERMS_LOCKED',
      holds: [hold({ side: 'SELLER_BOND', state: 'ORDER_CREATED', jar: 'EMPTY', payer: 'maya' })],
    })} actions={{ checkHold: () => {} }} />)
    expect(screen.getByRole('button', { name: /ask paypal again/i })).toBeEnabled()
  })
})
