const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');

const root = path.resolve(__dirname, '../src/main/resources/static');
const file = path.join(root, 'index.html');
const html = fs.readFileSync(file, 'utf8');
const versioned = html.replace(/(src|href)="(\/[^"?]+\.(?:js|css))(?:\?[^"\s]*)?"/g, (_, attribute, resource) => {
  const digest = createHash('sha256').update(fs.readFileSync(path.join(root, resource))).digest('hex').slice(0, 12);
  return `${attribute}="${resource}?v=${digest}"`;
});
if (versioned !== html) fs.writeFileSync(file, versioned, 'utf8');
