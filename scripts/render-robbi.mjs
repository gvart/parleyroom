// Renders Robbi's pixel-art frames from the portal into the PNGs embedded in emails.
// Gmail strips SVG, so emails get a nearest-neighbour upscale with a transparent background.
//
//   node scripts/render-robbi.mjs [path/to/parley-room-portal]
//
// Writes resources/email/robbi-<state>.png at SCALE px per art pixel (2x the size the
// templates display, so retina screens stay crisp).
import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const SCALE = 8
const here = dirname(fileURLToPath(import.meta.url))
const portal = resolve(process.argv[2] ?? join(here, '../../parley-room-portal'))
const { animations, palette, SIZE } = await import(
  pathToFileURL(join(portal, 'src/components/mascot/robbiSprites.ts')).href
)

// state -> frame index
const FRAMES = { idle: 0, wave: 0, celebrate: 2 }

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const out = Buffer.alloc(12 + data.length)
  out.writeUInt32BE(data.length, 0)
  out.write(type, 4, 'ascii')
  data.copy(out, 8)
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length)
  return out
}

function png(grid) {
  const px = SIZE * SCALE
  const raw = Buffer.alloc(px * (px * 4 + 1))
  for (let y = 0; y < px; y++) {
    const rowStart = y * (px * 4 + 1)
    raw[rowStart] = 0 // filter: none
    for (let x = 0; x < px; x++) {
      const ch = grid[Math.floor(y / SCALE)][Math.floor(x / SCALE)]
      if (ch === '.') continue
      const hex = palette[ch]
      const o = rowStart + 1 + x * 4
      raw[o] = parseInt(hex.slice(1, 3), 16)
      raw[o + 1] = parseInt(hex.slice(3, 5), 16)
      raw[o + 2] = parseInt(hex.slice(5, 7), 16)
      raw[o + 3] = 255
    }
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(px, 0)
  ihdr.writeUInt32BE(px, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

const outDir = join(here, '../resources/email')
mkdirSync(outDir, { recursive: true })
for (const [state, index] of Object.entries(FRAMES)) {
  const file = join(outDir, `robbi-${state}.png`)
  writeFileSync(file, png(animations[state].frames[index]))
  console.log(`wrote ${file}`)
}
