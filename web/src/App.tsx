import { Component, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { api, ApiError, type Contract, type PayPalCall, type Status, type Verifier } from './api.ts'
import { DealView } from './components/DealView.tsx'
import { Gallery } from './components/Gallery.tsx'
import { NewJob } from './components/NewJob.tsx'
import { money, settled, stepOf } from './model.ts'

/** A three-route app with no router library: /, /contracts/:id and /gallery (fixtures for visual checks). */
function usePath(): [string, (p: string) => void] {
  const [path, setPath] = useState(window.location.pathname)
  useEffect(() => {
    const back = () => setPath(window.location.pathname)
    window.addEventListener('popstate', back)
    return () => window.removeEventListener('popstate', back)
  }, [])
  const go = useCallback((p: string) => {
    window.history.pushState(null, '', p)
    setPath(p)
  }, [])
  return [path, go]
}

function Header({ status, go }: { status: Status | null; go: (p: string) => void }) {
  return (
    <header className="flex flex-wrap items-baseline justify-between gap-2">
      <a href="/" onClick={(e) => { e.preventDefault(); go('/') }} className="text-2xl font-bold">Mutual Stake</a>
      <p className="text-sm text-slate-600">PayPal holds both sides. The test, not a mood, decides who gets paid.</p>
      {status && (
        <p className="text-xs text-slate-500">
          PayPal sandbox {status.paypalConfigured ? 'connected' : 'not configured'} · Claude agents {status.agentsConfigured ? 'on' : 'off'}
        </p>
      )}
    </header>
  )
}

function Home({ status, go }: { status: Status | null; go: (p: string) => void }) {
  const [deals, setDeals] = useState<Contract[]>([])
  useEffect(() => { api.contracts().then(setDeals).catch(() => setDeals([])) }, [])
  return (
    <div className="flex flex-col gap-4">
      <NewJob agents={status?.agentsConfigured ?? false} open={(id) => go(`/contracts/${id}`)} />
      {deals.length > 0 && (
        <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <h2 className="mb-2 text-lg font-semibold">This session's deals</h2>
          <ul className="flex flex-col divide-y divide-slate-100">
            {deals.map((d) => (
              <li key={d.id}>
                <a href={`/contracts/${d.id}`} onClick={(e) => { e.preventDefault(); go(`/contracts/${d.id}`) }} className="flex flex-wrap justify-between gap-2 py-2 text-sm hover:bg-slate-50">
                  <span className="font-medium">{d.jobText}</span>
                  <span className="text-slate-600">{d.state} · {stepOf(d)} · {money(d.feeMinor)} / bond {money(d.bondMinor)}</span>
                  {d.outcome && <span className="w-full text-slate-700">{d.outcome}</span>}
                </a>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

/** A live deal: polls the control plane, which follows PayPal; the screen never decides a state itself. */
function Deal({ id }: { id: string }) {
  const [deal, setDeal] = useState<Contract | null>(null)
  const [verifier, setVerifier] = useState<Verifier | null>(null)
  const [calls, setCalls] = useState<PayPalCall[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const lastCalls = useRef(0)

  const refresh = useCallback(async () => {
    try {
      const next = await api.contract(id)
      setDeal(next)
      const verifierId = next.verifier?.id ?? next.offers[0]?.verifierId
      if (verifierId && verifier?.id !== verifierId) setVerifier(next.verifier ?? (await api.verifier(verifierId)))
      if (next.holds.some((h) => h.paypalOrderId) && Date.now() - lastCalls.current > 5000) {
        lastCalls.current = Date.now()
        setCalls(await api.paypalCalls(id))
      }
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    }
  }, [id, verifier?.id])

  useEffect(() => {
    refresh()
    const timer = window.setInterval(() => { if (!deal || !settled(deal)) refresh() }, 1500)
    return () => window.clearInterval(timer)
  }, [refresh, deal])

  async function act(work: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await work()
      await refresh()
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (!deal) return <p className="text-slate-600">{error ?? 'Loading the deal…'}</p>
  return (
    <DealView
      deal={deal}
      verifier={verifier}
      calls={calls}
      actions={{ busy, error, deliver: (mode) => act(() => api.deliver(id, mode)), confirm: () => act(() => api.confirm(id)) }}
    />
  )
}

/** A render bug shows its message instead of a blank page, so a demo never goes silently empty. */
class ScreenError extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null }
  static getDerivedStateFromError(error: Error) { return { error } }
  render() {
    if (!this.state.error) return this.props.children
    return (
      <section role="alert" className="rounded-xl border border-red-300 bg-red-50 p-4 text-sm text-red-900">
        <h2 className="font-semibold">This screen could not be drawn</h2>
        <p className="mt-1 font-mono">{this.state.error.message}</p>
        <p className="mt-1">Nothing about the deal changed: PayPal and the control plane hold the state. Reload to try again.</p>
      </section>
    )
  }
}

export default function App() {
  const [path, go] = usePath()
  const [status, setStatus] = useState<Status | null>(null)
  useEffect(() => { api.status().then(setStatus).catch(() => setStatus(null)) }, [])
  const deal = /^\/contracts\/([0-9a-f-]{36})$/.exec(path)
  return (
    <main className="mx-auto flex max-w-7xl flex-col gap-4 px-4 py-4">
      <Header status={status} go={go} />
      <ScreenError key={path}>
        {path === '/gallery' ? <Gallery /> : deal ? <Deal key={deal[1]} id={deal[1]} /> : <Home status={status} go={go} />}
      </ScreenError>
    </main>
  )
}
