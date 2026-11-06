import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DeskChrome } from './DeskShell.tsx'

vi.mock('../api.ts', () => ({ api: { contracts: vi.fn(async () => []) } }))

describe('buyer chrome', () => {
  it('names HireMind and routes the four links', async () => {
    const go = vi.fn()
    render(<DeskChrome desk="buyer" status={null} dealCount={1} dealId={null} hash="" go={go}><p>desk</p></DeskChrome>)
    expect(screen.getAllByText('HireMind').length).toBeGreaterThan(0)
    expect(screen.queryByText(/pact/i)).toBeNull()
    const payments = screen.getAllByRole('button', { name: 'Payments' })
    await userEvent.click(payments[0])
    expect(go).toHaveBeenCalledWith('/buyer#buyer-payments')
    expect(screen.getAllByRole('button', { name: 'Overview' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('button', { name: /My jobs/ }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('button', { name: 'Activity' }).length).toBeGreaterThan(0)
  })
})
