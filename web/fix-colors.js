const fs = require('fs');
const path = require('path');

const dir = 'D:/projects/Oculus/web/app';

const colorMap = {
  '#08090a': '#000000',
  '#0f1011': '#0a0a0a',
  '#111111': '#0a0a0a',
  '#161718': '#0a0a0a',
  '#222222': '#171717',
  '#23252a': '#171717',
  '#eb5757': '#ef4444',
  '#d0d6e0': '#ededed',
  '#e4f222': '#ededed',
  'bg-neutral-800': 'bg-[#171717]',
  'border-neutral-800': 'border-[#171717]',
  'border-neutral-700': 'border-[#171717]',
  'border-neutral-900': 'border-[#171717]',
  'text-neutral-500': 'text-[#a1a1aa]',
  'text-neutral-400': 'text-[#a1a1aa]',
  'text-neutral-300': 'text-[#ededed]',
  'text-neutral-200': 'text-[#ededed]',
  'text-neutral-100': 'text-[#ededed]',
  'hover:text-neutral-300': 'hover:text-[#ededed]',
  'hover:text-neutral-100': 'hover:text-[#ffffff]',
  'hover:bg-neutral-800': 'hover:bg-[#171717]',
};

function walk(directory) {
  const files = fs.readdirSync(directory);
  for (const file of files) {
    const fullPath = path.join(directory, file);
    if (fs.statSync(fullPath).isDirectory()) {
      walk(fullPath);
    } else if (fullPath.endsWith('.tsx') || fullPath.endsWith('.css')) {
      let content = fs.readFileSync(fullPath, 'utf8');
      let changed = false;
      for (const [key, value] of Object.entries(colorMap)) {
        if (content.includes(key)) {
          content = content.split(key).join(value);
          changed = true;
        }
      }
      if (changed) {
        fs.writeFileSync(fullPath, content);
        console.log(`Updated ${fullPath}`);
      }
    }
  }
}

walk(dir);
