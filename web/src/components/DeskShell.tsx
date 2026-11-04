import { useEffect, useRef, useState, type ReactNode } from 'react'
import { api, type Contract, type Status } from '../api.ts'
import { dealPath, type Desk } from '../role.ts'
import bellMark from '../assets/figma-bell.svg'
import mark from '../assets/figma-mark.svg'
import overviewIcon from '../assets/figma-nav-overview.svg'
import jobsIcon from '../assets/figma-nav-jobs.svg'
import activityIcon from '../assets/figma-nav-activity.svg'
import paymentsIcon from '../assets/figma-nav-payments.svg'
import opportunitiesIcon from '../assets/figma-nav-opportunities.svg'
import messagesIcon from '../assets/figma-nav-messages.svg'
import deliveriesIcon from '../assets/figma-nav-deliveries.svg'
import walletIcon from '../assets/figma-nav-wallet.svg'

export function Icon({ name, className = '' }: { name: string; className?: string }) {
  return <span className={`material-symbols-outlined ${className}`} aria-hidden>{name}</span>
}

export function MetricRow({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`desk-metrics -mx-4 flex snap-x snap-mandatory gap-3 overflow-x-auto px-4 pb-1 sm:mx-0 sm:grid sm:snap-none sm:grid-cols-2 sm:overflow-visible sm:px-0 lg:grid-cols-4 ${className}`}>{children}</div>
}

export function Metric({ icon, label, value, sub }: { icon: string; label: string; value: string; sub: string }) {
  return (
    <div className="flex w-[17rem] max-w-[80vw] shrink-0 snap-start items-start gap-4 rounded-2xl bg-white p-5 shadow-sm sm:w-auto sm:max-w-none">
      <span className="flex size-12 shrink-0 items-center justify-center rounded-xl bg-[color-mix(in_srgb,var(--bm-primary-c)_15%,white)]">
        <Icon name={icon} className="text-[24px] text-[var(--bm-primary)]" />
      </span>
      <div className="min-w-0">
        <div className="break-words text-[11px] font-semibold uppercase tracking-wide text-[var(--bm-on-var)]">{label}</div>
        <div className="break-words text-[17px] font-semibold tracking-tight">{value}</div>
        <div className="break-words text-[13px] text-[var(--bm-on-var)]">{sub}</div>
      </div>
    </div>
  )
}

function initials(name: string): string {
  const person = name.replace(/'s agent$/i, '').trim()
  const parts = person.split(/\s+/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return (person.slice(0, 2) || 'AL').toUpperCase()
}

const WORKSPACE: Record<Desk, { section: string; page: string; href: string; handle: string; fallback: string; chip: string }> = {
  buyer: { section: 'Client workspace', page: 'My jobs', href: '/buyer#buyer-deals', handle: 'alex', fallback: 'Alex', chip: 'CLIENT' },
  seller: { section: 'Seller workspace', page: 'Inbox', href: '/seller', handle: 'maya', fallback: 'Maya', chip: 'SELLER' },
  judge: { section: 'Judge workspace', page: 'Control room', href: '/judge', handle: '', fallback: 'Observer', chip: 'JUDGE' },
}

const SCROLL_TARGET: Record<string, string> = {
  'buyer-deals': 'buyer-deals',
  'buyer-activity': 'buyer-activity',
  'buyer-payments': 'buyer-payments',
  'seller-talk': 'seller-jobs',
  'seller-deliver': 'seller-jobs',
  'seller-wallet': 'seller-wallet',
  'judge-deals': 'judge-deals',
}

/** The four-diamond mark plus the product name. The phone header adds the desk chip. */
export function BrandLockup({ chip }: { chip?: string }) {
  return (
    <div className="flex min-w-0 items-center gap-2">
      <img src={mark} alt="" />
      <span className="truncate text-[20px] leading-normal text-[#0f172a]">HireMind</span>
      {chip && <span className="shrink-0 rounded-md bg-[#111827] px-[9px] py-1 text-[11px] leading-[1.5] text-white">{chip}</span>}
    </div>
  )
}

function NavGlyph({ src, active }: { src: string; active: boolean }) {
  const url = `url("${src}")`
  return (
    <span
      aria-hidden
      className="inline-block size-5 shrink-0"
      style={{
        backgroundColor: active ? '#2563eb' : '#64748b',
        WebkitMaskImage: url,
        WebkitMaskRepeat: 'no-repeat',
        WebkitMaskPosition: 'center',
        WebkitMaskSize: 'contain',
        maskImage: url,
        maskRepeat: 'no-repeat',
        maskPosition: 'center',
        maskSize: 'contain',
      }}
    />
  )
}

function Avatar({ name }: { name: string }) {
  return <span className="flex size-8 items-center justify-center rounded-full bg-[#dbeafe] text-[11px] font-bold leading-normal text-[#1e40af]" aria-hidden>{initials(name)}</span>
}

export function DeskChrome({ desk, status, dealCount, dealId, hash, go, children }: {
  desk: Desk
  status: Status | null
  dealCount: number
  dealId: string | null
  hash: string
  go: (p: string) => void
  children: ReactNode
}) {
  const workspace = WORKSPACE[desk]
  const party = status?.parties.find((p) => p.handle === workspace.handle)
  const name = party?.displayName ?? workspace.fallback
  const connected = status?.paypalConfigured === true
  const [open, setOpen] = useState(false)
  const [notes, setNotes] = useState<Contract[] | null>(null)
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onPointer(event: MouseEvent) {
      if (!panel.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onPointer)
    return () => document.removeEventListener('mousedown', onPointer)
  }, [open])

  function toggleBell() {
    setOpen((v) => !v)
    if (notes) return
    api.contracts().then((rows) => setNotes(rows.filter((d) => d.needsAttention))).catch(() => setNotes([]))
  }

  const nav: { label: string; href: string; icon: string; current: boolean; count?: number }[] = desk === 'buyer'
    ? [
        { label: 'Overview', href: '/buyer', icon: overviewIcon, current: !dealId && hash === '' },
        { label: 'My jobs', href: '/buyer#buyer-deals', icon: jobsIcon, current: !!dealId || hash === '#buyer-deals', count: dealCount },
        { label: 'Activity', href: '/buyer#buyer-activity', icon: activityIcon, current: hash === '#buyer-activity' },
        { label: 'Payments', href: '/buyer#buyer-payments', icon: paymentsIcon, current: hash === '#buyer-payments' },
      ]
    : desk === 'seller'
      ? [
          { label: 'Opportunities', href: '/seller', icon: opportunitiesIcon, current: !dealId && hash === '' },
          { label: 'Negotiations', href: '/seller#seller-talk', icon: messagesIcon, current: hash === '#seller-talk' },
          { label: 'Deliveries', href: '/seller#seller-deliver', icon: deliveriesIcon, current: hash === '#seller-deliver' },
          { label: 'Wallet', href: '/seller#seller-wallet', icon: walletIcon, current: hash === '#seller-wallet' },
        ]
      : [
          { label: 'Control', href: '/judge', icon: overviewIcon, current: !dealId && hash !== '#judge-deals' },
          { label: 'Deals', href: '/judge#judge-deals', icon: jobsIcon, current: !!dealId || hash === '#judge-deals', count: dealCount },
          { label: 'Alex', href: dealId ? dealPath('buyer', dealId) : '/buyer', icon: activityIcon, current: false },
          { label: 'Maya', href: dealId ? dealPath('seller', dealId) : '/seller', icon: messagesIcon, current: false },
        ]

  function follow(href: string) {
    const url = new URL(href, window.location.origin)
    const next = url.pathname + url.hash
    const here = window.location.pathname + window.location.hash
    const target = SCROLL_TARGET[url.hash.slice(1)]
    if (here === next) {
      if (target) document.getElementById(target)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      else window.scrollTo({ top: 0, behavior: 'smooth' })
      return
    }
    go(next)
  }

  const statusText = status ? (connected ? 'PayPal sandbox · connected' : 'PayPal sandbox · not configured') : 'Checking PayPal…'
  const statusClass = connected ? 'bg-[#eff6ff] text-[#2563eb]' : 'bg-[#fff7ed] text-[#c2410c]'

  return (
    <div className="desk-shell min-h-svh min-w-0">
      <header className="sticky top-0 z-40 border-b border-[#e5e7eb] bg-white">
        <div className="flex h-[68px] items-center justify-between gap-3 px-5 lg:hidden">
          <BrandLockup chip={workspace.chip} />
          <button type="button" onClick={() => go('/')} aria-label={`Change role. ${name}`} className="flex size-11 shrink-0 items-center justify-center">
            <Avatar name={name} />
          </button>
        </div>
        <div className="mx-auto hidden h-[76px] max-w-7xl items-center gap-6 px-6 lg:flex lg:px-10">
          <nav aria-label="Workspace" className="flex shrink-0 items-center gap-2.5 text-[13px] leading-normal">
            <button type="button" onClick={() => follow(`/${desk}`)} className="text-[#64748b]">{workspace.section}</button>
            <span className="text-[#94a3b8]">/</span>
            <button type="button" onClick={() => follow(workspace.href)} className="text-[#0f172a]">{workspace.page}</button>
          </nav>
          <nav aria-label="Desk" className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto">
            {nav.map((item) => (
              <button key={item.label} type="button" aria-current={item.current ? 'page' : undefined} onClick={() => follow(item.href)} className={`inline-flex h-11 shrink-0 items-center rounded-lg px-2.5 text-[13px] leading-normal ${item.current ? 'font-semibold text-[#2563eb]' : 'text-[#64748b] hover:text-[#0f172a]'}`}>
                {item.label}
                {item.count ? <span className="ml-1.5 rounded-full bg-[#2563eb] px-1.5 text-[11px] font-bold text-white">{item.count}</span> : null}
              </button>
            ))}
          </nav>
          <div ref={panel} className="relative flex shrink-0 items-center gap-4">
            <span className={`rounded-md px-[9px] py-1 text-[11px] leading-[1.5] ${statusClass}`}>{statusText}</span>
            <button type="button" aria-expanded={open} aria-label="Jobs that need attention" onClick={toggleBell} className="flex size-11 items-center justify-center">
              <img src={bellMark} alt="" />
            </button>
            {open && (
              <div className="absolute right-0 top-full z-50 mt-1 w-72 rounded-xl border border-[#e5e7eb] bg-white p-3 text-left shadow-[0px_8px_24px_0px_rgba(15,23,42,0.08)]">
                {notes === null && <p className="text-[13px] text-[#64748b]">Checking jobs…</p>}
                {notes?.length === 0 && <p className="text-[13px] text-[#64748b]">Nothing needs attention.</p>}
                {notes?.map((deal) => (
                  <button key={deal.id} type="button" onClick={() => { setOpen(false); go(dealPath(desk, deal.id)) }} className="block w-full rounded-lg px-2 py-2 text-left hover:bg-[#f8fafb]">
                    <span className="block truncate text-[13px] text-[#0f172a]">{deal.jobText}</span>
                    <span className="block truncate text-[11px] text-[#64748b]">{deal.attentionReason ?? deal.state}</span>
                  </button>
                ))}
              </div>
            )}
            <Avatar name={name} />
            <button type="button" onClick={() => go('/')} className="whitespace-nowrap text-[13px] leading-normal text-[#64748b] underline-offset-4 hover:underline">Change role</button>
          </div>
        </div>
      </header>
      <div className="min-w-0 pb-[calc(5rem+env(safe-area-inset-bottom))] lg:pb-0">{children}</div>
      <nav aria-label="Desk" className="fixed inset-x-0 bottom-0 z-40 border-t border-[#e5e7eb] bg-white px-3 pt-[15px] pb-[max(10px,env(safe-area-inset-bottom))] lg:hidden">
        <div className="flex items-start justify-between">
          {nav.map((item) => (
            <button key={item.label} type="button" aria-current={item.current ? 'page' : undefined} onClick={() => follow(item.href)} className={`flex min-h-11 w-[90px] max-w-[25%] flex-col items-center gap-[5px] ${item.current ? 'text-[#2563eb]' : 'text-[#64748b]'}`}>
              <NavGlyph src={item.icon} active={!!item.current} />
              <span className="text-[10px] leading-[1.5]">{item.label}</span>
            </button>
          ))}
        </div>
      </nav>
    </div>
  )
}
