# Diagrams

| Picture | Shows |
| --- | --- |
| [`design-vs-lab.png`](design-vs-lab.png) | The Hello Interview final design, coloured by what this lab did with each part: built as designed, built simpler, or not built. Plus what the lab measured. |
| [`system-components.png`](system-components.png) | What actually runs in this repo: one Spring Boot app, Postgres, Redis, Elasticsearch, and the step 10 outbox flow. |

## Change a diagram

Each PNG is drawn from the `.html` file next to it (a plain SVG inside). Nothing to install:

1. Edit the `.html` file (boxes are `<rect>`, labels are `<text>`, arrows are `<line>` / `<path>`).
2. Open it in Chrome:

```bash
open docs/design/diagrams/design-vs-lab.html
```

3. Save it as a PNG: Chrome menu View, Developer, Developer Tools. Then press `Cmd+Shift+P`,
   type `screenshot`, choose **Capture full size screenshot**, and save it over the old PNG.
