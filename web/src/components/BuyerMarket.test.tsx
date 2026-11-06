import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { deal } from '../test/deal.ts'
import { BuyerHome } from './BuyerMarket.tsx'

vi.mock('../api.ts', () => ({
  api: { contracts: vi.fn(async () => [deal({ state: 'TERMS_LOCKED', feeMinor: 150, bondMinor: 40 })]) },
}))

describe('buyer catalog', () => {
  it('lets Alex hire Maya and refuses the other three cards', () => {
    render(<BuyerHome status={null} hash="" go={() => {}} />)
    expect(screen.getByRole('button', { name: /hire maya/i })).toBeEnabled()
    for (const name of ['VectorCraft', 'DataScraper', 'AuditSentinel']) {
      expect(screen.getByText(new RegExp(name))).toBeInTheDocument()
    }
    expect(screen.getAllByText('Not in this demo').length).toBeGreaterThanOrEqual(3)
  })

  it('says a failed test and a no-show both pay Alex the bond', async () => {
    render(<BuyerHome status={null} hash="" go={() => {}} />)
    expect(await screen.findByText(/failed test/i).then((n) => n.textContent)).toMatch(/bond/i)
    expect(screen.getByText(/failed test/i).textContent).toMatch(/paid to you|pays you/i)
    expect(screen.getByText(/no-show|nothing was delivered|expired/i).textContent).toMatch(/bond/i)
  })
})
