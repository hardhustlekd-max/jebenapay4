import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import crypto from 'crypto';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;
const PUBLIC_DIR = path.join(__dirname, 'www');

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

// Real-time Transaction Store & SSE Live Clients
let transactions = [
  {
    id: 'TXN-1001',
    sender: 'Telebirr',
    amount: 1250.00,
    type: 'CREDIT',
    reference: 'TB839201948',
    merchantOrParty: 'Abebe Kebede',
    category: 'Mobile Wallet',
    timestamp: Date.now() - 3600000,
    rawSms: 'You have received ETB 1,250.00 from Abebe Kebede. Ref: TB839201948.'
  },
  {
    id: 'TXN-1000',
    sender: 'CBE',
    amount: 320.00,
    type: 'DEBIT',
    reference: 'CBE99401294',
    merchantOrParty: 'Kaldi\'s Coffee',
    category: 'Food & Beverage',
    timestamp: Date.now() - 86400000,
    rawSms: 'Your account 1000****1234 has been debited with ETB 320.00 at Kaldi\'s Coffee. Ref: CBE99401294.'
  }
];

let sseClients = [];

function broadcastTransaction(transaction) {
  const data = `data: ${JSON.stringify(transaction)}\n\n`;
  sseClients.forEach(res => {
    try {
      res.write(data);
    } catch (err) {
      // client disconnected
    }
  });
}

const server = http.createServer((req, res) => {
  // Real-time SSE Stream Endpoint for open UI dashboard updates
  if (req.method === 'GET' && req.url === '/api/transactions/stream') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*'
    });
    res.write('retry: 3000\n\n');
    sseClients.push(res);

    req.on('close', () => {
      sseClients = sseClients.filter(c => c !== res);
    });
    return;
  }

  // GET all transactions
  if (req.method === 'GET' && req.url === '/api/transactions') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ transactions }));
    return;
  }

  // POST capture new SMS transaction (dynamically updates all open UI dashboards)
  if (req.method === 'POST' && req.url === '/api/transactions') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        const payload = JSON.parse(body || '{}');
        const sender = payload.sender || 'Telebirr';
        const bodyText = payload.rawSms || payload.body || '';
        const amount = parseFloat(payload.amount) || 250.00;
        const type = payload.type || (bodyText.toLowerCase().includes('received') || bodyText.toLowerCase().includes('credit') ? 'CREDIT' : 'DEBIT');
        const reference = payload.reference || `TXN-${Math.floor(10000000 + Math.random() * 90000000)}`;
        const merchantOrParty = payload.merchantOrParty || sender;

        const newTx = {
          id: `TXN-${Date.now().toString().slice(-6)}`,
          sender,
          amount,
          type,
          reference,
          merchantOrParty,
          category: payload.category || 'General',
          timestamp: Date.now(),
          rawSms: bodyText || `Transaction of ETB ${amount} via ${sender}`
        };

        transactions.unshift(newTx);
        broadcastTransaction(newTx);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, transaction: newTx }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
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
    const fileName = 'jebena-pay.apk';

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

        // Step 3: Call b2_get_download_authorization
        const getAuthRes = await fetch(`${apiUrl}/b2api/v2/b2_get_download_authorization`, {
          method: 'POST',
          headers: {
            'Authorization': accountAuthToken,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            bucketId,
            fileNamePrefix: fileName,
            validDurationInSeconds: 3600 // 1 hour token
          })
        });

        if (!getAuthRes.ok) {
          const errText = await getAuthRes.text();
          throw new Error(`Failed to get download authorization: ${errText}`);
        }

        const getAuthData = await getAuthRes.json();
        const downloadAuthToken = getAuthData.authorizationToken;

        // Step 4: Redirect the client to the direct download link with the authorization token
        const authorizedDownloadUrl = `${downloadUrl}/file/${bucketName}/${fileName}?Authorization=${downloadAuthToken}`;
        
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
                <p class="text-[10px] text-slate-500">Please verify your Key ID, Application Key, and Bucket Name in the dashboard settings.</p>
                <a href="/" class="inline-block mt-2 px-4 py-2 bg-emerald-500 hover:bg-emerald-400 text-black font-bold text-xs rounded-xl transition-all">Go Back to Dashboard</a>
              </div>
            </body>
          </html>
        `);
      }
    })();
    return;
  }

  // Backblaze upload API
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
          // Default to the first bucket if none specified
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
          apkPath = path.join(PUBLIC_DIR, 'jebena-pay.apk');
        }
        
        if (!fs.existsSync(apkPath)) {
          throw new Error('APK file (app-debug.apk) does not exist on server.');
        }
        const fileBuffer = fs.readFileSync(apkPath);

        // Step 5: Calculate SHA-1
        const sha1 = crypto.createHash('sha1').update(fileBuffer).digest('hex');

        // Step 6: Upload File
        const fileName = 'jebena-pay.apk';
        const uploadRes = await fetch(uploadUrl, {
          method: 'POST',
          headers: {
            'Authorization': uploadAuthToken,
            'X-Bz-File-Name': encodeURIComponent(fileName),
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
        const downloadLink = `${downloadUrl}/file/${bucketName}/${fileName}`;

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
