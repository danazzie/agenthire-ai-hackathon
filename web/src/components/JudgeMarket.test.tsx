import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { deal, hold } from '../test/deal.ts'
import { JudgeHome } from './JudgeMarket.tsx'

const reset = vi.fn()
const tagline = 'A tagline for Spokes, a bike repair shop, at most 40 characters, must include Spokes, max $1, by 18:00'
vi.mock('../api.ts', () => ({
  api: {
    contracts: vi.fn(async () => [deal({ state: 'TERMS_LOCKED', holds: [hold({ jar: 'EMPTY' })] })]),
    resetDemo: (...args: unknown[]) => reset(...args),
    sellers: vi.fn(async () => [
      { id: 'blurb', title: 'Blurb writer', does: 'short marketing blurbs', usualMinor: 180, example: '50-word coffee-shop blurb, max $2, by 18:00, no lorem ipsum' },
      { id: 'tagline', title: 'Tagline writer', does: 'taglines', usualMinor: 90, example: tagline },
    ]),
  },
}))

describe('judge', () => {
  it('shows Empty and does not reset on render', async () => {
    render(<JudgeHome status={null} hash="" go={() => {}} />)
    expect((await screen.findAllByText(/Empty/)).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: /reset demo/i })).toBeInTheDocument()
    expect(reset).not.toHaveBeenCalled()
    expect(screen.queryByText(/say out loud/i)).toBeNull()
  })

  it("offers one of Maya's agents per kind of job, and picking one fills in its sentence", async () => {
    render(<JudgeHome status={null} hash="" go={() => {}} />)
    const chip = await screen.findByRole('button', { name: 'Tagline writer' })
    expect(screen.getByRole('button', { name: 'Blurb writer' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(chip)
    expect(screen.getByLabelText(/describe the job/i)).toHaveValue(tagline)
    expect(chip).toHaveAttribute('aria-pressed', 'true')
  })
})
