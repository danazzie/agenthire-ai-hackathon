import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deal } from '../test/deal.ts'
import { api } from '../api.ts'
import { BuyerHome } from './BuyerMarket.tsx'

vi.mock('../api.ts', () => ({
  api: {
    contracts: vi.fn(async () => [deal({ state: 'TERMS_LOCKED', feeMinor: 150, bondMinor: 40 })]),
    sellers: vi.fn(),
  },
}))

const ROSTER = [
  { id: 'blurb', title: 'Blurb writer', does: 'short marketing blurbs', usualMinor: 180, example: '50-word coffee-shop blurb, max $2, by 18:00, no lorem ipsum' },
  { id: 'tagline', title: 'Tagline writer', does: 'taglines, slogans, headlines and names: one short line', usualMinor: 90, example: 'A tagline for Spokes, at most 40 characters, max $1, by 18:00' },
  { id: 'email', title: 'Email writer', does: 'short emails', usualMinor: 150, example: 'A thank-you email to the judges, max $2, by 21:00' },
]

const claudeOn = { agentsConfigured: true } as Parameters<typeof BuyerHome>[0]['status']
const claudeOff = { agentsConfigured: false } as Parameters<typeof BuyerHome>[0]['status']

describe('buyer catalog', () => {
  beforeEach(() => { vi.mocked(api.sellers).mockResolvedValue(ROSTER) })

  it("lists Maya's agents from the control plane, not a hard-coded catalog", async () => {
    render(<BuyerHome status={claudeOn} hash="" go={() => {}} />)
    expect(await screen.findByText("Maya's tagline writer")).toBeInTheDocument()
    expect(screen.getByText("Maya's blurb writer")).toBeInTheDocument()
    expect(screen.getByText("Maya's email writer")).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /^hire$/i })).toHaveLength(3)
    for (const name of ['VectorCraft', 'DataScraper', 'AuditSentinel']) expect(screen.queryByText(new RegExp(name))).toBeNull()
  })

  it("opens the job form with the picked agent's example sentence", async () => {
    render(<BuyerHome status={claudeOn} hash="" go={() => {}} />)
    await screen.findByText("Maya's tagline writer")
    fireEvent.click(screen.getAllByRole('button', { name: /^hire$/i })[1])
    expect(screen.getByRole('dialog')).toHaveTextContent("Hire Maya's tagline writer")
    expect(screen.getByRole('textbox', { name: /describe the job/i })).toHaveValue(ROSTER[1].example)
    // The scripted deal is always the blurb, so it is not offered for another agent.
    expect(screen.queryByRole('button', { name: /scripted job/i })).toBeNull()
  })

  it('keeps the scripted job for the blurb writer', async () => {
    render(<BuyerHome status={claudeOn} hash="" go={() => {}} />)
    await screen.findByText("Maya's blurb writer")
    fireEvent.click(screen.getAllByRole('button', { name: /^hire$/i })[0])
    expect(screen.getByRole('button', { name: /scripted job/i })).toBeInTheDocument()
  })

  it('without Claude, only the blurb writer can be hired (the scripted deal)', async () => {
    render(<BuyerHome status={claudeOff} hash="" go={() => {}} />)
    await screen.findByText("Maya's tagline writer")
    expect(screen.getAllByRole('button', { name: /^hire$/i })).toHaveLength(1)
    expect(screen.getAllByText(/needs claude/i)).toHaveLength(2)
  })

  it('falls back to the blurb writer when the control plane has no roster', async () => {
    vi.mocked(api.sellers).mockRejectedValue(new Error('404'))
    render(<BuyerHome status={claudeOn} hash="" go={() => {}} />)
    expect(await screen.findByText("Maya's blurb writer")).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /^hire$/i })).toHaveLength(1)
  })

  it('says a failed test and a no-show both pay Alex the bond', async () => {
    render(<BuyerHome status={null} hash="" go={() => {}} />)
    expect(await screen.findByText(/failed test/i).then((n) => n.textContent)).toMatch(/bond/i)
    expect(screen.getByText(/failed test/i).textContent).toMatch(/paid to you|pays you/i)
    expect(screen.getByText(/no-show|nothing was delivered|expired/i).textContent).toMatch(/bond/i)
  })
})
