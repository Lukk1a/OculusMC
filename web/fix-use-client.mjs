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

const fixFile = (filepath) => {
  let content = fs.readFileSync(filepath, 'utf8');
  let original = content;

  if (content.includes('"use client";') && !content.startsWith('"use client";')) {
    // Remove all instances of "use client";
    content = content.replace(/"use client";\s*/g, '');
    // Add it to the top
    content = `"use client";\n\n` + content;
  }

  if (original !== content) {
    fs.writeFileSync(filepath, content, 'utf8');
    console.log('Fixed ' + filepath);
  }
};

const dashboardPath = path.join(__dirname, 'app', 'dashboard');
const files = walkSync(dashboardPath);
files.forEach(file => fixFile(file));

console.log('Done fix-use-client');
