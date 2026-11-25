const { readFileSync, writeFileSync } = require('node:fs');
const { createRequire } = require('node:module');
const { resolve, join } = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

const [emulatorDir, toolDir, discovery, command, ...args] = process.argv.slice(2);
const width = 720;
const height = 1400;
const rate = 24000;

async function main() {
  if (!emulatorDir || !toolDir || !discovery || !command) {
    throw new Error('Usage: node talkback-emulator.cjs <emulator dir> <tool dir> <discovery ini> <command> [args]');
  }
  const fields = new Map(readFileSync(discovery, 'utf8').split(/\r?\n/).filter((line) => line.includes('='))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  const port = Number(fields.get('grpc.port'));
  const token = fields.get('grpc.token');
  if (fields.get('avd.id') !== 'SereinPlay33' || !Number.isInteger(port) || port < 1024 || port > 65535 || !token) {
    throw new Error('Expected the authenticated, disposable SereinPlay33 emulator discovery file');
  }
  const requireTool = createRequire(join(resolve(toolDir), 'package.json'));
  const grpc = requireTool('@grpc/grpc-js');
  const proto = requireTool('@grpc/proto-loader');
  const { android } = grpc.loadPackageDefinition(
    proto.loadSync(join(emulatorDir, 'lib', 'emulator_controller.proto')),
  );
  const client = new android.emulation.control.EmulatorController(
    `127.0.0.1:${port}`, grpc.credentials.createInsecure(), { 'grpc.enable_http_proxy': 0 },
  );
  const auth = new grpc.Metadata();
  auth.set('authorization', `Bearer ${token}`);
  const touch = (x, y, pressure) => new Promise((accept, reject) => {
    client.sendTouch({ touches: [{ x, y, identifier: 0, pressure }] }, auth, { deadline: Date.now() + 3000 },
      (error) => error ? reject(error) : accept());
  });
  try {
    if (command === 'capture') {
      const [file, rawDuration] = args;
      const duration = Number(rawDuration);
      if (!file || !Number.isInteger(duration) || duration < 1000 || duration > 45000) {
        throw new Error('capture requires a new output .wav and duration in milliseconds (1000-45000)');
      }
      await capture(client, auth, grpc, resolve(file), duration);
    } else if (command === 'next' || command === 'previous') {
      const from = command === 'next' ? 180 : 540;
      const to = command === 'next' ? 540 : 180;
      try {
        for (let step = 0; step <= 8; step++) {
          await touch(from + (to - from) * step / 8, 588, 600);
          await delay(19);
        }
      } finally {
        await touch(to, 588, 0);
      }
    } else if (command === 'explore' || command === 'activate') {
      const x = Number(args[0]);
      const y = Number(args[1]);
      if (!Number.isInteger(x) || !Number.isInteger(y) || x < 64 || x > width - 64 || y < 48 || y > height - 60) {
        throw new Error('Expected an on-screen point inside the portrait 720x1400 test display');
      }
      const touches = command === 'activate' ? 2 : 1;
      for (let i = 0; i < touches; i++) {
        await touch(x, y, 600);
        try { await delay(touches === 1 ? 180 : 48); }
        finally { await touch(x, y, 0); }
        if (i + 1 < touches) await delay(95);
      }
    } else {
      throw new Error('Commands: next, previous, explore <x> <y>, activate <x> <y>, capture <file> <ms>');
    }
  } finally {
    client.close();
  }
}

async function capture(client, auth, grpc, file, duration) {
  const packets = [];
  const stream = client.streamAudio({ samplingRate: rate, channels: 0, format: 1 }, auth);
  const received = new Promise((accept, reject) => {
    const timer = setTimeout(() => stream.cancel(), duration);
    stream.on('data', (frame) => {
      if (frame.audio?.length) packets.push(frame.audio);
    });
    stream.once('end', () => { clearTimeout(timer); accept(); });
    stream.once('error', (error) => {
      clearTimeout(timer);
      if (error.code === grpc.status.CANCELLED) accept();
      else reject(error);
    });
  });
  await received;
  const pcm = Buffer.concat(packets);
  if (!pcm.length) throw new Error('No Android audio packets received');
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(pcm.length + 36, 4);
  header.write('WAVEfmt ', 8);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  writeFileSync(file, Buffer.concat([header, pcm]), { flag: 'wx' });
  console.log(`Saved ${(pcm.length / (2 * rate)).toFixed(2)} seconds of emulator PCM`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
