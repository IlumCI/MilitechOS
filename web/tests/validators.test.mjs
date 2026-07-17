import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateEdit } from '../js/validators.js';

test('blank content rejected', () => {
  assert.notEqual(validateEdit('any.txt', '   \n  '), null);
});

test('invalid JSON rejected', () => {
  const error = validateEdit('package.json', '{"a": }');
  assert.notEqual(error, null);
  assert.ok(error.includes('JSON'));
});

test('valid JSON passes', () => {
  assert.equal(validateEdit('package.json', '{"a": 1, "b": [true, null]}'), null);
});

test('YAML tab indentation rejected', () => {
  const error = validateEdit('ci.yml', 'jobs:\n\tbuild:\n\t\truns-on: ubuntu\n');
  assert.notEqual(error, null);
  assert.ok(error.includes('tab'));
});

test('YAML with spaces passes', () => {
  assert.equal(validateEdit('ci.yaml', 'jobs:\n  build:\n    runs-on: ubuntu\n'), null);
});

test('ordinary text file passes when non-blank', () => {
  assert.equal(validateEdit('README.md', '# Title\ncontent\n'), null);
});
