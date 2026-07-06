import fs from 'node:fs';
import path from 'node:path';

const sourcePath = path.resolve('src/main/resources/static/console/console.js');
const source = fs.readFileSync(sourcePath, 'utf8');
const coverageDir = path.resolve('build/js-coverage');
const files = fs.readdirSync(coverageDir)
  .filter(name => name.endsWith('.json'))
  .map(name => path.join(coverageDir, name));

function lineOffset(lineNumber) {
  const lines = source.split('\n');
  return lines.slice(0, lineNumber - 1).join('\n').length + (lineNumber > 1 ? 1 : 0);
}

function lineNumberContaining(needle) {
  const index = source.split('\n').findIndex(line => line.includes(needle));
  if (index < 0) {
    throw new Error(`Could not find line containing ${needle}`);
  }
  return index + 1;
}

function covered(offset, functions) {
  return functions.some(fn => fn.ranges.some(range => range.count > 0 && range.startOffset <= offset && offset < range.endOffset));
}

const targets = [
  "async function ringPhone()",
  "fetch('/api/ring-phone'",
  "function renderRingPhone()",
  "ringPhoneButton.textContent = state.ringingPhone ? t('ringPhoneLoading') : t('ringPhoneButton')",
  "function setLocale(locale)",
  "function statusLabel(status)",
  "function bridgeText(session)",
  "function sessionBridgeActions(session)",
  "if (bridge && bridge.cancellable)",
  "if (bridge && (bridge.renotifyAllowed || RENOTIFY_STATUSES.has(bridge.status)))",
  "function bridgeActionFailureMessage(action)"
];

const functions = [];
for (const file of files) {
  const payload = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const entry of payload.result || []) {
    if (entry.url.endsWith('src/main/resources/static/console/console.js')) {
      functions.push(...entry.functions);
    }
  }
}

if (!functions.length) {
  throw new Error('No V8 coverage entry found for console.js');
}

const missed = targets.filter(needle => !covered(lineOffset(lineNumberContaining(needle)), functions));
if (missed.length) {
  throw new Error(`Missing console.js ring coverage for: ${missed.join(', ')}`);
}

console.log(`Verified console.js productization changed-line coverage for ${targets.length}/${targets.length} targets.`);
