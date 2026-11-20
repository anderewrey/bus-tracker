# UX flows

Sketches of the app's screens and how the user moves between them, to explain what each
control does in each context.

| Page | Artboard | File |
|---|---|---|
| 0 · Navigation | 0.1 · Views and navigation | `Navigation.dc.html` |
| 1 · Flows | 1.1 · A normal school day | `Main.dc.html` |
| | 1.2 · The Alarm switch (bell) | `AlarmSwitch.dc.html` |
| | 1.3 · Automatic start off | `AutoStart.dc.html` |
| | 1.4 · The map | `MapView.dc.html` |

`canvas.json` lists the pages and artboards (names, positions, sizes). Each `.dc.html` is one
artboard in the Design canvas format: it renders in the owner's Design canvas, not on its own
or on GitHub. These files are the source; the canvas is published from them, and edits made in
the canvas are copied back here.

How the sketches are drawn (what to show, notifications, labels, scenarios, naming):
[CONVENTIONS.md](CONVENTIONS.md).

An app change that alters a flow updates its artboard in the same pull request (DESIGN.md says
which artboard goes with which section).
