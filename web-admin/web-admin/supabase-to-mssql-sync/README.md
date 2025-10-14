# supabase-to-mssql-sync

Sync Supabase (realtime) -> SQL Server, with cleanup cron.

## Setup
1. Copy `.env.example` -> `.env`, chỉnh thông số.
2. Tạo bảng trên SQL Server bằng file `sql/create_locations_mssql.sql`.
3. Cài dependencies: `npm install`.
4. Chạy: `node syncService.js` hoặc `pm2 start ecosystem.config.js`.
 
## Environment
Create a `.env` file in the project root with the following minimum variables (see `.env.example`):

- SUPABASE_URL - your Supabase URL
- SUPABASE_KEY - anon or service_role key (service role recommended for deletes)
- MSSQL_USER, MSSQL_PASSWORD, MSSQL_SERVER, MSSQL_DATABASE

## Endpoints
- GET /api/locations?deviceId=...&start=...&end=... - query history from MSSQL
- POST /api/sync { since: ISODateString } - trigger manual sync from Supabase (default: last 1 hour)

## Troubleshooting
- If the process exits with `supabaseUrl is required` or `Missing required environment variables`, copy `.env.example` to `.env` and set the missing values.

## How to run & test (Windows PowerShell)

1) Install dependencies (only once):

```powershell
cd 'C:\TTTN\web-admin\supabase-to-mssql-sync'
npm install
```

2) Start the sync service:

```powershell
cd 'C:\TTTN\web-admin\supabase-to-mssql-sync'
node syncService.js
```

You should see logs:
- "Connected to MSSQL"
- "Subscribed to Supabase realtime on locations"
- "API server running on port 3000"

3) Trigger a manual sync (pull recent rows from Supabase and upsert to MSSQL):

```powershell
# default: last 1 hour
Invoke-RestMethod -Uri 'http://localhost:3000/api/sync' -Method POST -Body (@{ since = (Get-Date).AddHours(-1).ToString('o') } | ConvertTo-Json) -ContentType 'application/json'
```

4) Query synced rows (example):

```powershell
# $start and $end must be ISO strings
# $deviceId must match the deviceId value in your rows
$deviceId = 'device-test-copilot-1'
$start = (Get-Date).AddHours(-2).ToString('o')
$end = (Get-Date).AddHours(1).ToString('o')
Invoke-RestMethod -Uri "http://localhost:3000/api/locations?deviceId=$($deviceId)&start=$($start)&end=$($end)&limit=100" -Method GET
```

Notes:
- The `timestamp` column in the Supabase table is stored as milliseconds (bigint) in this project. `/api/sync` accepts either a numeric ms value or an ISO date string and converts as needed.
- If you want the service to auto-run in background, consider using `pm2 start ecosystem.config.js` or run inside a container.


