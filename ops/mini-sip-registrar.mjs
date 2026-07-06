import dgram from 'node:dgram';
import crypto from 'node:crypto';

// Host-side SIP REGISTER validator for the GXP1630 MVP.
// It deliberately implements only Digest-authenticated REGISTER, not full PBX behavior.
const bind = process.argv[2] ?? '0.0.0.0';
const port = Number(process.argv[3] ?? 5060);
const rtpPort = Number(process.argv[4] ?? 40000);
const realm = 'phone-agent.local';
const user = '1001';
const password = '1001';
const nonce = crypto.randomBytes(16).toString('hex');

const socket = dgram.createSocket('udp4');
const rtpSocket = dgram.createSocket('udp4');
const activeCalls = new Map();
let inboundRtpPackets = 0;

function md5(value) {
  return crypto.createHash('md5').update(value).digest('hex');
}

function parseMessage(raw) {
  const lines = raw.replace(/\r\n/g, '\n').split('\n');
  const startLine = lines[0] ?? '';
  const headers = new Map();
  for (const line of lines.slice(1)) {
    if (!line) break;
    const index = line.indexOf(':');
    if (index <= 0) continue;
    const key = line.slice(0, index).trim().toLowerCase();
    const value = line.slice(index + 1).trim();
    const list = headers.get(key) ?? [];
    list.push(value);
    headers.set(key, list);
  }
  return {
    startLine,
    method: startLine.split(/\s+/, 1)[0],
    uri: startLine.split(/\s+/)[1],
    raw,
    header(name) {
      return headers.get(name.toLowerCase())?.[0];
    },
    headers(name) {
      return headers.get(name.toLowerCase()) ?? [];
    },
  };
}

function bodyOf(raw) {
  const index = raw.indexOf('\r\n\r\n');
  if (index >= 0) return raw.slice(index + 4);
  const lfIndex = raw.indexOf('\n\n');
  return lfIndex >= 0 ? raw.slice(lfIndex + 2) : '';
}

function parseTargetExtension(uri) {
  const match = uri?.match(/^sip:([^@;>\s]+)/i);
  return match?.[1];
}

function parseSdpConnection(sdp, fallbackAddress) {
  const connection = sdp.match(/^c=IN IP4 ([^\r\n]+)/m)?.[1] ?? fallbackAddress;
  const audioPort = Number(sdp.match(/^m=audio (\d+) /m)?.[1]);
  const codecs = sdp.match(/^m=audio \d+ [^ ]+ (.+)$/m)?.[1]?.trim().split(/\s+/) ?? [];
  return { address: connection, port: audioPort, codecs };
}

function parseDigest(header) {
  if (!header?.toLowerCase().startsWith('digest ')) return {};
  const body = header.slice('Digest '.length);
  const parts = [];
  let current = '';
  let quoted = false;
  for (const char of body) {
    if (char === '"') quoted = !quoted;
    if (char === ',' && !quoted) {
      parts.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  if (current.trim()) parts.push(current.trim());

  return Object.fromEntries(parts.flatMap((part) => {
    const index = part.indexOf('=');
    if (index <= 0) return [];
    const key = part.slice(0, index).trim();
    let value = part.slice(index + 1).trim();
    if (value.startsWith('"') && value.endsWith('"')) value = value.slice(1, -1);
    return [[key, value]];
  }));
}

function verifyDigest(auth, method) {
  if (auth.username !== user || auth.realm !== realm || auth.nonce !== nonce || !auth.uri || !auth.response) {
    return false;
  }
  const ha1 = md5(`${auth.username}:${auth.realm}:${password}`);
  const ha2 = md5(`${method}:${auth.uri}`);
  const expected = auth.qop
    ? md5(`${ha1}:${auth.nonce}:${auth.nc}:${auth.cnonce}:${auth.qop}:${ha2}`)
    : md5(`${ha1}:${auth.nonce}:${ha2}`);
  return expected.toLowerCase() === auth.response.toLowerCase();
}

function responseBase(status, request, options = {}) {
  const lines = [`SIP/2.0 ${status}`];
  for (const via of request.headers('via')) lines.push(`Via: ${via}`);
  for (const name of ['from', 'to', 'call-id', 'cseq']) {
    const value = request.header(name);
    if (!value) continue;
    if (name === 'to' && options.toTag && !/;tag=/i.test(value)) {
      lines.push(`To: ${value};tag=${options.toTag}`);
    } else {
      lines.push(`${name.replace(/\b\w/g, (c) => c.toUpperCase())}: ${value}`);
    }
  }
  lines.push('Server: phone-agent-mini-registrar');
  return lines;
}

function sendResponse(status, request, rinfo, extraHeaders = []) {
  const payload = [
    ...responseBase(status, request),
    ...extraHeaders,
    'Content-Length: 0',
    '',
    '',
  ].join('\r\n');
  socket.send(Buffer.from(payload), rinfo.port, rinfo.address);
}

function sendInviteResponse(status, request, rinfo, extraHeaders = [], body = '', options = {}) {
  const headers = [...responseBase(status, request, options), ...extraHeaders];
  if (body) {
    headers.push('Content-Type: application/sdp');
  }
  headers.push(`Content-Length: ${Buffer.byteLength(body)}`);
  const payload = [...headers, '', body].join('\r\n');
  socket.send(Buffer.from(payload), rinfo.port, rinfo.address);
}

function localSdp(localAddress) {
  return [
    'v=0',
    `o=phone-agent 1 1 IN IP4 ${localAddress}`,
    's=phone-agent-tone-test',
    `c=IN IP4 ${localAddress}`,
    't=0 0',
    `m=audio ${rtpPort} RTP/AVP 0`,
    'a=rtpmap:0 PCMU/8000',
    'a=sendrecv',
    'a=ptime:20',
    '',
  ].join('\r\n');
}

function pcmuSample(sample) {
  // ITU G.711 u-law companding for one 16-bit signed PCM sample.
  const bias = 0x84;
  const clip = 32635;
  let pcm = Math.max(-clip, Math.min(clip, sample));
  let sign = (pcm >> 8) & 0x80;
  if (sign !== 0) pcm = -pcm;
  pcm += bias;
  let exponent = 7;
  for (let mask = 0x4000; (pcm & mask) === 0 && exponent > 0; mask >>= 1) {
    exponent -= 1;
  }
  const mantissa = (pcm >> (exponent + 3)) & 0x0f;
  return (~(sign | (exponent << 4) | mantissa)) & 0xff;
}

function tonePayload(sequence) {
  const payload = Buffer.alloc(160);
  const cyclePosition = sequence % 70;
  const audible = cyclePosition < 50;
  for (let i = 0; i < payload.length; i++) {
    if (!audible) {
      payload[i] = 0xff;
      continue;
    }
    // Loud 500 Hz square wave in PCMU. This is intentionally harsh for media-path validation.
    const phase = Math.floor((sequence * 160 + i) / 8) % 2;
    payload[i] = phase === 0 ? pcmuSample(24000) : pcmuSample(-24000);
  }
  return payload;
}

function startTone(callId, target) {
  const ssrc = crypto.randomInt(1, 0x7fffffff);
  let sequence = 0;
  const timer = setInterval(() => {
    const payload = tonePayload(sequence);
    const packet = Buffer.alloc(12 + payload.length);
    packet[0] = 0x80;
    packet[1] = sequence === 0 ? 0x80 : 0x00;
    packet.writeUInt16BE(sequence & 0xffff, 2);
    packet.writeUInt32BE(sequence * 160, 4);
    packet.writeUInt32BE(ssrc, 8);
    payload.copy(packet, 12);
    rtpSocket.send(packet, target.port, target.address);
    if (sequence % 50 === 0) {
      console.log(`${new Date().toISOString()} RTP sent call=${callId} seq=${sequence} target=${target.address}:${target.port}`);
    }
    sequence += 1;
  }, 20);
  activeCalls.set(callId, timer);
  setTimeout(() => stopTone(callId), 12000);
}

function stopTone(callId) {
  const timer = activeCalls.get(callId);
  if (timer) {
    clearInterval(timer);
    activeCalls.delete(callId);
  }
}

socket.on('message', (buffer, rinfo) => {
  const raw = buffer.toString('utf8');
  const request = parseMessage(raw);
  if (request.method !== 'REGISTER') {
    if (request.method === 'INVITE') {
      const extension = parseTargetExtension(request.uri);
      const callId = request.header('call-id');
      const sdp = bodyOf(request.raw);
      const target = parseSdpConnection(sdp, rinfo.address);
      console.log(`${new Date().toISOString()} INVITE ${extension} from ${rinfo.address}:${rinfo.port}, rtp=${target.address}:${target.port}, codecs=${target.codecs.join(',')}`);
      console.log(`--- remote SDP ---\n${sdp.trim()}\n--- end remote SDP ---`);
      sendInviteResponse('100 Trying', request, rinfo);
      sendInviteResponse('180 Ringing', request, rinfo);
      if (!target.port || extension !== '701') {
        sendInviteResponse('404 Not Found', request, rinfo);
        return;
      }
      sendInviteResponse(
        '200 OK',
        request,
        rinfo,
        ['Contact: <sip:phone-agent@192.168.10.1:5060>'],
        localSdp('192.168.10.1'),
        { toTag: crypto.randomBytes(6).toString('hex') },
      );
      startTone(callId, target);
      return;
    }
    if (request.method === 'BYE' || request.method === 'CANCEL') {
      stopTone(request.header('call-id'));
      sendInviteResponse('200 OK', request, rinfo);
      console.log(`${new Date().toISOString()} ${request.method} accepted for call=${request.header('call-id')}`);
      return;
    }
    if (request.method === 'ACK') {
      console.log(`${new Date().toISOString()} ACK ${request.header('call-id')}`);
      return;
    }
    console.log(`${new Date().toISOString()} ignored ${request.startLine} from ${rinfo.address}:${rinfo.port}`);
    return;
  }

  const auth = parseDigest(request.header('authorization'));
  if (!verifyDigest(auth, request.method)) {
    console.log(`${new Date().toISOString()} REGISTER challenge -> ${rinfo.address}:${rinfo.port}`);
    sendResponse('401 Unauthorized', request, rinfo, [
      `WWW-Authenticate: Digest realm="${realm}", nonce="${nonce}", algorithm=MD5, qop="auth"`,
    ]);
    return;
  }

  const contact = request.header('contact');
  const expires = request.header('expires') ?? '3600';
  console.log(`${new Date().toISOString()} REGISTER accepted contact=${contact}`);
  sendResponse('200 OK', request, rinfo, [
    contact ? `Contact: ${contact};expires=${expires}` : undefined,
    `Expires: ${expires}`,
  ].filter(Boolean));
});

socket.bind(port, bind, () => {
  console.log(`Mini SIP registrar listening on ${bind}:${port}, user=${user}, password=${password}`);
});

rtpSocket.bind(rtpPort, bind, () => {
  const address = rtpSocket.address();
  console.log(`RTP tone sender listening on ${address.address}:${address.port}`);
});

rtpSocket.on('message', (packet, rinfo) => {
  inboundRtpPackets += 1;
  if (inboundRtpPackets <= 5 || inboundRtpPackets % 50 === 0) {
    console.log(`${new Date().toISOString()} RTP received count=${inboundRtpPackets} from ${rinfo.address}:${rinfo.port} bytes=${packet.length}`);
  }
});
