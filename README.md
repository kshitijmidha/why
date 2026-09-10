# why

`why` takes a shell command (e.g. `git reset --hard HEAD~1`) and explains what it does.

## Why this exists

Most "explain this command" tools just forward your command to an LLM and hope
for the best. That works, but it is non-deterministic: the same command can get
different (sometimes wrong) explanations, and there is no clear record of *why*
a flag was called dangerous.

`why` splits the problem into two layers:

1. **Deterministic parsing:** a rules-based knowledge base
   (`backend/src/main/resources/knowledge/*.yaml`) breaks the command into a
   structured object — tool, subcommand, flags with meanings, args, and
   effects. No AI involved, same input always gives the same output.
2. **AI explanation layer:** an LLM (via Groq) takes that structured
   breakdown and turns it into plain English. Because the facts come from
   the deterministic layer, the LLM only has to phrase things nicely, not
   guess what `--hard` means. If the LLM is unreachable, the endpoint still
   returns the structured breakdown with `explanation: null`.

> Fixed note: effects used to include *every* effect for a subcommand
> (e.g. `git reset --soft` showed a `--hard` discards-changes effect).
> Effects are now filtered to the subcommand's baseline plus only the
> effects tied to flags actually passed (`whenFlags` in the YAML).

## Monorepo structure

```
why/
  backend/    Spring Boot (Java 17, Maven). POST /api/explain → { command, explanation }.
  cli/        Node.js + TypeScript. Tiny HTTP client that pretty-prints the result.
  frontend/   React + TypeScript + Vite. Single page with a terminal-style input.
```

- `backend/` owns the knowledge base (`knowledge/git.yaml`, `knowledge/docker.yaml`),
  the `CommandParser` service, the `ExplanationService` (Groq caller with an
  in-memory explanation cache), and unit tests.
- `cli/` and `frontend/` are thin clients: they POST `{ "command": "..." }` to the
  backend and render the structured result plus the explanation text.
- `POST /api/explain` returns `{ command, explanation, explanationError, confidence }`.
  `confidence` is `"verified"` when the command matched the knowledge base, or
  `"inferred"` when the command is unknown and the explanation is a best-effort
  LLM guess (clearly caveated, same caching/timeout/fallback rules). Commands
  over 500 characters are rejected with `400 Bad Request`.

## How to run each part locally

Prerequisites: Java 17+, Maven 3.9+, Node.js 18+.

### 0. Groq API key (for the AI explanation layer)

1. Get a free key at https://console.groq.com (sign up → API Keys → Create).
2. Create `backend/.env` from `backend/.env.example` and add your key —
   no manual environment variable needed:
   ```bash
   # backend/.env
   GROQ_API_KEY="gsk_..."
   ```
   The backend loads `backend/.env` automatically on startup (via
   spring-dotenv). A real `GROQ_API_KEY` in your shell still wins over
   the `.env` file if both are set. Without any key the app still works,
   but responses come back with `explanation: null, explanationError: true`
   (structured breakdown only).

### 1. Backend (port 8080)

```bash
cd backend
mvn spring-boot:run
```

Run the unit tests (mocked LLM, no network calls, no key needed):

```bash
cd backend
mvn test
```

### 2. CLI

In a second terminal (backend must be running):

```bash
cd cli
npm install
npm run build
node ./dist/index.js "git reset --soft HEAD~1"
# or: npm run why -- "docker run -d -p 8080:80 nginx"
```

Set `WHY_API_URL` to point at a non-default backend:
`WHY_API_URL=http://localhost:8080/api/explain node ./dist/index.js "git push --force origin main"`.

### 3. Frontend (port 5173)

In a third terminal (backend must be running):

```bash
cd frontend
npm install
npm run dev
```

Then open http://localhost:5173, type a command, and press Explain.
Production build: `npm run build`.

## Deployment

### Why the backend is containerized

Render has no native Java/Spring Boot runtime — it supports Docker
images or buildpacks for other languages only. So the backend ships
as a container on purpose: `backend/Dockerfile` builds the jar with
Maven, then runs it on a slim JRE image. The frontend is a plain
static site and needs no container.

### Backend → Render (Docker)

1. New Web Service → connect repo `kshitijmidha/why`.
2. Root directory: `backend/`, runtime: Docker (Render picks up
   `backend/Dockerfile` automatically).
3. Health check path: `/health` (returns `{"status":"ok"}`).
4. Environment variables:
   - `GROQ_API_KEY` — your Groq key (without it the API still works,
     but returns the structured breakdown with `explanation: null`).
   - `ALLOWED_ORIGIN` — the deployed frontend URL, e.g.
     `https://why.vercel.app` (no trailing slash). Defaults to
     `http://localhost:5173` for local dev.
5. `PORT` is set by Render automatically; the app reads it and falls
   back to `8080` when unset.
6. Optional: `RATE_LIMIT_REQUESTS_PER_MINUTE` (default `10`) — per-IP
   budget for `POST /api/explain` per rolling 60s window.

### API notes

- `GET /` returns a small service pointer
  (`{"status":"ok","service":"why-backend","docs":"see /api/explain"}`).
- All errors are JSON (`{"error":"...","message":"..."}`) — e.g.
  `not_found` (404), `bad_request` (400), `rate_limited` (429),
  `internal_error` (500). No Whitelabel pages.
- Over the rate limit, `POST /api/explain` returns 429 with a
  `Retry-After: 60` header; the client IP comes from `X-Forwarded-For`
  (Render's proxy) with fallback to the direct peer address.

### Frontend → Vercel or Netlify (static)

1. New project → import repo `kshitijmidha/why`, root directory:
   `frontend/`.
2. Build command: `npm run build`, output directory: `dist`.
3. Environment variable:
   - `VITE_API_BASE_URL` — the deployed backend URL, e.g.
     `https://why-backend.onrender.com` (no trailing slash, no
     `/api` suffix). Unset locally it defaults to
     `http://localhost:8080`.
4. Rebuild/redeploy the frontend whenever this value changes —
   Vite bakes it in at build time.

### Render free-tier cold starts

On the free tier Render spins the backend down after inactivity, so
the first request can take up to a minute to wake the server. The
frontend shows a quiet note about this above the command input;
subsequent requests are fast.

### Git setup / first push

```bash
cd why
git init
git add -A
git status            # confirm: no .env, node_modules/, target/, dist/
git commit -m "why: shell command explainer (backend, cli, frontend)"
git branch -M main
git remote add origin https://github.com/kshitijmidha/why.git
git push -u origin main
```

## Current status

- ✅ Structured/deterministic parsing (git + docker knowledge base,
  REST endpoint, cache, CLI, frontend).
- ✅ AI explanation layer (Groq `llama-3.1-8b-instant`, cached per
  command, graceful fallback when the LLM is down).
- 🔲 Next ideas: more tools in the knowledge base, streaming
  explanations, per-effect risk scores.
