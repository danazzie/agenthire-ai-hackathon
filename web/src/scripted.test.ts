import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { SCRIPTED_PASS_TEXT } from './scripted.ts'

test('the scripted pass delivery is the PROTOTYPES pass blurb, word for word', () => {
  const prototypes = readFileSync(new URL('../../PROTOTYPES.md', import.meta.url), 'utf8')
  const blurb = /\| Pass \| \*(.+?)\* \(50 words\) \|/.exec(prototypes)?.[1]
  assert.equal(SCRIPTED_PASS_TEXT, blurb)
  assert.equal(SCRIPTED_PASS_TEXT.split(/\s+/).length, 50)
})
