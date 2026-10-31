import { useEffect, useRef } from 'react'
import { STEPS, type Step } from '../model.ts'

/** 1 Post job → 2 Negotiate → … → 7 Appeal. The current step comes from the deal's state; Appeal is not built yet. */
export function StepPills({ current, shown, onShow }: { current: Step; shown: Step; onShow: (s: Step) => void }) {
  const at = STEPS.indexOf(current)
  const currentRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const button = currentRef.current
    const nav = button?.parentElement
    if (!button || !nav || nav.scrollWidth <= nav.clientWidth) return
    const gap = button.getBoundingClientRect().left - nav.getBoundingClientRect().left - 16
    nav.scrollLeft += gap
  }, [current])
  return (
    <nav aria-label="Steps" className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 sm:mx-0 sm:flex-wrap sm:overflow-visible sm:px-0">
      {STEPS.map((step, i) => {
        const done = i < at
        const now = i === at
        const unbuilt = step === 'Appeal'
        const tone = now
          ? 'border-ink bg-ink text-paper'
          : done
            ? 'border-rule bg-paper text-ink'
            : 'border-rule bg-paper text-muted'
        return (
          <button
            key={step}
            ref={now ? currentRef : undefined}
            type="button"
            disabled={unbuilt}
            onClick={() => onShow(step)}
            aria-current={now ? 'step' : undefined}
            title={unbuilt ? 'Appeal to PayPal Disputes is not built in this version' : undefined}
            className={`min-h-11 shrink-0 rounded-sm border px-3 text-sm font-medium ${tone} ${shown === step && !now ? 'ring-2 ring-paid' : ''} ${unbuilt ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}
          >
            {done ? '✓ ' : `${i + 1} `}
            {step}
            {unbuilt && <span className="ml-2 text-[10px] tracking-wide text-muted">NOT BUILT</span>}
          </button>
        )
      })}
    </nav>
  )
}
