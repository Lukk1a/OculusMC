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

  // Remove shadows, rings, drop-shadows, gradients
  content = content.replace(/shadow-(sm|md|lg|xl|2xl|none|inner)\b/g, '');
  content = content.replace(/ring-(1|2|4|8)\b/g, '');
  content = content.replace(/ring-\[[^\]]+\]\b/g, '');
  content = content.replace(/focus:ring-\[[^\]]+\]\b/g, '');
  content = content.replace(/focus-visible:ring-\[[^\]]+\]\b/g, '');
  content = content.replace(/bg-gradient-to-[a-z]+\b/g, '');
  content = content.replace(/from-[a-z0-9\-]+\b/g, '');
  content = content.replace(/to-[a-z0-9\-]+\b/g, '');
  content = content.replace(/via-[a-z0-9\-]+\b/g, '');
  content = content.replace(/drop-shadow-[a-z0-9\-]+\b/g, '');

  if (original !== content) {
    fs.writeFileSync(filepath, content, 'utf8');
    console.log('Updated', filepath);
  }
};

const appPath = path.join(__dirname, 'app');
const files = walkSync(appPath);
files.forEach(file => replaceInFile(file));

console.log('Done');
