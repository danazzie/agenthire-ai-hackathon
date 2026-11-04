import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deal, offer } from '../test/deal.ts'
import { SellerCounteroffer } from './SellerCounteroffer.tsx'

const offerMock = vi.fn(async () => deal())
vi.mock('../api.ts', () => ({
  api: {
    status: vi.fn(async () => null),
    offer: (...args: unknown[]) => offerMock(...args),
  },
}))

describe('counteroffer', () => {
  beforeEach(() => offerMock.mockReset())

  it('Save draft does not call the API', async () => {
    render(<SellerCounteroffer deal={deal({ offers: [offer()] })} />)
    await userEvent.click(screen.getByRole('button', { name: /save draft/i }))
    expect(offerMock).not.toHaveBeenCalled()
    expect(screen.getByText('Saved on this page · Not sent')).toBeInTheDocument()
  })

  it('Send counteroffer posts PROPOSE as maya and recomputes 26.67 percent to 40 cents', async () => {
    offerMock.mockResolvedValue(deal())
    render(
      <SellerCounteroffer
        deal={deal({
          offers: [offer({ feeMinor: 150, bondMinor: 40, deadlineAt: '2026-12-31T18:00:00Z' })],
        })}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: /send counteroffer/i }))
    expect(offerMock).toHaveBeenCalledTimes(1)
    const [id, party, body] = offerMock.mock.calls[0]
    expect(id).toBe('c')
    expect(party).toBe('maya')
    expect(body).toMatchObject({ kind: 'PROPOSE', feeMinor: 150, bondMinor: 40, verifierId: 'v' })
  })
})
