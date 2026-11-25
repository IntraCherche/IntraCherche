// Lays out a Sherlock answer written in Markdown: paragraphs, bullet and numbered
// lists, headings and **bold**. The answer is already cut into parts (text,
// quotations checked by the server, citations); this only arranges those parts,
// so a quotation's offsets and verdict are untouched. Nothing here produces HTML:
// the pane renders every piece as text, since the model writes what it read in
// the documents and must never be able to inject markup.

export type TextPart = { type: 'text'; content: string }
export type QuotePart = { type: 'quote'; content: string }

/** One piece of a line. `last` is false on a quotation's pieces before its last line. */
export type Inline<P> = { part: P; bold: boolean; last: boolean }

export type Block<P> =
  | { kind: 'p'; lines: Inline<P>[][] }
  | { kind: 'h'; inlines: Inline<P>[] }
  | { kind: 'ul' | 'ol'; items: Inline<P>[][] }

const BULLET = /^\s*[*\-+•]\s+/
const NUMBERED = /^\s*\d+[.)]\s+/
const HEADING = /^\s*#{1,6}\s+/
const CONTINUATION = /^\s{2,}\S/

/** Splits the parts on line breaks, a quotation spanning two lines becoming two pieces. */
function splitLines<P extends { type: string }>(parts: P[]): Inline<P>[][] {
  const lines: Inline<P>[][] = [[]]
  for (const part of parts) {
    if (part.type !== 'text' && part.type !== 'quote') {
      lines[lines.length - 1]!.push({ part, bold: false, last: true })
      continue
    }
    const pieces = (part as unknown as TextPart).content.split('\n')
    pieces.forEach((content, i) => {
      if (i > 0) lines.push([])
      if (content === '' && part.type === 'text') return
      lines[lines.length - 1]!.push({ part: { ...part, content }, bold: false, last: i === pieces.length - 1 })
    })
  }
  return lines
}

/** Turns `**` pairs into bold, within one line; an unpaired `**` stays as written.
 *  Quotations count too: the model sometimes bolds a word inside one. */
function applyBold<P extends { type: string }>(line: Inline<P>[]): Inline<P>[] {
  const hasText = (x: Inline<P>) => x.part.type === 'text' || x.part.type === 'quote'
  const markers = line.reduce((n, x) =>
    hasText(x) ? n + ((x.part as unknown as TextPart).content.split('**').length - 1) : n, 0)
  let usable = markers - (markers % 2)
  let bold = false
  const out: Inline<P>[] = []
  for (const x of line) {
    if (!hasText(x)) { out.push({ ...x, bold }); continue }
    const pieces: { content: string; bold: boolean }[] = []
    const segments = (x.part as unknown as TextPart).content.split('**')
    let pending = segments[0]!
    for (let i = 1; i < segments.length; i++) {
      if (usable > 0) {
        if (pending) pieces.push({ content: pending, bold })
        bold = !bold
        usable--
        pending = segments[i]!
      } else {
        pending += '**' + segments[i]
      }
    }
    if (pending) pieces.push({ content: pending, bold })
    // a quotation's mark goes after its last piece only
    pieces.forEach((piece, i) => out.push({
      part: { ...x.part, content: piece.content }, bold: piece.bold, last: x.last && i === pieces.length - 1 }))
  }
  return out
}

/** Removes a list or heading marker from the start of the line; returns what it was. */
function stripMarker<P extends { type: string }>(line: Inline<P>[]): 'ul' | 'ol' | 'h' | null {
  const first = line[0]
  if (!first || first.part.type !== 'text') return null
  const text = (first.part as unknown as TextPart).content
  for (const [re, kind] of [[BULLET, 'ul'], [NUMBERED, 'ol'], [HEADING, 'h']] as const) {
    const m = re.exec(text)
    if (!m) continue
    const rest = text.slice(m[0].length)
    if (rest) line[0] = { ...first, part: { ...first.part, content: rest } }
    else line.shift()
    return kind
  }
  return null
}

const isBlank = <P extends { type: string }>(line: Inline<P>[]) =>
  line.every(x => x.part.type === 'text' && !(x.part as unknown as TextPart).content.trim())

export function layoutAnswer<P extends { type: string }>(parts: P[]): Block<P>[] {
  const blocks: Block<P>[] = []
  let current = null as Block<P> | null
  for (const raw of splitLines(parts)) {
    // a blank line ends a paragraph, but not a list: models often space their bullets
    if (isBlank(raw)) { if (current?.kind === 'p') current = null; continue }
    const firstText = raw[0]?.part.type === 'text' ? (raw[0].part as unknown as TextPart).content : ''
    const kind = stripMarker(raw)
    const line = applyBold(raw)
    if (kind === 'h') {
      blocks.push({ kind: 'h', inlines: line })
      current = null
    } else if (kind === 'ul' || kind === 'ol') {
      if (current?.kind !== kind) { current = { kind, items: [] }; blocks.push(current) }
      current.items.push(line)
    } else if ((current?.kind === 'ul' || current?.kind === 'ol') && CONTINUATION.test(firstText)) {
      // an indented line under a list item belongs to that item
      const item = current.items[current.items.length - 1]!
      item.push({ part: { type: 'text', content: ' ' } as unknown as P, bold: false, last: true }, ...line)
    } else if (current?.kind === 'p') {
      current.lines.push(line)
    } else {
      current = { kind: 'p', lines: [line] }
      blocks.push(current)
    }
  }
  return blocks
}
