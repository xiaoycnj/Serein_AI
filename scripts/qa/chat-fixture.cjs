const { createServer } = require('node:https');
const { appendFileSync, existsSync, readFileSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

const folder = process.argv[2];
const capture = process.argv[3] === 'capture';
const cases = new Set(['First', 'Followup', 'Recover', 'Stop', 'Background', 'Dense']);
const attempts = new Map();
const log = (name) => appendFileSync(join(folder, 'requests.log'), `${name}\n`);

function event(content) {
  return `data: ${JSON.stringify({ choices: [{ index: 0, delta: { content } }] })}\n\n`;
}

async function denseDelta(response) {
  const body = event('**Strong** *emphasis* `literal` [link](https://example.com)\n\n'.repeat(18000));
  // Keep one SSE event, but avoid handing the entire record to the emulator's TLS path in one write.
  for (let offset = 0; offset < body.length && !response.destroyed; offset += 32768) {
    await new Promise((resolve, reject) => {
      response.write(body.slice(offset, offset + 32768), (error) => error ? reject(error) : resolve());
    });
    await delay(4);
  }
}

async function waitForTest(name, response) {
  const deadline = Date.now() + 45000;
  while (!response.destroyed && !existsSync(join(folder, `${name}.resume`))) {
    if (Date.now() > deadline) throw new Error('Test did not release stream');
    await delay(125);
  }
}

async function reply(request, response) {
  if (request.headers.authorization || request.headers['x-api-key']) {
    throw new Error('Unexpected fixture credentials');
  }
  if (request.method === 'GET' && request.url === '/v1/models') {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ data: [{ id: 'serein-fixture' }] }));
    return;
  }
  if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
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

  if (name === 'First' && capture) await waitForTest(name, response);
  if (response.destroyed) return;
  response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' });
  if (name === 'Dense') await denseDelta(response);
  else response.write(event(`${name} partial.\n\n`));
  if (name === 'Recover' && attempt === 1) {
    response.end();
    return;
  }
  if (name === 'Stop' || name === 'Background') await waitForTest(name, response);
  else await delay(75);
  if (response.destroyed) return;
  response.write(event(`${name} complete.`));
  response.end('data: [DONE]\n\n');
}

const server = createServer({ pfx: readFileSync(join(folder, 'fixture.p12')), passphrase: 'only-local-qa' }, (request, response) => {
  response.setHeader('Connection', 'close');
  reply(request, response).catch((error) => {
    log('INVALID');
    console.error(error.message);
    response.destroy();
  });
});
server.on('tlsClientError', (error) => console.error(`Fixture TLS: ${error.code ?? error.name}`));
server.listen(0, '127.0.0.1', function () {
  writeFileSync(join(folder, 'port.txt'), String(this.address().port));
});
