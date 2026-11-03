# Pitch — HireMind

**Tagline:** PayPal holds both sides. The test, not a mood, decides who gets paid.

**Elevator (8 seconds):** Agents can already do the work. They still cannot settle it. We hold the buyer’s fee *and* the seller’s bond on PayPal, compile the deal into a test, and capture or void — nobody is charged on an opinion.

---

## The problem, elevated

Every other “agentic payment” in this hackathon protects **one** side.

- Shopping agents: a human still clicks Pay.
- Freelance escrow clones: the **buyer** holds money; the **worker** risks only time. Ghosting is free.
- “AI referees”: a language model **opines** after delivery. Prompt injection is a business model.

PayPal asked what happens when AI is behind the wheel of **money**. The missing primitive is not checkout. It is **a deal two machines can finish without trusting each other**.

That is how markets actually clear: both parties post a stake, the contract is public, settlement is mechanical.

---

## The insight

**Void is not a refund.** A refund means you already took the money. A PayPal **authorization** means you never took it.

If the test fails, the buyer was never charged. If the seller no-shows, their **bond** — a small hold they posted to take the job — is the only money that moves, as compensation.

Thirty teams will show “AI judged the work, then PayPal paid.” We show **two jars**: fee and bond. One run **refuses**. The next run **pays**. Judges remember refusal.

---

## What we built (v1)

Two agents. One sentence from the human (“50-word blurb, max $2, by 18:00, no lorem ipsum”).

1. They **negotiate** price, deadline, and bond (default ≈ 25% of the fee).
2. The buyer’s agent turns the sentence into a structured test, and code **compiles** it into a verifier. Both sides see it. Its hash is written on **both** PayPal orders **before** anyone approves a hold.
3. Buyer authorizes the **fee**. Seller authorizes the **bond**. Status: **Held**. Nothing captured.
4. Seller delivers text or a file.
5. The **verifier runs code**, not a chat. Placeholder text fails. Fifty real words pass.
6. **FAIL** → void the fee (buyer never charged) → capture the bond → Payouts to the buyer.  
   **PASS** → capture the fee to the seller → void the bond (seller never charged).
7. Appeal through PayPal **Disputes** is designed but **not built** in v1. The screen says so.

v1 is **agent → agent**. The same rails later hire a person. We do not fake that in November.

---

## Why this is PayPal, not a spreadsheet

| Without PayPal | Without the agent |
|---|---|
| Two chatbots and a green “Paid” label | A human clicking Pay after a suggestion |
| You have a toy | You have checkout with extra steps |

Load-bearing on both sides. Orders v2 `AUTHORIZE` / capture / void, Payouts v1, `custom_id` as the mandate. (Disputes for appeal is not built yet.) It is a PayPal authorization hold (~29 days), **not** legal escrow. We say that on the README.

---

## Why we are not the 30th escrow

| They | We |
|---|---|
| Protect the buyer only | **Mutual stake** — worker posts a bond |
| LLM “looks good” after the fact | Test **compiled and hashed before** the hold |
| One happy-path “Paid” | **Refuse, then pay** in the same video |
| Human marketplace cosplay | Agents are the parties; human only typed the sentence |

The bond is a no-show deposit, the same idea hotels already use, applied to the seller. If they deliver, they lose nothing.

---

## Spoken pitch (30 seconds)

> PayPal already moves money for people. We asked it to settle a deal between two agents that do not trust each other.
>
> The client’s agent holds the fee. The worker’s agent holds a small bond. The job is a sentence that becomes a test, hashed onto both PayPal orders before anyone is charged.
>
> Junk delivery: the client is never charged, and the bond pays them for the wasted turn. Real work: the fee is captured, the bond is released.
>
> Money never moves on an opinion. PayPal is the envelope. The test is the contract.

---

## Video (90 seconds, muted-proof)

| Time | On screen | Say (optional) |
|---|---|---|
| 0:00–0:12 | One sentence. Two labelled agents. | Agents can work. They still cannot settle. |
| 0:12–0:28 | Two jars go **Held**. Hash + two sandbox order ids. | Fee and bond. Nothing captured. |
| 0:28–0:48 | Lorem ipsum in. Verifier FAIL. Fee **Released**. Bond **Compensation**. Dashboard: void + payout. | They never charged the client. |
| 0:48–1:12 | Real 50-word blurb. PASS. Fee **Paid**. Bond **Released**. Dashboard: capture + void. | Same rails. The test passed. |
| 1:12–1:25 | One line: “Same envelope for a $2 blurb or an $800 job.” | Not a marketplace. A settlement primitive. |
| 1:25–1:30 | End card: HireMind · PayPal sandbox ids | — |

No music. No logos you do not own. If the sound is off, the jars still tell the story.

---

## Devpost copy

**Short (about 400 characters):**  
HireMind lets two agents settle work on PayPal without trusting each other. The buyer’s fee and the seller’s bond are authorized, not captured. A test compiled from the job — hashed onto both orders — decides capture or void. Fail: the client is never charged; the bond compensates them. Pass: the seller is paid; the bond is released. Money never moves on an opinion.

**How we used PayPal:**  
Orders v2 with intent `AUTHORIZE`, then authorize, capture and void, for both the buyer’s fee and the seller’s bond. Payouts v1 sends a captured fee to the seller or a captured bond to the buyer. `custom_id` carries the verifier’s SHA-256 on both orders before either hold is approved. The screen changes only when PayPal confirms a step, and every sandbox call is logged per deal with its `PayPal-Request-Id`.

**How we used AI:**  
Claude (through the Anthropic Java SDK) runs both agents. The buyer’s agent turns one sentence into a job, a fee cap, a deadline and a structured test; code compiles that test into a deterministic verifier. The agents negotiate fee and bond, and the control plane refuses any offer over the cap. The seller’s agent writes the work and dry-runs it against the verifier before delivering. The agents cannot touch PayPal: nothing they can call captures, voids or pays. The model does not get to “feel” that the work is good.

---

## Prize lanes we actually aim at

1. **Best Use of Agentic Commerce** — two agents transact; money moves or is refused.  
2. **Best Use of PayPal + AI** — authorize/void/capture is the product.  
3. **Best Demo Delivery** — refuse, then pay.  
4. Overall only if the sandbox ids are real and the README is runnable.

We do not chase grocery, AG Grid, or a sad charity story.

---

## One slide

```
HIREMIND
PayPal holds both sides. The test decides.

  Client agent ──AUTHORIZE fee──▶  [ JAR: FEE ]
  Seller agent ──AUTHORIZE bond─▶  [ JAR: BOND ]
                     ▼
              compiled test
              (hash on both orders)
                     ▼
           FAIL: void fee, pay bond to client
           PASS: capture fee, void bond
```

---

## If a judge pushes back

| They say | You say |
|---|---|
| “That’s just escrow.” | Escrow takes the money. We **never capture** on fail. And the **seller** posts a hold too. |
| “The AI is the judge.” | The AI **writes the test**. Code **runs** it. The hash was on the order before delivery. |
| “Two bots paying $2 is a toy.” | The human typed the cap. The envelope is the same for a real invoice. We are not Upwork in four weeks. |
| “Holdfast already did this.” | They protect the buyer and ask Gemini after the fact. We hold **both** sides and settle on a **pre-agreed** verifier. |
| “Show me PayPal.” | Two order ids, one void, one capture, one payout — in the dashboard, in the video. |

---

## Close

Do not pitch a marketplace. Pitch a **rail**: the first PayPal envelope two untrusted agents can finish, where refusal is a first-class outcome.

*PayPal holds both sides. The test, not a mood, decides who gets paid.*
