const TOKEN_KEY = 'myagent.token'

type ApiMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export class ApiError extends Error {
  status: number
  data: unknown

  constructor(message: string, status: number, data: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.data = data
  }
}

function readToken() {
  return localStorage.getItem(TOKEN_KEY)
}

function resolveErrorMessage(data: unknown, fallback: string) {
  if (data && typeof data === 'object') {
    const record = data as Record<string, unknown>
    const message = record.message ?? record.error ?? record.detail

    if (typeof message === 'string' && message.trim()) {
      return message
    }
  }

  return fallback
}

async function parseResponse(response: Response) {
  if (response.status === 204) {
    return null
  }

  const contentType = response.headers.get('content-type') ?? ''

  if (contentType.includes('application/json')) {
    return response.json()
  }

  const text = await response.text()
  return text || null
}

async function apiRequest<T>(method: ApiMethod, path: string, body?: unknown): Promise<T> {
  const headers = new Headers()
  const token = readToken()

  if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }

  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })

  const data = await parseResponse(response)

  if (!response.ok) {
    throw new ApiError(resolveErrorMessage(data, '请求失败，请稍后重试'), response.status, data)
  }

  return data as T
}

export function apiGet<T>(path: string) {
  return apiRequest<T>('GET', path)
}

export function apiPost<T>(path: string, body?: unknown) {
  return apiRequest<T>('POST', path, body)
}

export function apiPut<T>(path: string, body?: unknown) {
  return apiRequest<T>('PUT', path, body)
}

export function apiDelete<T>(path: string) {
  return apiRequest<T>('DELETE', path)
}

export { TOKEN_KEY }
