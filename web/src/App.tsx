import { Component, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { api, ApiError, type Contract, type PayPalCall, type Status, type Verifier } from './api.ts'
import { DealView } from './components/DealView.tsx'
import { Gallery } from './components/Gallery.tsx'
import { NewJob } from './components/NewJob.tsx'
import { RoleDesk } from './components/RoleDesk.tsx'
import { SCRIPTED_PASS_TEXT } from './scripted.ts'
import { money, settled, stepOf } from './model.ts'
import { dealPath, deskHome, parsePath, readRole, writeRole, type Desk } from './role.ts'

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

function Header({ status, desk, dealId, go }: { status: Status | null; desk: Desk | null; dealId: string | null; go: (p: string) => void }) {
  const home = desk ? deskHome(desk) : '/'
  const judgeHref = dealId ? dealPath('judge', dealId) : '/judge'
  return (
    <header className="flex flex-col items-start gap-3 border-b border-rule pb-4 sm:flex-row sm:items-end sm:justify-between sm:gap-4 sm:pb-5">
      <div>
        <a href={home} onClick={(e) => { e.preventDefault(); go(home) }} className="font-display text-3xl font-medium tracking-tight text-ink sm:text-4xl">Mutual Stake</a>
        <p className="mt-1 max-w-xl text-sm text-ink-soft">PayPal holds both sides. The test, not a mood, decides who gets paid.</p>
      </div>
      <div className="flex flex-col items-start gap-2 sm:items-end">
        {status && (
          <p className="rounded-sm border border-rule bg-paper px-3 py-1.5 text-xs text-ink-soft">
            PayPal sandbox {status.paypalConfigured ? 'connected' : 'not configured'} · Claude agents {status.agentsConfigured ? 'on' : 'off'}
          </p>
        )}
        <nav className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
          {desk && desk !== 'judge' && (
            <span className="text-ink-soft">You are {desk === 'buyer' ? "Alex's agent" : "Maya's agent"}</span>
          )}
          {desk === 'judge' && <span className="text-ink-soft">Judge view</span>}
          {desk !== 'judge' && (
            <a href={judgeHref} onClick={(e) => { e.preventDefault(); go(judgeHref) }} className="underline decoration-rule underline-offset-4">Judge view</a>
          )}
          {desk && (
            <a href="/" onClick={(e) => { e.preventDefault(); go('/') }} className="underline decoration-rule underline-offset-4">Change role</a>
          )}
        </nav>
      </div>
    </header>
  )
}

function ResetDemo({ done }: { done: () => void }) {
  const [armed, setArmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  async function reset() {
    setBusy(true)
    try {
      const result = await api.resetDemo()
      const voided = result.cancelled.length
      setMessage(`New session started. ${voided === 0 ? 'No deal needed cancelling.' : `${voided} unfunded deal${voided === 1 ? '' : 's'} cancelled; any placed hold is being voided.`}`)
      done()
    } catch (e) {
      setMessage(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    } finally {
      setBusy(false)
      setArmed(false)
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      {armed ? (
        <>
          <span className="text-slate-700">Cancel unfunded deals, void their holds and start a new session?</span>
          <button type="button" disabled={busy} onClick={reset} className="min-h-11 rounded-sm bg-ink px-3 py-2 font-medium text-paper disabled:opacity-40">Yes, reset</button>
          <button type="button" disabled={busy} onClick={() => setArmed(false)} className="min-h-11 rounded-sm border border-rule bg-paper px-3 py-2">Keep</button>
        </>
      ) : (
        <button type="button" onClick={() => { setMessage(null); setArmed(true) }} className="min-h-11 rounded-sm border border-rule bg-paper px-3 py-2">Reset demo</button>
      )}
      {message && <span className="text-slate-700" role="status">{message}</span>}
    </div>
  )
}

function RolePicker({ go, dest }: { go: (p: string) => void; dest?: (desk: Desk) => string }) {
  function pick(desk: Desk) {
    if (desk === 'buyer' || desk === 'seller') writeRole(desk)
    go(dest ? dest(desk) : deskHome(desk))
  }
  const card = 'flex min-h-36 flex-col justify-between rounded-lg border border-rule bg-paper p-5 text-left'
  return (
    <div className="flex flex-col gap-4">
      <h2 className="font-display text-3xl font-medium">Who are you in this deal?</h2>
      <p className="max-w-xl text-ink-soft">Each person only acts on their own PayPal hold. Both jars stay visible so you can still say who was Held, Paid, Released or Compensation.</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <button type="button" onClick={() => pick('buyer')} className={card}>
          <span className="text-xs font-semibold uppercase tracking-[0.14em] text-muted">Buyer</span>
          <span className="font-display text-3xl font-medium">Alex's agent</span>
          <span className="text-sm text-ink-soft">Post the job. Approve the fee. Nothing is charged until the test passes.</span>
        </button>
        <button type="button" onClick={() => pick('seller')} className={card}>
          <span className="text-xs font-semibold uppercase tracking-[0.14em] text-muted">Seller</span>
          <span className="font-display text-3xl font-medium">Maya's agent</span>
          <span className="text-sm text-ink-soft">Post the bond. Deliver the work. The bond is released if you pass.</span>
        </button>
      </div>
      <button type="button" onClick={() => pick('judge')} className="self-start text-sm underline decoration-rule underline-offset-4">
        Open the judge desk (both sides at once)
      </button>
    </div>
  )
}

function DealList({ desk, go }: { desk: Desk; go: (p: string) => void }) {
  const [deals, setDeals] = useState<Contract[]>([])
  const load = useCallback(() => { api.contracts().then(setDeals).catch(() => setDeals([])) }, [])
  useEffect(load, [load])
  return (
    <>
      {deals.length > 0 && (
        <section className="rounded-lg border border-rule bg-paper p-4">
          <h2 className="mb-2 text-lg font-semibold">This session's deals</h2>
          <ul className="flex flex-col divide-y divide-slate-100">
            {deals.map((d) => (
              <li key={d.id}>
                <a href={dealPath(desk, d.id)} onClick={(e) => { e.preventDefault(); go(dealPath(desk, d.id)) }} className="flex flex-col gap-1 py-3 text-sm hover:bg-slate-50 sm:flex-row sm:flex-wrap sm:justify-between sm:py-2">
                  <span className="font-medium">{d.jobText}</span>
                  <span className="text-slate-600">{d.state === 'CANCELLED' || d.state === 'SETTLED' ? d.state : `${d.state} · ${stepOf(d)}`} · {money(d.feeMinor)} / bond {money(d.bondMinor)}</span>
                  {d.outcome && <span className="w-full text-slate-700">{d.outcome}</span>}
                </a>
              </li>
            ))}
          </ul>
        </section>
      )}
      {desk === 'judge' && <ResetDemo done={load} />}
    </>
  )
}

function DeskHome({ desk, status, go }: { desk: Desk; status: Status | null; go: (p: string) => void }) {
  return (
    <div className="flex flex-col gap-4">
      {desk === 'buyer' && (
        <>
          <p className="text-ink-soft">You pay the fee. Approve your hold when the agents lock terms. Maya posts a smaller bond.</p>
          <NewJob agents={status?.agentsConfigured ?? false} open={(id) => go(dealPath('buyer', id))} />
        </>
      )}
      {desk === 'seller' && (
        <section className="rounded-lg border border-rule bg-paper p-5">
          <h2 className="font-display text-2xl font-medium">Waiting for a job</h2>
          <p className="mt-2 text-ink-soft">Alex's agent posts the job. When terms lock, you approve a {money(40)} bond — held, not charged, unless the work fails its test.</p>
        </section>
      )}
      {desk === 'judge' && <NewJob agents={status?.agentsConfigured ?? false} open={(id) => go(dealPath('judge', id))} />}
      <DealList desk={desk} go={go} />
    </div>
  )
}

function Deal({ id, desk }: { id: string; desk: Desk }) {
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

  const actions = {
    busy,
    error,
    deliver: (mode: 'AGENT' | 'SCRIPTED_JUNK' | 'SCRIPTED_PASS') =>
      act(() => (mode === 'SCRIPTED_PASS' ? api.deliverText(id, 'maya', SCRIPTED_PASS_TEXT) : api.deliver(id, mode))),
    confirm: () => act(() => api.confirm(id)),
  }

  if (!deal) return <p className="text-slate-600">{error ?? 'Loading the deal…'}</p>
  if (desk === 'judge') return <DealView deal={deal} verifier={verifier} calls={calls} actions={actions} />
  return <RoleDesk role={desk} deal={deal} actions={actions} />
}

class ScreenError extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null }
  static getDerivedStateFromError(error: Error) { return { error } }
  render() {
    if (!this.state.error) return this.props.children
    return (
      <section role="alert" className="rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-900">
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
  const route = parsePath(path)

  useEffect(() => {
    if (route.page === 'home' && (route.desk === 'buyer' || route.desk === 'seller')) writeRole(route.desk)
    if (route.page === 'deal' && (route.desk === 'buyer' || route.desk === 'seller')) writeRole(route.desk)
  }, [route])

  const stored = readRole()
  const desk: Desk | null =
    route.page === 'home' || route.page === 'deal' ? route.desk
      : route.page === 'legacy-deal' ? stored
        : null
  const dealId = route.page === 'deal' || route.page === 'legacy-deal' ? route.id : null

  return (
    <main className="mx-auto flex min-h-svh max-w-7xl flex-col gap-5 px-4 py-5 sm:gap-6 sm:px-5 sm:py-8">
      <Header status={status} desk={desk} dealId={dealId} go={go} />
      <ScreenError key={path}>
        {route.page === 'gallery' && <Gallery />}
        {route.page === 'picker' && <RolePicker go={go} />}
        {route.page === 'home' && <DeskHome desk={route.desk} status={status} go={go} />}
        {route.page === 'deal' && <Deal key={route.id + route.desk} id={route.id} desk={route.desk} />}
        {route.page === 'legacy-deal' && (
          stored
            ? <Deal key={route.id + stored} id={route.id} desk={stored} />
            : <RolePicker go={go} dest={(d) => dealPath(d, route.id)} />
        )}
      </ScreenError>
    </main>
  )
}
