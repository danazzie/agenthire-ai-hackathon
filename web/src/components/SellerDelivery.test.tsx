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

  it('does not treat a no-show as different from a final FAIL in settlement copy', () => {
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
    expect(screen.queryByText(/no-show is different/i)).toBeNull()
    const finalAside = screen.getByText('This result is final').closest('div')!
    expect(finalAside.textContent).toMatch(/A final FAIL voids/i)
    expect(finalAside.textContent).toMatch(/bond is paid to the buyer/i)
    expect(finalAside.textContent).toMatch(/no-show pays the bond to the buyer the same way/i)
  })

  it('EXPIRED with no delivery says bond paid and not open holds', () => {
    render(<SellerDelivery deal={deal({
      state: 'EXPIRED',
      feeMinor: 150,
      bondMinor: 40,
      delivery: null,
    })} />)
    const banner = screen.getByText('No delivery before the deadline').closest('div')!
    expect(banner.textContent).toMatch(/fee hold is voided/i)
    expect(banner.textContent).toMatch(/bond is paid to the buyer/i)
    expect(screen.queryByText(/stay as PayPal authorizations/i)).toBeNull()
    expect(screen.queryByText(/Holds stay in place until the test runs/i)).toBeNull()
    expect(screen.queryByText(/not the same as a failed test/i)).toBeNull()
    const noDelivery = screen.getByText(/No delivery at all/)
    expect(noDelivery.textContent).toMatch(/fee hold is voided/i)
    expect(noDelivery.textContent).toMatch(/same as a failed test/i)
  })

  it('TERMS_LOCKED with no verdict may say holds stay as authorizations', () => {
    render(<SellerDelivery deal={deal({
      state: 'TERMS_LOCKED',
      delivery: null,
    })} />)
    expect(screen.getByText(/stay as PayPal authorizations/i)).toBeTruthy()
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
