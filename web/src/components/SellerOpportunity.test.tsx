import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deal, offer } from '../test/deal.ts'
import { SellerOpportunity } from './SellerOpportunity.tsx'

const offerMock = vi.fn()
vi.mock('../api.ts', () => ({
  api: {
    verifier: vi.fn(async () => ({ id: 'v', sha256: 'ab', language: 'js', source: 'function verify(){}', compiledAt: '', spec: null })),
    status: vi.fn(async () => ({ paypalConfigured: false, webhooksConfigured: false, agentsConfigured: false, parties: [], session: { id: 's', startedAt: '', capMinor: 3000, maxJobs: 2, firstCaptureConfirmedAt: null } })),
    contracts: vi.fn(async () => []),
    offer: (...args: unknown[]) => offerMock(...args),
  },
}))

const futureDeadline = new Date(Date.now() + 86_400_000).toISOString()

const draft = (deadlineAt = futureDeadline) => deal({
  offers: [offer({ party: 'alex', kind: 'PROPOSE', feeMinor: 150, bondMinor: 40, verifierId: 'v', deadlineAt })],
})

describe('seller opportunity', () => {
  beforeEach(() => offerMock.mockReset())

  it('does not post an ACCEPT until Maya reviews the terms', async () => {
    render(<SellerOpportunity deal={draft()} onNegotiate={() => {}} />)
    const accept = await screen.findByRole('button', { name: /accept these terms/i })
    expect(accept).toBeDisabled()
    await userEvent.click(accept)
    expect(offerMock).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('checkbox', { name: /reviewed the exact test, deadline and no-show bond terms/i }))
    expect(accept).toBeEnabled()
    await userEvent.click(accept)
    expect(offerMock).toHaveBeenCalledTimes(1)
    expect(offerMock).toHaveBeenCalledWith('c', 'maya', {
      kind: 'ACCEPT',
      feeMinor: 150,
      bondMinor: 40,
      deadlineAt: futureDeadline,
      verifierId: 'v',
    })
  })

  it('no-delivery bullet voids the fee hold like a failed test', async () => {
    render(<SellerOpportunity deal={draft()} onNegotiate={() => {}} />)
    const noDelivery = await screen.findByText(/No delivery at all/)
    expect(noDelivery.textContent).toMatch(/fee hold is voided/i)
    expect(noDelivery.textContent).toMatch(/same as a failed test/i)
    expect(noDelivery.textContent).not.toMatch(/not the same as a failed test/i)
  })

  it('says a failed test pays the bond to the buyer and does not split the fee', async () => {
    render(<SellerOpportunity deal={draft()} onNegotiate={() => {}} />)
    const fail = await screen.findByText(/FAIL:/)
    expect(fail.textContent).toMatch(/paid to the buyer/i)
    expect(fail.textContent).toMatch(/not split/i)
    expect(fail.textContent).not.toMatch(/bond is released/i)
  })

  it('accept panel says a failed test pays the bond to the buyer', async () => {
    render(<SellerOpportunity deal={draft()} onNegotiate={() => {}} />)
    const acceptNote = await screen.findByText(/A final test failure/)
    expect(acceptNote.textContent).toMatch(/paid to the buyer/i)
    expect(acceptNote.textContent).not.toMatch(/releases your bond/i)
  })
})
