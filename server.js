import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import crypto from 'crypto';
import { execSync } from 'child_process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;
const PUBLIC_DIR = path.join(__dirname, 'www');

// Read app version from package.json
let APP_VERSION = '1.0.0';
try {
  const pkgPath = path.join(__dirname, 'package.json');
  if (fs.existsSync(pkgPath)) {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    if (pkg.version) APP_VERSION = pkg.version;
  }
} catch (e) {
  // default 1.0.0
}

function getReleases() {
  const relPath = path.join(__dirname, 'releases.json');
  if (fs.existsSync(relPath)) {
    try {
      return JSON.parse(fs.readFileSync(relPath, 'utf8'));
    } catch (e) {
      return [];
    }
  }
  return [];
}

function saveReleases(releases) {
  const relPath = path.join(__dirname, 'releases.json');
  fs.writeFileSync(relPath, JSON.stringify(releases, null, 2), 'utf8');
}

function getGitHistory() {
  try {
    const raw = execSync('git log -n 15 --pretty=format:\'%h|%an|%ad|%s\'', { encoding: 'utf8' });
    return raw.trim().split('\n').map(line => {
      const [hash, author, date, message] = line.split('|');
      return { hash, author, date, message };
    });
  } catch (e) {
    return [];
  }
}

const APK_DISPLAY_NAME = `jebena pay v${APP_VERSION}.apk`;

const MIME_TYPES = {
  '.html': 'text/html',
  '.css': 'text/css',
  '.js': 'text/javascript',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.apk': 'application/vnd.android.package-archive'
};

const server = http.createServer((req, res) => {
  // GET Version & Release History API
  if (req.method === 'GET' && req.url === '/api/version-history') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      currentVersion: APP_VERSION,
      gitHistory: getGitHistory(),
      releases: getReleases()
    }));
    return;
  }

  // POST Publish New Version Release API
  if (req.method === 'POST' && req.url === '/api/releases') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        const payload = JSON.parse(body || '{}');
        const newVer = (payload.version || '').replace(/^v/, '').trim();
        if (!newVer) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Valid version number required (e.g. 1.0.1)' }));
          return;
        }

        // Update package.json version
        const pkgPath = path.join(__dirname, 'package.json');
        if (fs.existsSync(pkgPath)) {
          const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
          pkg.version = newVer;
          fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2), 'utf8');
        }
        APP_VERSION = newVer;

        const gitHistory = getGitHistory();
        const latestCommitHash = gitHistory.length > 0 ? gitHistory[0].hash : 'head';

        const releases = getReleases();
        const changesArr = Array.isArray(payload.changes) 
          ? payload.changes 
          : (payload.changes || '').split('\n').map(s => s.trim()).filter(Boolean);

        const newRelease = {
          version: newVer,
          releaseDate: new Date().toISOString().split('T')[0],
          title: payload.title || `v${newVer} Release`,
          apkName: `jebena pay v${newVer}.apk`,
          commitHash: latestCommitHash,
          status: payload.status || 'Production',
          changes: changesArr.length > 0 ? changesArr : ['General bug fixes and performance improvements']
        };

        // Remove existing release with same version if exists, then unshift
        const filtered = releases.filter(r => r.version !== newVer);
        filtered.unshift(newRelease);
        saveReleases(filtered);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          success: true,
          currentVersion: APP_VERSION,
          releases: filtered,
          gitHistory
        }));
      } catch (err) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
    return;
  }

  // Backblaze secure download proxy/redirect API for private buckets
  if (req.method === 'GET' && req.url.startsWith('/api/download-apk')) {
    const urlObj = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const keyId = urlObj.searchParams.get('keyId') || 'affc1d33accc';
    const applicationKey = urlObj.searchParams.get('applicationKey') || '0050538f19f6604d5c849f8562d20e12d2923de30a';
    const bucketNameParam = urlObj.searchParams.get('bucketName') || 'jebenapay-build';
    const reqFileName = urlObj.searchParams.get('fileName');
    let targetFileName = reqFileName || APK_DISPLAY_NAME;

    (async () => {
      try {
        // Step 1: Authorize Account with B2
        const authHeader = 'Basic ' + Buffer.from(`${keyId}:${applicationKey}`).toString('base64');
        const authRes = await fetch('https://api.backblazeb2.com/b2api/v2/b2_authorize_account', {
          headers: { 'Authorization': authHeader }
        });

        if (!authRes.ok) {
          const errText = await authRes.text();
          throw new Error(`Failed to authorize with Backblaze: ${errText}`);
        }

        const authData = await authRes.json();
        const { authorizationToken: accountAuthToken, apiUrl, downloadUrl, accountId } = authData;

        // Step 2: List buckets to find the correct bucketId
        const listRes = await fetch(`${apiUrl}/b2api/v2/b2_list_buckets`, {
          method: 'POST',
          headers: {
            'Authorization': accountAuthToken,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ accountId })
        });
        if (!listRes.ok) {
          const errText = await listRes.text();
          throw new Error(`Failed to list buckets: ${errText}`);
        }
        const listData = await listRes.json();
        const buckets = listData.buckets || [];
        
        const foundBucket = buckets.find(b => b.bucketName === bucketNameParam) || buckets[0];
        if (!foundBucket) {
          throw new Error(`No buckets found in this Backblaze B2 account.`);
        }

        const bucketId = foundBucket.bucketId;
        const bucketName = foundBucket.bucketName;

        // Step 3: Check for exact file or find latest jebena pay apk file in bucket
        if (!reqFileName) {
          try {
            const fileListRes = await fetch(`${apiUrl}/b2api/v2/b2_list_file_names`, {
              method: 'POST',
              headers: { 'Authorization': accountAuthToken, 'Content-Type': 'application/json' },
              body: JSON.stringify({ bucketId, maxFileCount: 20, prefix: 'jebena' })
            });
            if (fileListRes.ok) {
              const fileListData = await fileListRes.json();
              if (fileListData.files && fileListData.files.length > 0) {
                // Pick the latest file
                targetFileName = fileListData.files[0].fileName;
              }
            }
          } catch (e) {
            // fallback to targetFileName
          }
        }

        // Step 4: Call b2_get_download_authorization
        const getAuthRes = await fetch(`${apiUrl}/b2api/v2/b2_get_download_authorization`, {
          method: 'POST',
          headers: {
            'Authorization': accountAuthToken,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            bucketId,
            fileNamePrefix: targetFileName,
            validDurationInSeconds: 3600 // 1 hour token
          })
        });

        if (!getAuthRes.ok) {
          const errText = await getAuthRes.text();
          throw new Error(`Failed to get download authorization: ${errText}`);
        }

        const getAuthData = await getAuthRes.json();
        const downloadAuthToken = getAuthData.authorizationToken;

        // Step 5: Redirect the client to direct download link
        const authorizedDownloadUrl = `${downloadUrl}/file/${bucketName}/${encodeURIComponent(targetFileName)}?Authorization=${downloadAuthToken}`;
        
        res.writeHead(302, { 'Location': authorizedDownloadUrl });
        res.end();
      } catch (err) {
        console.error('Backblaze Redirect Error:', err);
        res.writeHead(500, { 'Content-Type': 'text/html' });
        res.end(`
          <html>
            <head>
              <meta charset="UTF-8">
              <title>Download Error — Jebena Pay</title>
              <script src="https://cdn.tailwindcss.com"></script>
            </head>
            <body class="bg-[#0A0A0C] text-slate-100 min-h-screen flex flex-col items-center justify-center p-6 font-sans">
              <div class="max-w-md w-full bg-[#111115] border border-slate-800 rounded-2xl p-6 md:p-8 space-y-4 text-center shadow-2xl">
                <div class="w-12 h-12 bg-rose-500/10 border border-rose-500/20 text-rose-400 rounded-full flex items-center justify-center mx-auto text-xl">⚠️</div>
                <h1 class="text-lg font-bold text-white">B2 Download Failed</h1>
                <p class="text-xs text-slate-400 leading-relaxed">${err.message}</p>
                <a href="/" class="inline-block mt-2 px-4 py-2 bg-emerald-500 hover:bg-emerald-400 text-black font-bold text-xs rounded-xl transition-all">Go Back Home</a>
              </div>
            </body>
          </html>
        `);
      }
    })();
    return;
  }

  // Backblaze upload API (uploads APK as "jebena pay v1.0.0.apk")
  if (req.method === 'POST' && req.url === '/api/backblaze-upload') {
    let body = '';
    req.on('data', chunk => {
      body += chunk;
    });
    req.on('end', async () => {
      try {
        const data = JSON.parse(body || '{}');
        const keyId = data.keyId || 'affc1d33accc';
        const applicationKey = data.applicationKey || '0050538f19f6604d5c849f8562d20e12d2923de30a';
        let bucketId = data.bucketId;
        let bucketName = data.bucketName;
        const uploadVersion = data.version || APP_VERSION;
        const targetUploadFileName = `jebena pay v${uploadVersion}.apk`;

        if (!keyId || !applicationKey) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Backblaze Key ID and Application Key are required.' }));
          return;
        }

        // Step 1: Authorize Account
        const authHeader = 'Basic ' + Buffer.from(`${keyId}:${applicationKey}`).toString('base64');
        const authRes = await fetch('https://api.backblazeb2.com/b2api/v2/b2_authorize_account', {
          headers: { 'Authorization': authHeader }
        });

        if (!authRes.ok) {
          const errText = await authRes.text();
          throw new Error(`Failed to authorize with Backblaze: ${errText}`);
        }

        const authData = await authRes.json();
        const { authorizationToken, apiUrl, downloadUrl, accountId } = authData;

        // Step 2: List buckets to find ID or verify bucket name
        const listRes = await fetch(`${apiUrl}/b2api/v2/b2_list_buckets`, {
          method: 'POST',
          headers: {
            'Authorization': authorizationToken,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ accountId })
        });
        if (!listRes.ok) {
          const errText = await listRes.text();
          throw new Error(`Failed to list buckets: ${errText}`);
        }
        const listData = await listRes.json();
        const buckets = listData.buckets || [];
        
        let foundBucket = null;
        if (bucketName) {
          foundBucket = buckets.find(b => b.bucketName === bucketName);
        } else if (bucketId) {
          foundBucket = buckets.find(b => b.bucketId === bucketId);
        } else {
          foundBucket = buckets[0];
        }

        if (!foundBucket) {
          throw new Error(`No matching bucket found in your Backblaze B2 account.`);
        }

        bucketId = foundBucket.bucketId;
        bucketName = foundBucket.bucketName;

        // Step 3: Get Upload URL
        const uploadUrlRes = await fetch(`${apiUrl}/b2api/v2/b2_get_upload_url`, {
          method: 'POST',
          headers: {
            'Authorization': authorizationToken,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ bucketId })
        });

        if (!uploadUrlRes.ok) {
          const errText = await uploadUrlRes.text();
          throw new Error(`Failed to get upload URL: ${errText}`);
        }

        const uploadUrlData = await uploadUrlRes.json();
        const { uploadUrl, authorizationToken: uploadAuthToken } = uploadUrlData;

        // Step 4: Read APK file from build outputs or static folder
        let apkPath = path.resolve('.build-outputs/app-debug.apk');
        if (!fs.existsSync(apkPath)) {
          apkPath = path.resolve('app/build/outputs/apk/debug/app-debug.apk');
        }
        if (!fs.existsSync(apkPath)) {
          apkPath = path.join(PUBLIC_DIR, 'jebena-pay.apk');
        }
        
        if (!fs.existsSync(apkPath)) {
          throw new Error('APK file (app-debug.apk) does not exist on server.');
        }
        const fileBuffer = fs.readFileSync(apkPath);

        // Step 5: Calculate SHA-1
        const sha1 = crypto.createHash('sha1').update(fileBuffer).digest('hex');

        // Step 6: Upload File with Versioned Name ("jebena pay v1.0.0.apk")
        const uploadRes = await fetch(uploadUrl, {
          method: 'POST',
          headers: {
            'Authorization': uploadAuthToken,
            'X-Bz-File-Name': encodeURIComponent(targetUploadFileName),
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Length': fileBuffer.length.toString(),
            'X-Bz-Content-Sha1': sha1
          },
          body: fileBuffer
        });

        if (!uploadRes.ok) {
          const errText = await uploadRes.text();
          throw new Error(`Failed to upload file to Backblaze: ${errText}`);
        }

        const uploadData = await uploadRes.json();
        const downloadLink = `${downloadUrl}/file/${bucketName}/${encodeURIComponent(targetUploadFileName)}`;

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          success: true,
          downloadLink,
          fileId: uploadData.fileId,
          fileName: uploadData.fileName,
          size: uploadData.contentLength
        }));
      } catch (err) {
        console.error('Backblaze Upload Error:', err);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
    return;
  }

  // Normalize URL path to prevent directory traversal
  let safePath = req.url?.split('?')[0] || '/index.html';
  if (safePath === '/') {
    safePath = '/index.html';
  }
  
  const resolvedPath = path.join(PUBLIC_DIR, safePath);

  // Security check: ensure path is within PUBLIC_DIR
  if (!resolvedPath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Forbidden');
    return;
  }

  fs.stat(resolvedPath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
      return;
    }

    const ext = path.extname(resolvedPath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    res.writeHead(200, { 'Content-Type': contentType });
    const stream = fs.createReadStream(resolvedPath);
    stream.pipe(res);
  });
});

server.listen(PORT, () => {
  console.log(`Server is running at http://localhost:${PORT}`);
});
