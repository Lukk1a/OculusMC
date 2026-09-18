import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const walkSync = (dir, filelist = []) => {
  const files = fs.readdirSync(dir);
  for (const file of files) {
    const filepath = path.join(dir, file);
    const stat = fs.statSync(filepath);
    if (stat.isDirectory()) {
      filelist = walkSync(filepath, filelist);
    } else if (filepath.endsWith('.tsx') || filepath.endsWith('.ts')) {
      filelist.push(filepath);
    }
  }
  return filelist;
};

const replaceInFile = (filepath) => {
  let content = fs.readFileSync(filepath, 'utf8');
  let original = content;

  content = content.replace(/bg-gray-900/g, 'bg-[var(--color-void)]');
  content = content.replace(/bg-gray-800/g, 'bg-[var(--color-carbon)]');
  content = content.replace(/bg-gray-700/g, 'bg-[var(--color-obsidian)]');
  content = content.replace(/bg-gray-600/g, 'bg-[var(--color-graphite)]');

  content = content.replace(/text-white/g, 'text-[var(--color-paper)]');
  content = content.replace(/text-gray-300/g, 'text-[var(--color-mist)]');
  content = content.replace(/text-gray-400/g, 'text-[var(--color-fog)]');
  content = content.replace(/text-gray-500/g, 'text-[var(--color-ash)]');
  content = content.replace(/text-gray-600/g, 'text-[var(--color-smoke)]');

  content = content.replace(/text-blue-400/g, 'text-[var(--color-signal-teal)]');
  content = content.replace(/text-blue-300/g, 'text-[var(--color-signal-teal)]');
  content = content.replace(/text-red-400/g, 'text-[var(--color-coral-red)]');
  content = content.replace(/text-red-300/g, 'text-[var(--color-coral-red)]');
  
  content = content.replace(/border-gray-700/g, 'border-[var(--color-graphite)]');
  content = content.replace(/border-gray-800/g, 'border-[var(--color-obsidian)]');
  content = content.replace(/border-gray-900/g, 'border-[var(--color-carbon)]');
  
  content = content.replace(/focus:border-blue-500/g, 'focus:border-[var(--color-smoke)]');
  content = content.replace(/focus-visible:ring-blue-500/g, 'focus-visible:ring-[var(--color-acid-lime)]');
  
  content = content.replace(/bg-blue-600\/20/g, 'bg-[var(--color-signal-teal)]/20');
  content = content.replace(/bg-blue-600/g, 'bg-[var(--color-signal-teal)]');
  content = content.replace(/hover:bg-blue-700/g, 'hover:bg-[var(--color-signal-teal)]/80');
  
  content = content.replace(/bg-red-600\/20/g, 'bg-[var(--color-coral-red)]/20');
  content = content.replace(/bg-red-600/g, 'bg-[var(--color-coral-red)]');
  content = content.replace(/hover:bg-red-700/g, 'hover:bg-[var(--color-coral-red)]/80');

  content = content.replace(/bg-green-600/g, 'bg-[var(--color-pulse-green)]');
  content = content.replace(/hover:bg-green-700/g, 'hover:bg-[var(--color-pulse-green)]/80');

  content = content.replace(/bg-purple-600/g, 'bg-[var(--color-lavender)]');
  content = content.replace(/hover:bg-purple-700/g, 'hover:bg-[var(--color-lavender)]/80');

  if (content.includes('alert(') && !content.includes('import { toast }')) {
    const importRegex = /import .* from '.*';\n/g;
    let lastMatch;
    let match;
    while ((match = importRegex.exec(content)) !== null) {
      lastMatch = match;
    }
    if (lastMatch) {
      const insertIndex = lastMatch.index + lastMatch[0].length;
      content = content.slice(0, insertIndex) + `import { toast } from "@/lib/toast";\n` + content.slice(insertIndex);
    } else {
        content = `import { toast } from "@/lib/toast";\n` + content;
    }
  }
  
  content = content.replace(/alert\("([^"]+)"\)/g, 'toast.error("$1")');
  content = content.replace(/alert\(`([^`]+)`\)/g, 'toast.error(`$1`)');

  if (original !== content) {
    fs.writeFileSync(filepath, content, 'utf8');
    console.log('Updated ' + filepath);
  }
};

const dashboardPath = path.join(__dirname, 'app', 'dashboard');
const files = walkSync(dashboardPath);
files.forEach(file => replaceInFile(file));

console.log('Done');
