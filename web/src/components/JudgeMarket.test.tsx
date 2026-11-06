import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { deal, hold } from '../test/deal.ts'
import { JudgeHome } from './JudgeMarket.tsx'

const reset = vi.fn()
vi.mock('../api.ts', () => ({
  api: {
    contracts: vi.fn(async () => [deal({ state: 'TERMS_LOCKED', holds: [hold({ jar: 'EMPTY' })] })]),
    resetDemo: (...args: unknown[]) => reset(...args),
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
})
