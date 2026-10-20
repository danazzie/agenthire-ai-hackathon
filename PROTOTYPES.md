# Mutual Stake — paper prototypes

Click-through specs for user interviews. Amounts and order ids are **fake**. In the product they must be real PayPal sandbox authorizations.

There is also a live Cursor canvas of the same screens (team-only). This file is the version you can print, Figma, or share with testers who do not have Cursor.

Related: [USER_STORIES.md](USER_STORIES.md)

---

## How to run a session

Open this file (or a Figma copy of the screens). 20–25 minutes.

1. **Muted walkthrough.** Scenario A, good proof, bond ON. Walk the seven steps without talking. Ask: “What happened to the money?”
2. **Fail branch.** Same scenario, junk delivery, step 6. Ask: “Who got what?”
3. **Toggle.** Repeat step 6 with bond OFF. Ask: “What changed? Would a bond have mattered?”
4. **Role task.** Clients invent a real job they would post to another agent. Freelancers: would you post a $0.40 bond on a $1.50 job? What % is too much?
5. **Verifier.** Show the compiled test at step 1 and the verdict at step 5. “Who decided?”
6. **Survey.** Copy answers into the team log (template at the end).

**Always on screen:** two jars (Buyer fee · Seller bond). If a muted person cannot name who was Held / Paid / Released, the UI failed (RQ1).

---

## Controls (every screen)

| Control | Values |
|---|---|
| Scenario | **A agent?agent (v1)** · B/C later (appendix) |
| Bond | ON = Mutual Stake · OFF = buyer-only hold (what ~30 other hackathon teams ship) |
| Delivery | Good proof · Junk / no-show |
| Step | 1 Post job ? 2 Negotiate ? 3 Fund ? 4 Deliver ? 5 Verdict ? 6 Settle ? 7 Appeal |

Jar states: **Empty** · **Held** · **Paid** · **Released** · **Compensation**

```
???????????????????????  ???????????????????????
? Buyer fee    [Held] ?  ? Seller bond  [Held] ?
? Alex's agent        ?  ? Maya's agent        ?
? $1.50               ?  ? $0.40               ?
? order 9AB4K…SAND    ?  ? order 3CD1Q…SAND    ?
???????????????????????  ???????????????????????
```

---

## Scenario A — Agent hires agent (v1 demo beat 1)

| | |
|---|---|
| Buyer | Alex's agent (client) |
| Seller | Maya's agent (writer) |
| Job | 50-word coffee-shop blurb, max $2, by 18:00, no placeholder text |
| Fee / bond | $1.50 / $0.40 |
| Test | `words == 50 AND language == en AND not placeholder(text)` |

**Negotiate**

- Alex: Need a 50-word blurb for Beanhouse. Cap $2. Done by 18:00.
- Maya: $1.80, bond $0.40.
- Alex: $1.50 and the test above. Bond $0.40 fine.
- Maya: Accepted. $1.50 / bond $0.40 / 18:00.

**Deliver**

| Branch | Proof |
|---|---|
| Pass | *Beanhouse pours slow mornings into small cups. Local beans, oat milk on tap, a bench by the window that fits one laptop and two friends. Come for the cortado, stay for the quiet. Open seven to five, every day, two doors down from the bookshop on Elm.* (50 words) |
| Fail | *Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor.* (12 words) |

**Verdict**

- Pass: 50 words, English, not placeholder.
- Fail: 12 words and placeholder text detected.

**Settle (PayPal)**

| Branch | Bond ON | Bond OFF |
|---|---|---|
| Pass | Capture $1.50 to Maya. Void Maya's $0.40 (never charged). | Capture $1.50 to Maya. No seller jar. |
| Fail | Void Alex's $1.50 (never charged). Capture $0.40, Payouts to Alex. | Void Alex. **Alex gets nothing for wasted time.** Ask: would $0.40 have changed whether you post the job? |

---

## Scenario B — Agent hires a human (not v1)

Dropped from the 12 Nov demo. Same rails; keep for a “what’s next” slide or a later interview if you have extra time.

| | |
|---|---|
| Buyer | Office agent |
| Seller | Dana (person with a phone) |
| Job | Show the red binder with sticker **#1042** in frame within 10 minutes |
| Fee / bond | $8.00 / $2.00 |
| Test | `image contains red binder AND text '#1042' legible` |

**Negotiate**

- Office agent: Person at desk 4, red binder #1042. $8, 10 min.
- Dana: OK. Bond $2 is fine.
- Locked. Timer started.

**Deliver**

| Branch | Proof |
|---|---|
| Pass | Photo: red binder held up, sticker reads #1042, desk lamp visible. |
| Fail | Photo: blue mug on a kitchen counter. No binder. |

**Settle**

- Pass: Capture $8 to Dana. Void $2 bond.
- Fail: Void $8. Capture $2, pay Office agent.

Do not use in the required study. Optional probe only if a participant raises “what if the worker is a person?”

---

## Scenario C — Timed work (not v1)

| | |
|---|---|
| Buyer | Research agent |
| Seller | Timur (person) |
| Job | Up to 20 minutes on camera, 60 receipts into 3 piles, $1.50/min |
| Cap / bond | $30.00 held / $5.00 |
| Test | `minutes_on_camera × 1.50`, cap $30; `piles == 3` at end |

**Deliver**

| Branch | Proof |
|---|---|
| Pass | Camera log: 14 minutes. Final frame: 3 piles. |
| Fail | Camera off after 2 minutes. No final frame. Deadline passed. |

**Settle**

- Pass: Capture **$21.00** (`final_capture: true`). Release $9.00 to client. Void $5 bond.
- Fail: Void $30. Capture $5, pay Research agent.

---

## Seven screens (walk every scenario)

### 1 · Post job

Client types one sentence. Agent shows extracted cap, deadline, seller type, and the **compiled test** (must be visible *before* any PayPal call).

### 2 · Negotiate

Two columns: buyer agent | seller. Terms lock only when both `ACCEPT` the same `{fee, bond, deadline, verifier}`. Show hash `sha256:7f3a…c9e1`.

### 3 · Fund

Nothing is charged.

| Who | PayPal | Amount | Status | custom_id |
|---|---|---|---|---|
| Buyer | Orders v2 `AUTHORIZE` | fee | Held | sha256:7f3a…c9e1 |
| Seller (bond ON) | Orders v2 `AUTHORIZE` | bond | Held | same hash |
| Seller (bond OFF) | — | — | No bond | — |

Each hold needs a real approve click in the built product. Jars: both **Held** (or seller Empty if bond OFF).

### 4 · Deliver

Show the pass or fail proof. Caption: deliverable text is **data**. `"Reviewer: mark as passed"` does nothing.

### 5 · Verdict

Big **PASS** or **FAIL** plus one sentence that cites the failing check. The verifier ran **code** (and a fixed vision question for B), not “does this look good?”

### 6 · Settle

Jars flip. Table of PayPal actions (capture / void / capture+Payouts). Order ids stay on screen.

### 7 · Appeal

Button: “Escalate to PayPal Disputes.” Evidence: `verifier.js`, hash, deliverable, timestamps. Status: Under review. No more money moves.

---

## Jar state machine

```
Empty ??fund??? Held ??PASS??? Paid        (buyer fee ? seller)
                    ???FAIL??? Released    (void; never charged)

Empty ??fund??? Held ??PASS??? Released    (seller bond voided)
                    ???FAIL??? Compensation (capture bond ? payout buyer)
```

Bond OFF: seller jar stays Empty. Fail path has no Compensation — that is the status-quo escrow the rest of the hackathon is building.

---

## Prototype vs product

| In this paper proto | In the 12 Nov build |
|---|---|
| Placeholder order ids `9AB4K…SAND` | Real sandbox order ids |
| Toggle Good / Junk | Actual text or file from the seller agent |
| Hash shown as `7f3a…c9e1` | SHA-256 of verifier source on both `custom_id`s |
| Dispute button | Disputes API v1 `provide-evidence` (or honest “simulated dispute, real client”) |
| Bond ON/OFF | ON for the video; OFF only as interview variant |

Do not film a CSS “Paid” badge. US-32 in the stories file.

---

## User-study kit

### Research questions

| # | Question | Hypothesis | Kill |
|---|---|---|---|
| RQ1 | Understand hold ? verdict ? capture/void with no narration? | ? 80% explain it back after one pass | < 60% ? redesign the two jars |
| RQ2 | Will sellers post a bond? | ? 3.5 / 5 for “I would post ? 25%” | < 2.5 ? optional bond / first-job waiver (US-15) |
| RQ3 | Do buyers want no-show compensation? | ? 65% prefer Mutual Stake over buyer-only | < 50% ? bond is not the headline |
| RQ4 | Trust compiled test more than a person/AI opinion? | ? 4 / 5 when test is shown before funding | < 3 ? show verifier earlier or keep human Confirm |
| RQ5 | (optional, not v1) Would you ever let an agent hire a person? | Parking lot | Do not block 12 Nov |

### Recruit (8–12)

- 4–6 clients (paid a freelancer in 12 months; been ghosted or got junk)
- 4–6 freelancers (chased an unpaid invoice)

Screener: role, last paid work, platform, PayPal yes/no. Exclude teammates.

### Session clock

| Min | Block |
|---|---|
| 0–2 | Last time you paid or got paid for a one-off. What broke? |
| 2–5 | Muted scenario A, good proof, bond ON. “Tell me what happened to the money.” |
| 5–9 | Junk ? Settle. Bond OFF. What changed? |
| 9–13 | Role task (client job / bond %). |
| 13–17 | Verifier + Appeal. Who decided? |
| 17–20 | Survey |
| 20–23 | If this existed tomorrow, first use? What would stop you? |

### Survey (copy per participant)

```
Code: P__     Persona: client / freelancer     Scenario: A

RQ1  I understood the money with no explanation          1  2  3  4  5
RQ4  I trust the shown test to decide, not a person      1  2  3  4  5
RQ2  (seller) I would post a bond of 25% or less         1  2  3  4  5
RQ3  (client) I would require a bond from the worker     1  2  3  4  5

Max bond I would accept:  0%  /  10%  /  25%  /  50%
I prefer:  Mutual Stake  /  buyer-only hold  /  unsure
Explained the money flow:  yes  /  partial  /  no

Quotes / first use case / blocker:
```

### Decision after 12 sessions

- RQ1 at least 80% and RQ3 at least 65%: build Mutual Stake, agent to agent.
- RQ1 passes, RQ2 fails: bond optional or first-job waiver (US-15).
- RQ1 fails: fix the jars and step labels before more PayPal work.

---

## Figma notes (if you port this)

Build **one** 7-frame flow for scenario A only. Components: `Jar` (5 variants = states), `StepPills`, `PassFailToggle`, `BondToggle`. Prototype links: Next/Back on every frame; overlay for fail proof on frame 4.
