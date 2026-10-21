// The PROTOTYPES negotiation, scripted, for when the Claude agents are off (no key) or for a dependable recording.
// It goes through the same API as everyone else; the screen labels it as scripted.
import { api, type Contract } from './api.ts'

export const US1_SENTENCE = '50-word coffee-shop blurb, max $2, by 18:00, no lorem ipsum'
/** The PROTOTYPES pass blurb (50 words, English, no placeholder): the scripted delivery that passes the test. */
export const SCRIPTED_PASS_TEXT =
  'Beanhouse pours slow mornings into small cups. Local beans, oat milk on tap, a bench by the window that fits one laptop and two friends. Come for the cortado, stay for the quiet. Open seven to five, every single day, just two doors down from the old bookshop on Elm.'

const US1_TEST = { checks: [{ type: 'word_count', equals: 50 }, { type: 'language', equals: 'en' }, { type: 'not_placeholder' }] }

/** The next 18:00 here that is at least an hour away. */
function nextSixPm(now = new Date()): string {
  const due = new Date(now)
  due.setHours(18, 0, 0, 0)
  if (due.getTime() - now.getTime() < 3600_000) due.setDate(due.getDate() + 1)
  return due.toISOString()
}

export async function scriptedDeal(): Promise<Contract> {
  const verifier = await api.compile('50-word coffee-shop blurb for Beanhouse, no placeholder text', US1_TEST)
  const deal = await api.open('alex', 'maya', '50-word coffee-shop blurb for Beanhouse, no placeholder text', 200)
  const deadlineAt = nextSixPm()
  const say = (party: string, kind: 'PROPOSE' | 'ACCEPT', feeMinor: number, note: string) =>
    api.offer(deal.id, party, { kind, feeMinor, bondMinor: 40, deadlineAt, verifierId: verifier.id, note })
  await say('alex', 'PROPOSE', 150, 'Need a 50-word blurb for Beanhouse. Cap $2, done by 18:00.')
  await say('maya', 'PROPOSE', 180, '$1.80, bond $0.40.')
  await say('alex', 'PROPOSE', 150, '$1.50 and the test above. Bond $0.40 fine.')
  await say('maya', 'ACCEPT', 150, 'Accepted. $1.50 / bond $0.40 / 18:00.')
  return say('alex', 'ACCEPT', 150, 'Agreed.')
}
