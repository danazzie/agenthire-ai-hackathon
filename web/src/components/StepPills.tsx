import type { Step } from '../model.ts'
import { STEPS } from '../model.ts'

/** 1 Post job → 2 Negotiate → … → 7 Appeal. The current step comes from the deal's state; Appeal is not built yet. */
export function StepPills({ current, shown, onShow }: { current: Step; shown: Step; onShow: (s: Step) => void }) {
  const at = STEPS.indexOf(current)
  return (
    <nav aria-label="Steps" className="desk-metrics flex min-w-0 gap-2 overflow-x-auto pb-1 sm:flex-wrap sm:overflow-visible">
      {STEPS.map((step, i) => {
        const done = i < at
        const now = i === at
        const unbuilt = step === 'Appeal'
        const tone = now
          ? 'bg-[#2563eb] text-white'
          : done
            ? 'bg-white text-[var(--bm-on)] shadow-sm'
            : 'bg-white text-[var(--bm-on-var)] shadow-sm'
        return (
          <button
            key={step}
            type="button"
            disabled={unbuilt}
            onClick={() => onShow(step)}
            aria-current={now ? 'step' : undefined}
            title={unbuilt ? 'Appeal to PayPal Disputes is not built in this version' : undefined}
            className={`inline-flex min-h-11 shrink-0 items-center rounded-xl px-3 text-sm font-semibold ${tone} ${shown === step && !now ? 'ring-2 ring-[var(--bm-primary-c)]' : ''} ${unbuilt ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}
          >
            {done ? '✓ ' : `${i + 1} `}
            {step}
            {unbuilt && <span className="ml-2 text-[10px] tracking-wide">NOT BUILT</span>}
          </button>
        )
      })}
    </nav>
  )
}
