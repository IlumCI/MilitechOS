import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SurgicalAgent, extractJsonObject } from '../js/agent.js';
import { OllamaClient } from '../js/ollama.js';

/** An Ollama client whose next chat responses are queued contents. */
function agentWithReplies(...contents) {
  const queue = [...contents];
  const fetchFn = async () => {
    const content = queue.shift();
    if (content === undefined) throw new Error('no reply queued — agent called the model unexpectedly');
    return new Response(JSON.stringify({ message: { role: 'assistant', content }, done: true }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    });
  };
  return new SurgicalAgent(new OllamaClient('http://fake', 'test-model', '', { fetchFn }));
}

const planContent = (over = {}) => JSON.stringify({
  abstain: false,
  reason: '',
  commit_message: 'Fix docs',
  edits: [{ path: 'README.md', is_new: false, old_string: 'world', new_string: 'universe' }],
  ...over,
});

const files = { 'README.md': '# Hello\nworld\n' };

// ---- plan ----

test('applies valid edit', async () => {
  const agent = agentWithReplies(planContent());
  const outcome = await agent.plan('t', 'b', files, false);
  assert.equal(outcome.kind, 'ready');
  assert.equal(outcome.finalContents['README.md'], '# Hello\nuniverse\n');
  assert.equal(outcome.commitMessage, 'Fix docs');
  assert.equal(outcome.edits.length, 1);
});

test('parses markdown-fenced JSON', async () => {
  const agent = agentWithReplies('```json\n' + planContent() + '\n```');
  assert.equal((await agent.plan('t', 'b', files, false)).kind, 'ready');
});

test('rejects missing old_string target', async () => {
  const agent = agentWithReplies(planContent({
    edits: [{ path: 'README.md', is_new: false, old_string: 'does-not-exist', new_string: 'x' }],
  }));
  const outcome = await agent.plan('t', 'b', files, false);
  assert.equal(outcome.kind, 'rejected');
  assert.ok(outcome.reason.includes('not found'));
});

test('rejects ambiguous old_string', async () => {
  const agent = agentWithReplies(planContent({
    edits: [{ path: 'README.md', is_new: false, old_string: 'aa', new_string: 'x' }],
  }));
  const outcome = await agent.plan('t', 'b', { 'README.md': 'aa aa' }, false);
  assert.equal(outcome.kind, 'rejected');
  assert.ok(outcome.reason.includes('ambiguous'));
});

test('abstains when model abstains', async () => {
  const agent = agentWithReplies(planContent({ abstain: true, reason: 'unsure' }));
  const outcome = await agent.plan('t', 'b', files, false);
  assert.equal(outcome.kind, 'abstained');
  assert.equal(outcome.reason, 'unsure');
});

test('rejects new file that already exists in fetched set', async () => {
  const agent = agentWithReplies(planContent({
    edits: [{ path: 'README.md', is_new: true, new_string: 'overwrite!' }],
  }));
  assert.equal((await agent.plan('t', 'b', files, false)).kind, 'rejected');
});

test('rejects too many files', async () => {
  const many = Object.fromEntries([1, 2, 3, 4].map((i) => [`f${i}.md`, `content ${i}`]));
  const agent = agentWithReplies(planContent({
    edits: [1, 2, 3, 4].map((i) => ({
      path: `f${i}.md`, is_new: false, old_string: `content ${i}`, new_string: `changed ${i}`,
    })),
  }));
  const outcome = await agent.plan('t', 'b', many, false);
  assert.equal(outcome.kind, 'rejected');
  assert.ok(outcome.reason.includes('cap'));
});

test('danger mode tightens file cap to 2', async () => {
  const three = Object.fromEntries([1, 2, 3].map((i) => [`f${i}.md`, `content ${i}`]));
  const agent = agentWithReplies(planContent({
    edits: [1, 2, 3].map((i) => ({
      path: `f${i}.md`, is_new: false, old_string: `content ${i}`, new_string: `changed ${i}`,
    })),
  }));
  assert.equal((await agent.plan('t', 'b', three, true)).kind, 'rejected');
});

test('rejects oversize change', async () => {
  const agent = agentWithReplies(planContent({
    edits: [{ path: 'README.md', is_new: false, old_string: 'world', new_string: 'x'.repeat(9001) }],
  }));
  const outcome = await agent.plan('t', 'b', files, false);
  assert.equal(outcome.kind, 'rejected');
  assert.ok(outcome.reason.includes('large'));
});

test('rejects unparseable model output', async () => {
  const agent = agentWithReplies('I think you should probably change the file somehow');
  assert.equal((await agent.plan('t', 'b', files, false)).kind, 'rejected');
});

// ---- locate ----

test('locate filters hallucinated paths', async () => {
  const agent = agentWithReplies('{"files":["a.py","ghost.py"],"reason":"r"}');
  assert.deepEqual(await agent.locate('t', 'b', ['a.py', 'b.py']), ['a.py']);
});

test('locate with empty candidates short-circuits without a model call', async () => {
  const agent = agentWithReplies(); // queue empty — any call would throw
  assert.deepEqual(await agent.locate('t', 'b', []), []);
});

// ---- critique ----

const readyOutcome = {
  edits: [{ path: 'README.md', oldString: 'world', newString: 'universe', isNew: false }],
  finalContents: { 'README.md': '# Hello\nuniverse\n' },
  commitMessage: 'Fix docs',
  notes: '',
};

test('critique approves', async () => {
  const agent = agentWithReplies('{"approve":true,"reason":"minimal and complete"}');
  const { approved, reason } = await agent.critique('t', 'b', readyOutcome, false);
  assert.equal(approved, true);
  assert.equal(reason, 'minimal and complete');
});

test('critique rejects', async () => {
  const agent = agentWithReplies('{"approve":false,"reason":"touches unrelated code"}');
  const { approved, reason } = await agent.critique('t', 'b', readyOutcome, false);
  assert.equal(approved, false);
  assert.equal(reason, 'touches unrelated code');
});

test('critique fails closed on garbage output', async () => {
  const agent = agentWithReplies('looks good to me!');
  const { approved } = await agent.critique('t', 'b', readyOutcome, false);
  assert.equal(approved, false);
});

// ---- extractJsonObject ----

test('extractJsonObject handles fences, prose wrapping, and garbage', () => {
  assert.deepEqual(extractJsonObject('```json\n{"a":1}\n```'), { a: 1 });
  assert.deepEqual(extractJsonObject('Sure! Here it is: {"a":1} hope that helps'), { a: 1 });
  assert.equal(extractJsonObject('no json here'), null);
  assert.equal(extractJsonObject('{broken'), null);
});
