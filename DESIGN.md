---
name: Suko
description: Typed Java components compiled to plain JTE, documented as a transit line map.
colors:
  map-paper: "#fafaf9"
  station-white: "#ffffff"
  line-ink: "#0f172a"
  night-ink: "#0b1220"
  tunnel-black: "#020617"
  body-slate: "#334155"
  secondary-slate: "#475569"
  muted-slate: "#64748b"
  code-light: "#f1f5f9"
  teletext-teal: "#5eead4"
  home-field-teal: "#14b8a6"
  juice-teal: "#2dd4bf"
  line-core: "#0d9488"
  line-core-ink: "#0f766e"
  line-registry: "#d97706"
  line-registry-ink: "#b45309"
  line-cli: "#65a30d"
  line-cli-ink: "#4d7c0f"
  line-site: "#e11d48"
  line-site-ink: "#be123c"
typography:
  display:
    fontFamily: "Overpass, ui-sans-serif, system-ui, sans-serif"
    fontSize: "3rem"
    fontWeight: 800
    lineHeight: 1.05
  headline:
    fontFamily: "Overpass, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 800
    lineHeight: 1.33
  title:
    fontFamily: "Overpass, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 800
    lineHeight: 1.4
  lead:
    fontFamily: "IBM Plex Sans, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 400
    lineHeight: 1.4
  body:
    fontFamily: "IBM Plex Sans, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
  code:
    fontFamily: "JetBrains Mono, ui-monospace, SFMono-Regular, monospace"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.625
    fontFeature: "\"liga\" 0, \"calt\" 0"
  label:
    fontFamily: "JetBrains Mono, ui-monospace, SFMono-Regular, monospace"
    fontSize: "11px"
    fontWeight: 400
    lineHeight: 1.25
    letterSpacing: "0.06em"
rounded:
  none: "0px"
spacing:
  cell-y: "4px"
  cell-x: "8px"
  panel: "16px"
  code: "20px"
  station-gap: "56px"
  page-y: "56px"
components:
  button-primary:
    backgroundColor: "{colors.night-ink}"
    textColor: "{colors.station-white}"
    typography: "{typography.code}"
    rounded: "{rounded.none}"
    padding: "12px 20px"
  button-primary-hover:
    backgroundColor: "{colors.station-white}"
    textColor: "{colors.night-ink}"
  button-secondary:
    backgroundColor: "{colors.station-white}"
    textColor: "{colors.night-ink}"
    typography: "{typography.code}"
    rounded: "{rounded.none}"
    padding: "12px 20px"
  button-secondary-hover:
    backgroundColor: "{colors.night-ink}"
    textColor: "{colors.station-white}"
  panel:
    backgroundColor: "{colors.station-white}"
    textColor: "{colors.line-ink}"
    rounded: "{rounded.none}"
    padding: "{spacing.panel}"
  code-block:
    backgroundColor: "{colors.tunnel-black}"
    textColor: "{colors.code-light}"
    typography: "{typography.code}"
    rounded: "{rounded.none}"
    padding: "{spacing.code}"
  addr-cell:
    backgroundColor: "{colors.tunnel-black}"
    textColor: "{colors.teletext-teal}"
    rounded: "{rounded.none}"
    padding: "4px 8px"
  station-marker:
    backgroundColor: "{colors.line-core}"
    textColor: "{colors.night-ink}"
    rounded: "{rounded.none}"
    size: "32px"
  station-marker-hollow:
    backgroundColor: "{colors.station-white}"
    textColor: "{colors.muted-slate}"
    rounded: "{rounded.none}"
    size: "32px"
  navstop-marker:
    backgroundColor: "{colors.station-white}"
    rounded: "{rounded.none}"
    size: "22px"
  navstop-marker-current:
    backgroundColor: "{colors.line-core}"
    rounded: "{rounded.none}"
    size: "22px"
---

# Design System: Suko

## Overview

**Creative North Star: "The Line Map"**

Suko's documentation is drawn as a transit schematic of its own compiler pipeline. Every pipeline stage is a line with its own color, every page is a station on the line it documents, and the site navigation is literally four stops on one drawn line. The geometry is the schematic's: right angles only, flat 2px ink outlines, square station markers, straight rails. Nothing floats, nothing is rounded, nothing casts a shadow.

Two densities share the world. The home page is the one Persuade surface: a full-bleed teal field that shows the real `.sk` source station, a drawn rail, and the real `.jte` output station before any prose, then the main line of four stops beneath it. Every other page is a Read page on off-white map paper, where sections are stations sitting on a vertical rail in the page's line color. Code sits in ink blocks with a line-colored rule on top; version and status chrome renders as flat monospace address cells, the teletext raise.

The signature motion is the compile: rails draw in stepped increments and the template's characters settle split-flap style onto the real compiler output. Motion only ever adds to a page that is already complete; without JavaScript or under reduced motion, the final state is what renders.

**Key Characteristics:**
- Right angles only; global corner radius is zero.
- Flat 2px ink outlines; no shadows, no lift.
- One color per pipeline stage; a page takes the color of the stage it documents.
- Sections are stations on a drawn rail; the nav is a line map.
- Grotesk display, humanist sans body, true monospace code with ligatures off.
- Real compiler output as the hero, never an illustration.

## Colors

Four saturated line colors on neutral map paper and ink; the lines carry meaning, the neutrals carry everything else.

### Primary
- **Core Line Teal** (line-core): the Core stage line. Rails, markers and hover fills on the Language Reference; the brand's default line (logo mark, footer link, fallback for `--line`). Its lighter sibling **Home Field Teal** (home-field-teal) is the full-bleed Persuade field on the home page only. **Juice Teal** (juice-teal) belongs to the logo alone: the Suko mark is "juice over JTE", a Night Ink block (the plain JTE template) with Juice Teal running down over it in right-angle drips (`assets/favicon.svg`, repeated inline in the header). Never redraw the drips rounded, and never use Juice Teal outside the mark.
- **Core Ink Teal** (line-core-ink): Core teal darkened for text links on map paper.

### Secondary
- **Registry Line Amber** (line-registry) / **Registry Ink Amber** (line-registry-ink): the Registry stage; Components index and detail pages.
- **CLI Line Lime** (line-cli) / **CLI Ink Lime** (line-cli-ink): the CLI stage; Getting Started.

### Tertiary
- **Website Line Rose** (line-site) / **Website Ink Rose** (line-site-ink): the Website stage; the Roadmap page. Station numerals on rose markers are white, because ink on rose falls under 4.5:1.

### Neutral
- **Map Paper** (map-paper): page background and header on every Read page.
- **Station White** (station-white): panels, tables, hollow markers, the resting fill of every stop marker.
- **Line Ink** (line-ink): every outline, divider, drawn nav line, trunk rail, and heading text.
- **Night Ink** (night-ink): hero rails and station squares, primary buttons, logo tile, text on the teal field.
- **Tunnel Black** (tunnel-black): code blocks, address cells, footer.
- **Body Slate** (body-slate) for running prose; **Secondary Slate** (secondary-slate) for leads, descriptions and nav labels at rest; **Muted Slate** (muted-slate) for captions and hollow-marker numerals.
- **Code Light** (code-light): text inside code blocks. **Teletext Teal** (teletext-teal): text inside address cells and the footer link.

### Named Rules
**The One Line Per Stage Rule.** Each pipeline stage owns exactly one line color: CLI lime, Core teal, Registry amber, Website rose. A page sets its stage's line on its root (`--line` for fills and rails, `--line-ink` for text) and every marker, rail, code rule and link on it inherits. A new surface picks the stage it documents; it does not invent a fifth hue.

**The No Violet Rule.** Violet was a line color and was removed after the detector flagged it as the AI-palette tell. Don't reintroduce violet or purple for any stage.

**The Destination Color Rule.** A link that leaves the page for another stage's page may take the destination's line (the home page's roadmap link is rose); otherwise links take the ambient page line.

## Typography

**Display Font:** Overpass (with ui-sans-serif, system-ui)
**Body Font:** IBM Plex Sans (with ui-sans-serif, system-ui)
**Label/Mono Font:** JetBrains Mono (with ui-monospace, SFMono-Regular)

**Character:** Overpass is signage type, the Highway Gothic lineage, so headings read like station names; Plex keeps long reference prose calm; JetBrains Mono carries every piece of code and every piece of chrome (nav labels, buttons, captions, address cells).

### Hierarchy
- **Display** (800, 3rem on Read pages; 2.25rem to 3rem on the home hero at 1.05): one per page, the page title or the hero claim.
- **Headline** (800, 1.5rem, 2rem line): station headings on the route.
- **Title** (800, 1.25rem): home stop names, roadmap station titles, definition-list terms.
- **Lead** (400, 1.25rem): the single paragraph under a page title, in secondary slate, max 42rem.
- **Body** (400, 1rem, 1.5): prose, capped at 42rem (`max-w-2xl`).
- **Code** (400, 0.875rem, 1.625): code blocks and hero stations; ligatures and contextual alternates off.
- **Label** (400 to 500, 11px to 12px, uppercase, 0.06em tracking in the nav): nav stop names, buttons, figure captions, address cells.

### Named Rules
**The Two Characters Rule.** Code never uses programming ligatures: `!=` renders as two characters in a language reference. `font-variant-ligatures: none` and `liga`/`calt` off apply to all code and mono text.

**The Extrabold Signage Rule.** Display type is always Overpass at 800. Headings do not step down to lighter weights; hierarchy comes from size.

## Layout

Chrome (header, hero, footer) sits in a 72rem container (`max-w-6xl`) with 16px, then 24px, side padding. Read pages narrow to 64rem (Getting Started, Language Reference, Components) or 56rem (Roadmap), and prose inside them caps at 42rem. Pages breathe on a 56px vertical rhythm: page padding and gaps between stations on the route.

The route is the Read-page skeleton: an ordered list indented 16px, with a 4px rail on its left edge and station markers straddling it; content starts 44px to the right of the rail. Station numbers appear only where order is meaning (Getting Started steps, Roadmap subproject numbers); elsewhere markers are empty squares. The Roadmap's rail is ink (the trunk every line runs along) with each station still filled in its own stage color, plus a small line legend above.

The home hero stacks source station, vertical rail and output station on phones and runs them horizontally from 1024px. The home main line of four stops is vertical on phones and horizontal on a 6px rail from 768px. The header shows the line-map nav on every Read page (stacked under the wordmark on phones, a 36rem line beside it from 768px); on the home page, where the main line sits right under the hero, the header shows it only below 768px. Home value statements are a two-column definition list (18rem term, prose) separated by 2px ink rules, not a card grid.

## Elevation & Depth

The system is flat. There is no shadow anywhere. Depth is drawn: 2px ink outlines separate a surface from the paper, tunnel-black blocks sit on white and paper by value alone, and state is shown by color moving into the outline or the marker fill, never by lift.

**The Drawn Not Lifted Rule.** A surface is distinguished by a 2px ink outline or by an ink fill. If something needs to feel raised, draw it; don't shadow it.

## Shapes

Right angles only; the base layer forces radius to 0 on every element. The vocabulary is squares and straight segments: square station markers (22px nav, 28px home, 32px route), square hero station squares, straight rails at 4px (route, nav) or 6px (home main line), and 2px outlines. The logo and favicon are the same language: a night-ink tile with a teal right-angle line between two square stations.

**The Right Angle Rule.** Nothing is rounded: no pills, no rounded cards, no circular markers. A marker is a square; a line is a straight segment that turns at 90 degrees.

## Components

### Buttons
Blunt mono-type blocks.
- **Shape:** square corners (0px), 2px night-ink outline.
- **Primary:** night-ink fill, white mono uppercase 14px, 12px by 20px padding.
- **Secondary:** white fill, night-ink text, same outline and padding.
- **Hover:** the two invert instantly (fill and text swap); no transition, no lift.
- **Ghost (on the teal field):** transparent, night-ink outline, mono 12px uppercase; hover fills night-ink with teletext-teal text (the Recompilar replay button).

### Panels
- **Corner Style:** 0px.
- **Background:** station white, 2px line-ink outline.
- **Header strip:** a mono 12px caption row (file path left, muted verb right) separated by a 2px ink rule.
- **Interactive panels:** on hover or focus the outline takes the line color.

### Code Blocks
Tunnel-black block, code-light mono 14px at 1.625, 20px padding, horizontal scroll, and a 4px rule on top in the page's line color. The rule is how code belongs to its stage.

### Address Cells
The teletext raise: an inline tunnel-black cell, teletext-teal mono 12px, 4px by 8px padding. Used only for in-context version, status and command chrome: version and category on component pages, `feito` / `em curso` / `por fazer` beside roadmap titles, `suko add name` on the components index, the version stamp in the footer.

### Navigation
The line map. Four stops in a four-column grid on a 4px ink line; each stop is a 22px white square with a 4px border in its stage color above an 11px mono uppercase label in secondary slate. Hover or focus fills the square with its line and darkens the label to ink. The current page (`aria-current="page"`) keeps the fill and adds a 2px ink outline offset 2px, with a medium-weight ink label. The same component serves as the in-page table of contents on the Language Reference, all stops on the Core line.

### Route Stations
Sections are stations. Headline type in ink, a 32px square marker with a 2px ink outline filled with the line color, mono 12px bold numerals where order matters (white on rose). Hollow variant: white fill, muted numeral, used for not-yet-built work on the Roadmap.

### Home Main Line
Four literal stops on a 6px ink rail: 28px white squares with a 5px stage-colored border, a title-size stop name whose underline appears in the line color on hover, and a small secondary-slate description.

### Text Links
Underlined, 2px decoration, 4px offset, colored with the line's ink variant; hover turns them ink. Stop names and component names use a transparent underline that takes the line color on hover.

### Compile Hero (signature)
The real `greeting/Hello.sk` and the compiler's real `greeting/Hello.jte` as two white panels on the teal field, joined by night-ink station squares and a rail labelled "compila para". On first view (40% visible) the rails draw in 12 steps over 420ms, the label fades in, then the template's characters settle left to right, split-flap style, onto the real output (14ms stagger, 280ms of cycling per character); the arriving station fills night-ink. A replay button reruns it. Without JavaScript or under reduced motion, the final state renders and the replay button is hidden.

## Do's and Don'ts

### Do:
- **Do** set the page's stage line on the page root (`line-cli`, `line-core`, `line-registry`, `line-site`) and let markers, rails, code rules and links inherit it.
- **Do** draw sections as stations on a rail; number markers only where order is meaning, and leave them as empty squares elsewhere.
- **Do** show real compiler input and output in place of illustrations.
- **Do** use address cells for version, status and command chrome that sits in context beside what it describes.
- **Do** put state in color: hover moves the line color into an outline, a marker fill or an underline.
- **Do** keep every animated element fully rendered without JavaScript and under reduced motion.
- **Do** escape page text in `.sk` sources: `${`, `{`, `}`, `$`, `@` (JTE directives) and `//` as HTML entities, and a bare `=` directly after a tag's `>`; keep HTML entities out of static attribute values.

### Don't:
- **Don't** round anything or add a shadow; the world is flat right angles with 2px ink outlines.
- **Don't** add a fifth line color, and don't bring back violet.
- **Don't** put an address cell, kicker or mono label above a heading as an eyebrow.
- **Don't** lay content out as a grid of cards; use stations on a route or ruled definition rows.
- **Don't** use glyph or emoji icons; markers are drawn squares and the only mark is the SVG logo.
- **Don't** use programming ligatures in code.
- **Don't** use a line color as a generic accent or warning; each hue means its stage.
