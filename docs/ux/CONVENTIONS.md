
# UX flows: conventions

How the sketches in this folder are drawn and kept up to date. Read it before adding or
changing a flow, an artboard or the navigation page.

## What a sketch shows

- **The app as it is, not a comparison.** A change updates the artboards in place; no "today" and
  "proposed" side by side (user, 2026-11-27). The why goes in the captions and DESIGN.md.
- **Minimal, not 1:1.** Only the app's own elements that matter to the flow. Nothing decorative:
  every extra element is more to maintain. Exact pixels, the status bar and real styling are out.
- **Same screen in every step.** All phones in a flow show the same screen with the same blocks
  (for Bus runs: Automatic start, the Pickup card, the Dropoff card); only what changes changes.
  Never drop a block from one step and keep it in another.
- **Off-topic UI is dimmed, not removed.** App elements present on the screen but not the subject
  (the header: Bus runs · Rescan · Debug · Settings) stay at 30 % opacity, for uniformity.
- **System notifications are outside the phone**, stacked above it ("System notifications",
  or "System notifications: none"). Plain: white with a grey border. Only the ringing alarm is
  red, because it is a state that changes what is expected.
- **Explanations are under the phone**, never inside the UI. Say why the app behaves so and
  what is expected, not an inventory of what is drawn.
- **Arrows between phones carry the trigger**: what the user does in bold ("pickup bell → off"),
  what happens by itself in italics ("7:03, by itself", "bus enters the main zone").
- **Colour**: greys; one accent for a switch that is on; red only for the alarm and errors.
- **Labels are generic and short**, not applied examples: "Automatic start ›", not "Mon–Fri ›".
  Inside a scenario's screens, example content ("Mon–Fri", "Off until Tue Nov 24") is fine.
- **The app's words**: "watching" (never "monitoring" or "tracking" on screen), Stop watching,
  Test alarm, Reload from BusPlanner, Watching days (DESIGN.md §8, Words and labels on screen).
- **Fake data only**: B101, 123 This Street, This Street, That Street, Home zone. Never a real
  child, school, stop, street or route from the logs.

## Scenarios

- **Credible, from real school life**: a normal day, a child staying home, a storm closing the
  schools, a long weekend, a second parent who handles one run now and then. If nobody would do
  it, don't draw it, even when the app allows it.
- **Always answer "and then?"**: draw until the state is back to normal (the bell back on or off
  by itself, the next period, watching restarting).
- One row per scenario; split two unrelated gestures into two rows.

## Reference page (Page 0)

What the app has, as opposed to Page 1 · Flows, what happens over time: the views and how to move
between them (0.1), the notifications (0.2).

### Navigation (0.1)

- Hubs and spokes: a view, then "opens one of →", then a dashed frame holding every view opened
  from it, side by side. Each one's Back returns to the view that opened it.
- A view is drawn once. Elsewhere a dashed chip points to it ("↑ Settings, drawn above"); drop
  the chip when the link is trivial.
- A view that opens many others gets its own row, drawn in full, with its own frame.
- Android system views the user passes through (sound picker, channel settings) are chips
  marked "Android system view".

## Naming and layout

- **Page N · name**, **Artboard N.M · name**, **Row N.M.K · name** (inside the artboard).
- An artboard is as wide as its widest row, no wider; artboards on a page may differ.
- Resize the artboard (its root size, `$preview` and its `canvas.json` entry) in the same change
  that adds content, so no scrollbar ever appears.

## Working on them

- Keep it light: small edits, one artboard at a time. Don't render or screenshot to check unless
  asked; the owner looks and comments.
- Before changing a file the owner may have edited in the canvas, read the canvas's version and
  start from it, then copy the result back into `docs/ux/`.
- An app change that alters a flow updates its artboard in the same pull request, like DESIGN.md.
- A review against outside references (Nielsen's 10 heuristics with a severity per finding,
  Material Design for Android components) only when asked. Rules written for 1:1 wireframes
  (every status bar, every state of every screen) don't apply here.
