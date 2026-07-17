import { test } from 'node:test';
import assert from 'node:assert/strict';
import { slugify, normalizeLabel, rankPaths, SKIP_LABELS, b64DecodeUtf8, occurrencesOf } from '../js/utils.js';

test('slugify basic', () => {
  assert.equal(slugify('Fix the docs'), 'fix-the-docs');
});

test('slugify strips special chars and collapses dashes', () => {
  assert.equal(slugify('Fix!! bug -- in <parser>'), 'fix-bug-in-parser');
});

test('slugify caps length at 40', () => {
  assert.ok(slugify('a'.repeat(100)).length <= 40);
});

test('slugify blank falls back', () => {
  assert.equal(slugify('!!!'), 'change');
});

test('normalizeLabel handles spaces and underscores', () => {
  assert.equal(normalizeLabel('On Hold'), 'on-hold');
  assert.equal(normalizeLabel('needs_discussion'), 'needs-discussion');
  assert.equal(normalizeLabel('WONTFIX'), 'wontfix');
});

test('skip labels catch common disqualifiers', () => {
  assert.ok(SKIP_LABELS.has(normalizeLabel('wontfix')));
  assert.ok(SKIP_LABELS.has(normalizeLabel('Question')));
});

test('rankPaths puts filename matches first', () => {
  const paths = ['zzz/aaa.py', 'docs/config.md', 'src/config_loader.py'];
  const ranked = rankPaths(paths, 'Fix config loader crash', '', 10);
  assert.equal(ranked[0], 'src/config_loader.py');
});

test('rankPaths respects cap', () => {
  const paths = Array.from({ length: 50 }, (_, i) => `file${i}.py`);
  assert.equal(rankPaths(paths, 'title', 'body', 5).length, 5);
});

test('rankPaths with no usable tokens still caps', () => {
  const paths = Array.from({ length: 10 }, (_, i) => `f${i}.py`);
  assert.equal(rankPaths(paths, 'a b', '', 3).length, 3);
});

test('b64DecodeUtf8 handles embedded line breaks and utf-8', () => {
  const text = '# Héllo\nwörld\n';
  const raw = Buffer.from(text, 'utf-8').toString('base64');
  const withBreak = raw.slice(0, 8) + '\n' + raw.slice(8);
  assert.equal(b64DecodeUtf8(withBreak), text);
});

test('occurrencesOf counts non-overlapping matches', () => {
  assert.equal(occurrencesOf('aa aa', 'aa'), 2);
  assert.equal(occurrencesOf('abc', 'zz'), 0);
  assert.equal(occurrencesOf('abc', ''), 0);
});
