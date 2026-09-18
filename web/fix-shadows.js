const fs = require('fs');
const path = require('path');

const dir = 'D:/projects/Oculus/web/app';

function walk(directory) {
  const files = fs.readdirSync(directory);
  for (const file of files) {
    const fullPath = path.join(directory, file);
    if (fs.statSync(fullPath).isDirectory()) {
      walk(fullPath);
    } else if (fullPath.endsWith('.tsx')) {
      let content = fs.readFileSync(fullPath, 'utf8');
      const original = content;
      // remove shadow-sm, shadow-none, shadow, etc
      content = content.replace(/shadow(-[a-z]+)?/g, '');
      if (content !== original) {
        fs.writeFileSync(fullPath, content);
        console.log(`Removed shadow from ${fullPath}`);
      }
    }
  }
}

walk(dir);
