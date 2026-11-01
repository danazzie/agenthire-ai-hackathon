// Fixtures for looking at every jar state and every step without a live deal. Development aid, not a demo.
import type { Contract, Hold, Jar, Side } from '../api.ts'
import { DealView } from './DealView.tsx'
import { JarCard } from './JarCard.tsx'
import { RoleDesk } from './RoleDesk.tsx'
import { isRole } from '../role.ts'

const HASH = 'dcf42a28d65b2ab8abdfc108b3e86904d56e0a3cd7d56dc61b2112843aa5bc7d'

function hold(side: Side, state: string, jar: Jar, caption: string, extra: Partial<Hold> = {}): Hold {
  const fee = side === 'BUYER_FEE'
  return {
    id: `${side}-${state}`, side, payer: fee ? 'alex' : 'maya', amountMinor: fee ? 150 : 40, state, jar, caption, customId: HASH,
    paypalOrderId: fee ? '5KP96021YH834471D' : '5WN30283VU936141M', approveUrl: 'https://www.sandbox.paypal.com/checkoutnow?token=X',
    authorizationId: jar === 'EMPTY' ? null : fee ? '3KR19753NY052961C' : '6B127203P6458315P', authorizationExpiresAt: null,
    captureId: null, capturedMinor: null, payoutBatchId: null, payoutItemId: null, needsAttention: false, attentionReason: null, ...extra,
  }
}

const offers = [
  ['alex', 'PROPOSE', 150, 'Need a 50-word blurb for Beanhouse. Cap $2, done by 18:00.'],
  ['maya', 'PROPOSE', 180, '$1.80, bond $0.40.'],
  ['alex', 'PROPOSE', 150, '$1.50 and the test above. Bond $0.40 fine.'],
  ['maya', 'ACCEPT', 150, 'Accepted. $1.50 / bond $0.40 / 18:00.'],
  ['alex', 'ACCEPT', 150, 'Agreed.'],
].map(([party, kind, fee, note], i) => ({
  id: `o${i}`, party: party as string, kind: kind as 'PROPOSE' | 'ACCEPT', feeMinor: fee as number, bondMinor: 40,
  deadlineAt: '2026-10-21T15:00:00Z', verifierId: 'v', note: note as string, createdAt: `2026-10-21T11:0${i}:00Z`,
}))

const checksFail = [
  { name: 'word_count', passed: false, detail: '12 words', expected: 'exactly 50 words', evidence: null },
  { name: 'language', passed: false, detail: 'not English', expected: 'English', evidence: null },
  { name: 'not_placeholder', passed: false, detail: 'placeholder text detected', expected: 'no placeholder text', evidence: 'lorem ipsum' },
]

function deal(over: Partial<Contract>): Contract {
  return {
    id: 'fixture', state: 'DRAFT', sessionId: 's', buyer: { handle: 'alex', displayName: "Alex's agent" }, seller: { handle: 'maya', displayName: "Maya's agent" },
    sellerType: 'AGENT', jobText: 'A 50-word coffee-shop blurb with no placeholder text.', sentence: '50-word coffee-shop blurb, max $2, by 18:00, no lorem ipsum',
    feeCapMinor: 200, feeMinor: 150, bondMinor: 40, currency: 'USD', deadlineAt: '2026-10-21T15:00:00Z', verifier: null, termsHash: HASH, termsDocument: null,
    holds: [], offers, transitions: [], ledger: [], delivery: null, awaitingConfirmation: false, settlementPlannedAt: null, settlementConfirmedAt: null,
    needsAttention: false, attentionReason: null, inFlight: [], outcome: null, createdAt: '', updatedAt: '', ...over,
  }
}

const delivered = (verdict: 'PASS' | 'FAIL') => ({
  id: 'd', submittedAt: '2026-10-21T11:10:00Z', payloadSha256: HASH,
  text: verdict === 'FAIL' ? 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor.' : 'Beanhouse pours slow mornings into small cups. Local beans, oat milk on tap, a bench by the window that fits one laptop and two friends. Come for the cortado, stay for the quiet. Open seven to five, every single day, just two doors down from the old bookshop on Elm.',
  verdict, failingCheck: verdict === 'FAIL' ? 'word_count' : null,
  summary: verdict === 'FAIL' ? '12 words, not English and placeholder text detected.' : '50 words, English, no placeholder text.',
  checks: verdict === 'FAIL' ? checksFail : checksFail.map((c, i) => ({ ...c, passed: true, detail: ['50 words', 'English', 'no placeholder text'][i], evidence: null })),
  injectionFlags: [], verifiedAt: '2026-10-21T11:10:01Z',
  run: { id: 'r', outcome: verdict, runtime: 'deno 2.9.7 (stable, release, aarch64-apple-darwin) / v8 15.0.245.2-rusty', harnessSha256: HASH, sourceSha256: HASH, inputSha256: HASH, durationMs: 17, ranAt: '', error: null },
  attempts: 1, needsAttention: false, attentionReason: null,
})

const STAGES: [string, Contract][] = [
  ['Negotiating, an agent mid-turn', deal({ state: 'DRAFT', termsHash: null, feeMinor: null, bondMinor: null, offers: offers.slice(0, 2), inFlight: [{ step: 'AGENT_TURN_BUYER', label: "Alex's agent is thinking", attempts: 1, lastError: null }] })],
  ['Funding: one hold placed', deal({ state: 'TERMS_LOCKED', holds: [hold('BUYER_FEE', 'HELD', 'HELD', '$1.50 held, not charged'), hold('SELLER_BOND', 'ORDER_CREATED', 'EMPTY', '$0.40, not held yet', { authorizationId: null })], inFlight: [{ step: 'CHECK_ORDER', label: 'Waiting for the seller bond to be approved on PayPal', attempts: 1, lastError: null }] })],
  ['Verdict FAIL, waiting for the first-capture confirm', deal({ state: 'FAIL', awaitingConfirmation: true, delivery: delivered('FAIL'), holds: [hold('BUYER_FEE', 'HELD', 'HELD', '$1.50 held, not charged'), hold('SELLER_BOND', 'HELD', 'HELD', '$0.40 held, not charged')] })],
  ['FAIL settling: payout in flight', deal({ state: 'FAIL', settlementPlannedAt: 'x', delivery: delivered('FAIL'), holds: [hold('BUYER_FEE', 'VOIDED', 'RELEASED', 'Released: $1.50 never charged'), hold('SELLER_BOND', 'PAYOUT_REQUESTED', 'CAPTURED', "Captured $0.40; paying out to Alex's agent", { captureId: '7BE03498X60788205', payoutBatchId: 'SFGM4ZYWWS2HE' })], inFlight: [{ step: 'CHECK_PAYOUT', label: 'Waiting for PayPal to deliver the seller bond payout', attempts: 3, lastError: null }] })],
  ['FAIL settled', deal({ state: 'SETTLED', settlementPlannedAt: 'x', delivery: delivered('FAIL'), outcome: "Alex's agent was never charged; Maya's agent's $0.40 bond was paid to Alex's agent as compensation.", holds: [hold('BUYER_FEE', 'VOIDED', 'RELEASED', 'Released: $1.50 never charged'), hold('SELLER_BOND', 'PAID_OUT', 'COMPENSATION', "Compensation: $0.40 paid to Alex's agent", { captureId: '7BE03498X60788205', payoutBatchId: 'SFGM4ZYWWS2HE', payoutItemId: 'EWAXQE5VL83US' })] })],
  ['PASS settled', deal({ state: 'SETTLED', settlementPlannedAt: 'x', delivery: delivered('PASS'), outcome: "Maya's agent was paid $1.50; Maya's agent's $0.40 bond was released, never charged.", holds: [hold('BUYER_FEE', 'PAID_OUT', 'PAID', "Paid: $1.50 to Maya's agent", { captureId: '9AB4K', payoutBatchId: 'B2', payoutItemId: 'I2' }), hold('SELLER_BOND', 'VOIDED', 'RELEASED', 'Released: $0.40 never charged')] })],
]

const JARS: [Jar, Side, string][] = [
  ['EMPTY', 'BUYER_FEE', '$1.50, not held yet'], ['HELD', 'BUYER_FEE', '$1.50 held, not charged'],
  ['CAPTURED', 'SELLER_BOND', "Captured $0.40; paying out to Alex's agent"], ['PAID', 'BUYER_FEE', "Paid: $1.50 to Maya's agent"],
  ['RELEASED', 'BUYER_FEE', 'Released: $1.50 never charged'], ['COMPENSATION', 'SELLER_BOND', "Compensation: $0.40 paid to Alex's agent"],
]

export function Gallery() {
  const params = new URLSearchParams(window.location.search)
  const stage = params.get('stage')
  const role = params.get('role')
  if (stage !== null) {
    const [title, c] = STAGES[Number(stage)] ?? STAGES[0]
    return (
      <div className="flex flex-col gap-2">
        <p className="text-sm text-slate-500">Fixture: {title}{isRole(role) ? ` · ${role} desk` : ''}</p>
        {isRole(role) ? <RoleDesk role={role} deal={c} /> : <DealView deal={c} verifier={null} calls={null} />}
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-6">
      <section>
        <h2 className="mb-2 font-semibold">Every jar state</h2>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {JARS.map(([jar, side, caption]) => (
            <JarCard key={jar} title={side === 'BUYER_FEE' ? 'Buyer fee' : 'Seller bond'} party={side === 'BUYER_FEE' ? "Alex's agent" : "Maya's agent"} hold={hold(side, jar, jar, caption)} />
          ))}
        </div>
      </section>
      <ul className="list-inside list-disc text-sm">
        {STAGES.map(([title], i) => <li key={title}><a className="underline" href={`/gallery?stage=${i}`}>{title}</a></li>)}
      </ul>
    </div>
  )
}
