import type { Hold, Jar } from '../api.ts'
import { JAR_LABEL, money } from '../model.ts'

/** Per state: the word, its colour, how full the jar is, and which way the money went (if it left). */
const LOOK: Record<Jar, { text: string; fill: string; level: number; dashed?: boolean; out?: boolean }> = {
  EMPTY: { text: 'text-slate-500', fill: 'transparent', level: 0 },
  HELD: { text: 'text-held', fill: 'var(--color-held-fill)', level: 0.72 },
  CAPTURED: { text: 'text-captured', fill: 'var(--color-captured-fill)', level: 0.72, out: true },
  PAID: { text: 'text-paid', fill: 'var(--color-paid-fill)', level: 0, out: true },
  RELEASED: { text: 'text-released', fill: 'transparent', level: 0, dashed: true },
  COMPENSATION: { text: 'text-compensation', fill: 'var(--color-compensation-fill)', level: 0, out: true },
}

const STROKE: Record<Jar, string> = {
  EMPTY: '#94a3b8',
  HELD: 'var(--color-held)',
  CAPTURED: 'var(--color-captured)',
  PAID: 'var(--color-paid)',
  RELEASED: '#94a3b8',
  COMPENSATION: 'var(--color-compensation)',
}

/** The jar itself, hand-drawn: a lid, a body, and a fill level. Money that left is drawn as a coin going out. */
function JarGlyph({ jar, id }: { jar: Jar; id: string }) {
  const look = LOOK[jar]
  const stroke = STROKE[jar]
  const top = 34
  const bottom = 150
  const fillTop = bottom - (bottom - top) * look.level
  const body = 'M30 34 Q30 26 38 26 L102 26 Q110 26 110 34 L110 138 Q110 150 98 150 L42 150 Q30 150 30 138 Z'
  return (
    <svg viewBox="0 0 140 160" className="h-28 w-24 shrink-0 sm:h-36 sm:w-32" role="img" aria-label={`${JAR_LABEL[jar]} jar`}>
      <defs>
        <clipPath id={`jar-${id}`}>
          <path d={body} />
        </clipPath>
      </defs>
      <rect x="40" y="12" width="60" height="12" rx="3" fill="#e2e8f0" stroke={stroke} strokeWidth="3" />
      {look.level > 0 && <rect x="30" y={fillTop} width="80" height={bottom - fillTop} fill={look.fill} clipPath={`url(#jar-${id})`} />}
      <path d={body} fill="none" stroke={stroke} strokeWidth="4" strokeDasharray={look.dashed ? '8 6' : undefined} />
      {jar === 'HELD' && (
        <g transform="translate(58 76)" fill="none" stroke="var(--color-held)" strokeWidth="3.5">
          <path d="M5 12 V7 a7 7 0 0 1 14 0 V12" />
          <rect x="1" y="12" width="22" height="16" rx="3" fill="#fffbeb" />
        </g>
      )}
      {look.out && (
        <g>
          <circle cx="124" cy="22" r="11" fill={look.fill} stroke={stroke} strokeWidth="3" />
          <path d="M104 40 L116 30" stroke={stroke} strokeWidth="3" strokeLinecap="round" />
        </g>
      )}
    </svg>
  )
}

export function JarCard({ hold, title, party }: { hold: Hold | null; title: string; party: string }) {
  const jar: Jar = hold?.jar ?? 'EMPTY'
  const look = LOOK[jar]
  return (
    <section className="@container flex min-w-0 flex-col items-center rounded-2xl bg-white px-5 py-6 shadow-sm" aria-label={`${title} jar`}>
      <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-muted">{title}</div>
      <div className="mt-0.5 break-words text-center text-sm text-ink-soft">{party}</div>
      <JarGlyph jar={jar} id={hold?.id ?? title} />
      <div className={`max-w-full text-balance text-center text-2xl font-semibold leading-tight tracking-tight @[18rem]:text-4xl ${look.text}`}>{JAR_LABEL[jar]}</div>
      <div className="mt-1 text-3xl font-semibold tabular-nums">{money(hold?.amountMinor)}</div>
      <div className="mt-2 min-h-10 px-1 text-balance text-center text-sm leading-snug text-ink-soft">{hold?.caption ?? 'not held yet'}</div>
      <div className="mt-2 w-full break-all text-center font-mono text-[11px] text-muted" title={hold?.paypalOrderId ?? ''}>
        {hold?.paypalOrderId ? `order ${hold.paypalOrderId}` : 'no PayPal order yet'}
      </div>
    </section>
  )
}
