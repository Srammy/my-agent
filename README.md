# MyAgent

MyAgent is a Java/Spring Boot backend and Vue frontend for an AgentScope-based assistant. The default Docker path starts MySQL, Redis, the backend, and the frontend.

## Prerequisites

- Docker and Docker Compose for the end-to-end path.
- JDK 21 and Maven 3.9+ for local backend development.
- Node.js 22+ and npm for local frontend development.
- A DashScope API key if you want live model calls. Without a key, keep `AGENT_SCOPE_ENABLED=false` and use the stub or error-display path.

## Environment Variables

Copy the example file before starting Docker:

```bash
cp .env.example .env
```

Important variables:

- `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USERNAME`, `MYSQL_PASSWORD`: MySQL settings. The example uses root with `change-me` for local development only.
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_DATABASE`: Redis settings.
- `SECURITY_JWT_SECRET`: required by the backend. Replace the example value with a long random secret outside local development.
- `AGENT_SCOPE_ENABLED`: defaults to `false`. Set to `true` only when model credentials and runtime behavior are ready.
- `DASHSCOPE_API_KEY`: DashScope key used by the default model provider.
- `AGENT_MODEL_PROVIDER`: defaults to `dashscope`.
- `AGENT_MODEL_NAME`: defaults to `dashscope:qwen-plus`.
- `AGENT_MODEL_BASE_URL`: optional base URL for OpenAI-compatible providers.
- `AGENT_MODEL_API_KEY_ENV`: name of the environment variable that contains the model API key. Defaults to `DASHSCOPE_API_KEY`.
- `VITE_API_PROXY_TARGET`: optional local Vite dev proxy target. Defaults to `http://localhost:8080`.

## Docker Startup

```bash
cp .env.example .env && docker compose up -d
```

The frontend is published at `http://localhost:5173`, and it proxies `/api/` requests to the backend container. The backend listens on `http://localhost:8080`.

Use `docker compose logs -f backend` if the UI reports backend or model configuration errors.

## Backend Local Startup

Start local MySQL and Redis first. The default local backend configuration expects:

- MySQL: `localhost:3306`, database `myagent`, user `root`, password `root`.
- Redis: `localhost:6379`, database `0`.

Then run:

```bash
cd backend
$env:SECURITY_JWT_SECRET="dev-only-change-me-to-a-long-random-secret"
$env:AGENT_SCOPE_ENABLED="false"
mvn spring-boot:run
```

For PowerShell, the `$env:` assignments above apply to the current shell. In Bash, use `export SECURITY_JWT_SECRET=...` and `export AGENT_SCOPE_ENABLED=false`.

## Frontend Local Startup

Install dependencies and run Vite:

```bash
cd frontend
npm install
npm run dev
```

The development server defaults to `http://localhost:5173`. Run the backend separately on `http://localhost:8080` for API calls. If your backend runs elsewhere, set `VITE_API_PROXY_TARGET` before `npm run dev`.

## MySQL and Redis

Docker Compose starts MySQL 8.4 and Redis 7 with health checks. `docker/mysql/init.sql` only creates the `myagent` database and sets UTF-8 defaults. Application tables are owned by backend Flyway migrations under `backend/src/main/resources/db/migration`; do not duplicate table DDL in the Docker init script.

Compose persists MySQL data in the `mysql_data` volume. To reset local Docker data, stop the stack and remove the volume explicitly:

```bash
docker compose down -v
```

## Model Provider Switching

The default provider is DashScope with `AGENT_MODEL_NAME=dashscope:qwen-plus`.

For an OpenAI-compatible provider, configure the provider, base URL, API key environment variable name, and model name in `.env`:

```dotenv
AGENT_MODEL_PROVIDER=openai-compatible
AGENT_MODEL_BASE_URL=https://example.com/v1
AGENT_MODEL_API_KEY_ENV=OPENAI_COMPATIBLE_API_KEY
OPENAI_COMPATIBLE_API_KEY=replace-me
AGENT_MODEL_NAME=openai-compatible:your-model-name
AGENT_SCOPE_ENABLED=true
```

Keep `AGENT_SCOPE_ENABLED=false` when no usable API key is available.

## High-Privilege Tool Safety

The backend defaults keep high-privilege tools disabled:

- `agent.tools.file-tools-enabled=false`
- `agent.tools.shell-enabled=false`
- `agent.tools.http-fetch-enabled=false`
- `agent.tools.mcp-enabled=false`

File, shell, HTTP fetch, and MCP tools can access local files, execute commands, reach networks, or call external servers depending on the runtime. Enable them only in a trusted development environment.

Permission modes also matter. `DEFAULT`, `EXPLORE`, and `ACCEPT_EDITS` are safer interactive modes. `DONT_ASK` and especially `BYPASS` reduce or remove confirmation boundaries and should only be used inside a trusted sandbox with disposable credentials and data.
