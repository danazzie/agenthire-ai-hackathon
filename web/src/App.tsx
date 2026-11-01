import { Component, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { api, ApiError, type Contract, type PayPalCall, type Status, type Verifier } from './api.ts'
import { BuyerHome } from './components/BuyerMarket.tsx'
import { DealView } from './components/DealView.tsx'
import { BrandLockup, DeskChrome, Icon } from './components/DeskShell.tsx'
import { Gallery } from './components/Gallery.tsx'
import { JudgeHome } from './components/JudgeMarket.tsx'
import { RoleDesk } from './components/RoleDesk.tsx'
import { SellerHome } from './components/SellerMarket.tsx'
import { SCRIPTED_PASS_TEXT } from './scripted.ts'
import { settled } from './model.ts'
import { dealPath, deskHome, parsePath, readRole, writeRole, type Desk } from './role.ts'

function pathOnly(p: string) {
  return p.split(/[?#]/)[0] || '/'
}

function usePath(): [string, string, (p: string) => void] {
  const [path, setPath] = useState(() => pathOnly(window.location.pathname))
  const [hash, setHash] = useState(() => window.location.hash)
  useEffect(() => {
    const back = () => {
      setPath(pathOnly(window.location.pathname))
      setHash(window.location.hash)
    }
    window.addEventListener('popstate', back)
    return () => window.removeEventListener('popstate', back)
  }, [])
  const go = useCallback((p: string) => {
    const url = new URL(p, window.location.origin)
    window.history.pushState(null, '', url.pathname + url.hash)
    setPath(url.pathname)
    setHash(url.hash)
  }, [])
  return [path, hash, go]
}

function Header({ status, desk, dealId, go }: { status: Status | null; desk: Desk | null; dealId: string | null; go: (p: string) => void }) {
  const home = desk ? deskHome(desk) : '/'
  const judgeHref = dealId ? dealPath('judge', dealId) : '/judge'
  return (
    <header className="flex flex-col items-start gap-3 border-b border-rule pb-4 sm:flex-row sm:items-end sm:justify-between sm:gap-4 sm:pb-5">
      <div>
        <a href={home} onClick={(e) => { e.preventDefault(); go(home) }} className="font-display text-3xl font-medium tracking-tight text-ink sm:text-4xl">HireMind</a>
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

function RolePicker({ go, dest }: { go: (p: string) => void; dest?: (desk: Desk) => string }) {
  function pick(desk: Desk) {
    if (desk === 'buyer' || desk === 'seller') writeRole(desk)
    go(dest ? dest(desk) : deskHome(desk))
  }
  const card = 'flex min-h-44 w-full min-w-0 flex-col justify-between whitespace-normal break-words rounded-2xl bg-white p-6 text-left shadow-sm'
  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-6 px-4 py-10 sm:px-6">
      <div className="min-w-0">
        <p className="text-[11px] font-semibold uppercase tracking-[0.6px] text-[#2563eb]">HireMind / Choose a desk</p>
        <h1 className="mt-1 text-[30px] leading-tight tracking-[-0.5px] text-[#0f172a]">Who are you in this deal?</h1>
        <p className="mt-2 max-w-2xl text-[14px] leading-normal text-[#64748b]">Pick the person you are. Alex hires and approves the fee. Maya delivers the work. The judge can see both sides.</p>
      </div>
      <div className="grid min-w-0 grid-cols-[repeat(auto-fit,minmax(min(100%,16rem),1fr))] gap-4">
        <button type="button" onClick={() => pick('buyer')} className={card}>
          <span className="flex size-12 items-center justify-center rounded-xl bg-[color-mix(in_srgb,var(--bm-primary-c)_15%,white)]"><Icon name="storefront" className="text-[24px] text-[var(--bm-primary)]" /></span>
          <span className="mt-4 text-[11px] font-semibold uppercase tracking-wider text-[var(--bm-on-var)]">Buyer</span>
          <span className="text-[22px] leading-tight tracking-[-0.3px] text-[#0f172a]">Alex's agent</span>
          <span className="text-sm text-[var(--bm-on-var)]">Hire Maya. Approve the fee. Nothing is charged until the test passes.</span>
        </button>
        <button type="button" onClick={() => pick('seller')} className={card}>
          <span className="flex size-12 items-center justify-center rounded-xl bg-[color-mix(in_srgb,var(--bm-primary-c)_15%,white)]"><Icon name="inbox" className="text-[24px] text-[var(--bm-primary)]" /></span>
          <span className="mt-4 text-[11px] font-semibold uppercase tracking-wider text-[var(--bm-on-var)]">Seller</span>
          <span className="text-[22px] leading-tight tracking-[-0.3px] text-[#0f172a]">Maya's agent</span>
          <span className="text-sm text-[var(--bm-on-var)]">Post the bond. Deliver the work. The bond is released if you pass.</span>
        </button>
        <button type="button" onClick={() => pick('judge')} className={card}>
          <span className="flex size-12 items-center justify-center rounded-xl bg-[color-mix(in_srgb,var(--bm-primary-c)_15%,white)]"><Icon name="gavel" className="text-[24px] text-[var(--bm-primary)]" /></span>
          <span className="mt-4 text-[11px] font-semibold uppercase tracking-wider text-[var(--bm-on-var)]">Judge</span>
          <span className="text-[22px] leading-tight tracking-[-0.3px] text-[#0f172a]">Both sides at once</span>
          <span className="text-sm text-[var(--bm-on-var)]">See both sides, start a job, or reset the session.</span>
        </button>
      </div>
    </div>
  )
}

function Deal({ id, desk }: { id: string; desk: Desk }) {
  const [deal, setDeal] = useState<Contract | null>(null)
  const [verifier, setVerifier] = useState<Verifier | null>(null)
  const [calls, setCalls] = useState<PayPalCall[] | null>(null)
  const [reads, setReads] = useState(false)
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
        setCalls(await api.paypalCalls(id, reads))
      }
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    }
  }, [id, verifier?.id, reads])

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
    cancel: () => act(() => api.cancel(id, desk === 'seller' ? 'maya' : 'alex')),
    checkHold: (holdId: string) => act(() => api.checkHold(holdId)),
    loadReads: () => act(async () => {
      setReads(true)
      lastCalls.current = Date.now()
      setCalls(await api.paypalCalls(id, true))
    }),
  }

  if (!deal) return <p className="text-[var(--bm-on-var)]">{error ?? 'Loading the deal…'}</p>
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
  const [path, hash, go] = usePath()
  const [status, setStatus] = useState<Status | null>(null)
  const [dealCount, setDealCount] = useState(0)
  useEffect(() => { api.status().then(setStatus).catch(() => setStatus(null)) }, [])
  useEffect(() => {
    if (hash.startsWith('#buyer-') || hash.startsWith('#seller-') || hash.startsWith('#judge-')) return
    window.scrollTo({ top: 0 })
  }, [path, hash])
  useEffect(() => { api.contracts().then((rows) => setDealCount(rows.length)).catch(() => setDealCount(0)) }, [path])
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

  const screens = (
    <ScreenError key={path}>
      {route.page === 'gallery' && <Gallery />}
      {route.page === 'picker' && <RolePicker go={go} />}
      {route.page === 'home' && route.desk === 'buyer' && <BuyerHome status={status} hash={hash} go={go} />}
      {route.page === 'home' && route.desk === 'seller' && <SellerHome hash={hash} go={go} />}
      {route.page === 'home' && route.desk === 'judge' && <JudgeHome status={status} hash={hash} go={go} />}
      {route.page === 'deal' && (
        <div className="mx-auto min-w-0 max-w-7xl px-4 py-6 sm:px-6"><Deal key={route.id + route.desk} id={route.id} desk={route.desk} /></div>
      )}
      {route.page === 'legacy-deal' && (
        stored
          ? <div className="mx-auto min-w-0 max-w-7xl px-4 py-6 sm:px-6"><Deal key={route.id + stored} id={route.id} desk={stored} /></div>
          : <RolePicker go={go} dest={(d) => dealPath(d, route.id)} />
      )}
    </ScreenError>
  )

  if (desk) {
    return (
      <DeskChrome desk={desk} status={status} dealCount={dealCount} dealId={dealId} hash={hash} go={go}>
        {screens}
      </DeskChrome>
    )
  }

  if (route.page === 'picker' || route.page === 'legacy-deal') {
    return (
      <div className="desk-shell min-h-svh">
        <header className="sticky top-0 z-40 border-b border-[#e5e7eb] bg-white">
          <div className="mx-auto flex h-[68px] max-w-7xl items-center px-5 sm:h-[76px] sm:px-10">
            <BrandLockup />
          </div>
        </header>
        {screens}
      </div>
    )
  }

  return (
    <main className="mx-auto flex min-h-svh max-w-7xl flex-col gap-5 px-4 py-5 sm:gap-6 sm:px-5 sm:py-8">
      <Header status={status} desk={desk} dealId={dealId} go={go} />
      {screens}
    </main>
  )
}
