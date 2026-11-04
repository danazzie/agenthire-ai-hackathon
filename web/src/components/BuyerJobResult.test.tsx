import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { deal, hold } from '../test/deal.ts'
import { BuyerJobResult, isJobResult } from './BuyerJobResult.tsx'

vi.mock('../api.ts', () => ({
  api: {
    status: vi.fn(async () => ({ session: { id: 'other' } })),
    contracts: vi.fn(async () => []),
  },
}))

describe('buyer job result', () => {
  it('is a result once there is a verdict, and a note is not sent', async () => {
    const row = deal({
      state: 'FAIL',
      delivery: { id: 'd', submittedAt: '', payloadSha256: '', text: 'x', verdict: 'FAIL', failingCheck: 'words', summary: null, checks: [{ name: 'words', passed: false, detail: '49', expected: '50', evidence: null }], injectionFlags: [], verifiedAt: '', run: null, attempts: 1, needsAttention: false, attentionReason: null },
      holds: [hold({ side: 'BUYER_FEE', jar: 'RELEASED', state: 'VOIDED' })],
    })
    expect(isJobResult(row)).toBe(true)
    expect(isJobResult(deal({ state: 'DRAFT' }))).toBe(false)
    render(<BuyerJobResult deal={row} />)
    expect(screen.getByText(/FAIL:/).textContent).toMatch(/paid to you/i)
    await userEvent.type(screen.getByRole('textbox', { name: /why should this result be remembered/i }), 'the count was wrong')
    await userEvent.click(screen.getByRole('button', { name: /save a note/i }))
    expect(screen.getByText(/not sent/i)).toBeInTheDocument()
  })
})
