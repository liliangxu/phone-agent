import fs from 'node:fs';
import {execFileSync} from 'node:child_process';

const reportPath = 'build/reports/jacoco/test/jacocoTestReport.xml';

// Keep exclusions explicit and narrow: only non-business entry points that cannot
// produce meaningful JaCoCo line evidence belong here.
const excludedProductionFiles = new Set([]);
const productionFiles = discoverChangedProductionJavaFiles();
const untrackedProductionFiles = discoverUntrackedProductionJavaFiles();
const sourceToPath = new Map(productionFiles.map((file) => [
  file.slice('src/main/java/'.length),
  file
]));

const changedLines = mergeChangedLines(parseChangedLines(execFileSync('git', [
  'diff',
  '--unified=0',
  'HEAD',
  '--',
  ...productionFiles
], {encoding: 'utf8'})), linesForUntrackedFiles(untrackedProductionFiles));
const coverage = parseJacoco(fs.readFileSync(reportPath, 'utf8'));

let executableLines = 0;
let coveredLines = 0;
let branchTotal = 0;
let branchCovered = 0;
const missedLines = [];
const missedBranches = [];

for (const [file, lines] of changedLines) {
  for (const line of lines) {
    const counters = coverage.get(`${file}:${line}`);
    if (!counters) {
      continue;
    }
    executableLines++;
    if (counters.ci > 0) {
      coveredLines++;
    } else {
      missedLines.push(`${file}:${line}`);
    }
    const lineBranches = counters.mb + counters.cb;
    if (lineBranches > 0) {
      branchTotal += lineBranches;
      branchCovered += counters.cb;
      if (counters.mb > 0) {
        missedBranches.push(`${file}:${line} (${counters.cb}/${lineBranches})`);
      }
    }
  }
}

if (!executableLines) {
  throw new Error('No changed executable Java production lines found in JaCoCo report.');
}

const lineRate = coveredLines / executableLines;
const branchRate = branchTotal === 0 ? 1 : branchCovered / branchTotal;
const linePct = (lineRate * 100).toFixed(2);
const branchPct = (branchRate * 100).toFixed(2);

if (lineRate < 0.90 || branchRate < 0.85) {
  throw new Error([
    `Java diff coverage below threshold: lines ${linePct}% (${coveredLines}/${executableLines}), branches ${branchPct}% (${branchCovered}/${branchTotal}).`,
    missedLines.length ? `Missed lines: ${missedLines.join(', ')}` : '',
    missedBranches.length ? `Missed branches: ${missedBranches.join(', ')}` : ''
  ].filter(Boolean).join('\n'));
}

console.log(`Verified Java diff coverage: lines ${linePct}% (${coveredLines}/${executableLines}), branches ${branchPct}% (${branchCovered}/${branchTotal}).`);

function discoverChangedProductionJavaFiles() {
  const files = [
    ...discoverTrackedChangedProductionJavaFiles(),
    ...discoverUntrackedProductionJavaFiles()
  ];
  return [...new Set(files)].sort();
}

function discoverTrackedChangedProductionJavaFiles() {
  const output = execFileSync('git', [
    'diff',
    '--name-only',
    '--diff-filter=ACMRTUXB',
    'HEAD',
    '--',
    'src/main/java'
  ], {encoding: 'utf8'});
  const files = output
    .split('\n')
    .map((line) => line.trim())
    .filter((file) => file.endsWith('.java'))
    .filter((file) => fs.existsSync(file))
    .filter((file) => !excludedProductionFiles.has(file));
  return [...new Set(files)].sort();
}

function discoverUntrackedProductionJavaFiles() {
  const output = execFileSync('git', [
    'ls-files',
    '--others',
    '--exclude-standard',
    '--',
    'src/main/java'
  ], {encoding: 'utf8'});
  const files = output
    .split('\n')
    .map((line) => line.trim())
    .filter((file) => file.endsWith('.java'))
    .filter((file) => fs.existsSync(file))
    .filter((file) => !excludedProductionFiles.has(file));
  return [...new Set(files)].sort();
}

function linesForUntrackedFiles(files) {
  return new Map(files.map((file) => {
    const lineCount = fs.readFileSync(file, 'utf8').split('\n').length;
    return [file, Array.from({length: lineCount}, (_, index) => index + 1)];
  }));
}

function mergeChangedLines(...maps) {
  const merged = new Map();
  for (const map of maps) {
    for (const [file, lines] of map) {
      const existing = merged.get(file) || new Set();
      for (const line of lines) {
        existing.add(line);
      }
      merged.set(file, existing);
    }
  }
  return new Map([...merged].map(([file, lines]) => [file, [...lines].sort((a, b) => a - b)]));
}

function parseChangedLines(diff) {
  const result = new Map();
  let currentFile = null;
  let nextLine = 0;
  for (const rawLine of diff.split('\n')) {
    if (rawLine.startsWith('+++ b/')) {
      const file = rawLine.slice('+++ b/'.length);
      currentFile = productionFiles.includes(file) ? file : null;
      if (currentFile && !result.has(currentFile)) {
        result.set(currentFile, new Set());
      }
      continue;
    }
    const hunk = rawLine.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/);
    if (hunk) {
      nextLine = Number(hunk[1]);
      continue;
    }
    if (!currentFile || rawLine.startsWith('---') || rawLine.startsWith('+++') || rawLine.startsWith('@@')) {
      continue;
    }
    if (rawLine.startsWith('+') && !rawLine.startsWith('+++')) {
      result.get(currentFile).add(nextLine);
      nextLine++;
      continue;
    }
  }
  return new Map([...result].map(([file, lines]) => [file, [...lines].sort((a, b) => a - b)]));
}

function parseJacoco(xml) {
  const result = new Map();
  const packageRegex = /<package name="([^"]+)">([\s\S]*?)<\/package>/g;
  let packageMatch;
  while ((packageMatch = packageRegex.exec(xml))) {
    const packageName = packageMatch[1];
    const sourceRegex = /<sourcefile name="([^"]+)">([\s\S]*?)<\/sourcefile>/g;
    let sourceMatch;
    while ((sourceMatch = sourceRegex.exec(packageMatch[2]))) {
      const sourceKey = `${packageName}/${sourceMatch[1]}`;
      const file = sourceToPath.get(sourceKey);
      if (!file) {
        continue;
      }
      const lineRegex = /<line nr="(\d+)" mi="(\d+)" ci="(\d+)" mb="(\d+)" cb="(\d+)"\/>/g;
      let lineMatch;
      while ((lineMatch = lineRegex.exec(sourceMatch[2]))) {
        result.set(`${file}:${lineMatch[1]}`, {
          mi: Number(lineMatch[2]),
          ci: Number(lineMatch[3]),
          mb: Number(lineMatch[4]),
          cb: Number(lineMatch[5])
        });
      }
    }
  }
  return result;
}
