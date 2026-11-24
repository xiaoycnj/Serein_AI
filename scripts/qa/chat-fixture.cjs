const { createServer } = require('node:https');
const { appendFileSync, existsSync, readFileSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

const folder = process.argv[2];
const cases = new Set(['First', 'Followup', 'Recover', 'Stop', 'Background', 'Dense']);
const attempts = new Map();
const log = (name) => appendFileSync(join(folder, 'requests.log'), `${name}\n`);

function delta(response, content) {
  response.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content } }] })}\n\n`);
}

async function waitForTest(name, response) {
  const deadline = Date.now() + 45000;
  while (!response.destroyed && !existsSync(join(folder, `${name}.resume`))) {
    if (Date.now() > deadline) throw new Error('Test did not release stream');
    await delay(125);
  }
}

async function reply(request, response) {
  if (request.method !== 'POST' || request.url !== '/v1/chat/completions' ||
      request.headers.authorization || request.headers['x-api-key']) {
    throw new Error('Unexpected fixture request');
  }
  let input = '';
  for await (const chunk of request) {
    input += chunk;
    if (input.length > 16384) throw new Error('Fixture request exceeds test budget');
  }
  const body = JSON.parse(input);
  const turn = body.messages.at(-1);
  const name = turn.content;
  if (body.model !== 'serein-fixture' || body.stream !== true || turn.role !== 'user' || !cases.has(name)) {
    throw new Error('Invalid fixture payload');
  }
  if (name === 'Followup' && !body.messages.some((m) => m.role === 'assistant' && m.content.includes('First complete.'))) {
    throw new Error('Follow-up lost earlier reply');
  }
  const attempt = (attempts.get(name) || 0) + 1;
  attempts.set(name, attempt);
  log(name);
  if (name === 'Recover' && attempt === 2) {
    response.writeHead(503).end();
    return;
  }

  response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' });
  delta(response, name === 'Dense' ? '**Strong** *emphasis* `literal` [link](https://example.com)\n\n'.repeat(18000)
    : `${name} partial.\n\n`);
  if (name === 'Recover' && attempt === 1) {
    response.end();
    return;
  }
  if (name === 'Stop' || name === 'Background') await waitForTest(name, response);
  else await delay(75);
  if (response.destroyed) return;
  delta(response, `${name} complete.`);
  response.end('data: [DONE]\n\n');
}

createServer({ pfx: readFileSync(join(folder, 'fixture.p12')), passphrase: 'only-local-qa' }, (request, response) => {
  reply(request, response).catch(() => {
    log('INVALID');
    response.destroy();
  });
}).listen(0, '127.0.0.1', function () {
  writeFileSync(join(folder, 'port.txt'), String(this.address().port));
});
