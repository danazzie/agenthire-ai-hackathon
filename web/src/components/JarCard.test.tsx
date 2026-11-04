import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { hold } from '../test/deal.ts'
import { JarCard } from './JarCard.tsx'

describe('jar card', () => {
  it('says Empty, not EMPTY', () => {
    render(<JarCard hold={hold({ jar: 'EMPTY' })} title="Buyer fee" party="Alex's agent" />)
    expect(screen.getByText('Empty')).toBeInTheDocument()
    expect(screen.queryByText('EMPTY')).toBeNull()
  })

  it('shows Paid together with the PayPal order id', () => {
    render(<JarCard hold={hold({ jar: 'PAID', state: 'PAID_OUT', paypalOrderId: 'ORDER1', captureId: '9AB4K' })} title="Buyer fee" party="Alex's agent" />)
    expect(screen.getByText('Paid')).toBeInTheDocument()
    expect(screen.getByText(/order ORDER1/)).toBeInTheDocument()
  })
})
