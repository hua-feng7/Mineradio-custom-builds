// Rename macOS build artifacts: arm64 -> apple, x64 -> intel.
const fs = require('fs');
const path = require('path');

const distDir = path.join(__dirname, '..', 'dist');
if (!fs.existsSync(distDir)) {
  console.log('  - dist/ not found, nothing to rename');
  process.exit(0);
}

const mapping = {
  arm64: 'apple',
  x64: 'intel'
};

const files = fs.readdirSync(distDir).filter((file) => /\.(dmg|zip|blockmap)$/i.test(file));
let renamed = 0;

files.forEach((file) => {
  let nextName = file;
  Object.entries(mapping).forEach(([from, to]) => {
    if (nextName.includes(from)) nextName = nextName.replace(from, to);
  });
  if (nextName === file) return;
  fs.renameSync(path.join(distDir, file), path.join(distDir, nextName));
  console.log('  - ' + file + ' -> ' + nextName);
  renamed += 1;
});

if (!renamed) console.log('  - no macOS artifacts to rename');
