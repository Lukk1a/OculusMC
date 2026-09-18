// In-memory access token storage (SPA memory)
let memoryAccessToken: string | null = null;
let isRefreshing = false;
let refreshSubscribers: ((token: string | null) => void)[] = [];

function onTokenRefreshed(token: string | null) {
  refreshSubscribers.forEach((callback) => callback(token));
  refreshSubscribers = [];
}

export function setToken(token: string | null) {
  memoryAccessToken = token;
}

export function getToken(): string | null {
  return memoryAccessToken;
}

export function clearToken() {
  memoryAccessToken = null;
}

export function isTokenExpired(token: string | null = memoryAccessToken, skewSeconds = 10): boolean {
  if (!token) return true;
  try {
    const base64Url = token.split('.')[1];
    if (!base64Url) return true;
    let base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    while (base64.length % 4) {
      base64 += '=';
    }
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    const payload = JSON.parse(jsonPayload);
    if (!payload.exp) return false;
    const nowSeconds = Math.floor(Date.now() / 1000);
    return payload.exp <= nowSeconds + skewSeconds;
  } catch (e) {
    return true;
  }
}

let refreshPromise: Promise<string | null> | null = null;

export async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) {
    return refreshPromise;
  }

  refreshPromise = (async () => {
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'same-origin',
      });

      if (!res.ok) {
        clearToken();
        return null;
      }

      const data = await res.json();
      if (data.accessToken) {
        setToken(data.accessToken);
        return data.accessToken;
      }
      clearToken();
      return null;
    } catch (e) {
      clearToken();
      return null;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

export async function getFreshToken(): Promise<string | null> {
  const token = getToken();
  if (token && !isTokenExpired(token)) {
    return token;
  }
  return await refreshAccessToken();
}

export async function fetchApi(endpoint: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers || {});
  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const token = getToken();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  // Ensure cookies are sent (for /api/auth/*)
  const fetchOptions: RequestInit = {
    ...options,
    headers,
    credentials: 'same-origin',
  };

  let response = await fetch(endpoint, fetchOptions);

  // Handle 401 for authenticated API calls
  if (response.status === 401 && !endpoint.startsWith('/api/auth/')) {
    if (!isRefreshing) {
      isRefreshing = true;
      const newToken = await refreshAccessToken();
      isRefreshing = false;
      onTokenRefreshed(newToken);

      if (newToken) {
        headers.set('Authorization', `Bearer ${newToken}`);
        return fetch(endpoint, {
          ...options,
          headers,
          credentials: 'same-origin',
        }).then(handleResponse);
      } else {
        if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
          window.location.href = '/login';
        }
        return handleResponse(response);
      }
    } else {
      // Wait for ongoing refresh
      const retryPromise = new Promise<Response>((resolve) => {
        refreshSubscribers.push((newToken) => {
          if (newToken) {
            headers.set('Authorization', `Bearer ${newToken}`);
            resolve(
              fetch(endpoint, {
                ...options,
                headers,
                credentials: 'same-origin',
              })
            );
          } else {
            if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
              window.location.href = '/login';
            }
            resolve(response);
          }
        });
      });
      return retryPromise.then(handleResponse);
    }
  }

  return handleResponse(response);
}

async function handleResponse(response: Response) {
  const contentType = response.headers.get('content-type');
  if (contentType && contentType.includes('application/json')) {
    const data = await response.json();
    if (!response.ok) {
      if (response.status === 503 && data.error === 'apollo_not_available') {
        throw new Error('APOLLO_NOT_AVAILABLE');
      }
      throw new Error(data.message || data.error || `Error ${response.status}`);
    }
    return data;
  }

  if (!response.ok) {
    throw new Error(`Error ${response.status}`);
  }

  return response.text();
}

export interface Principal {
  username: string;
  role: string;
  nodes: string[];
}

export function getAuthPrincipal(): Principal | null {
  const token = getToken();
  if (!token) return null;
  try {
    const base64Url = token.split('.')[1];
    let base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    while (base64.length % 4) {
      base64 += '=';
    }
    const jsonPayload = decodeURIComponent(atob(base64).split('').map(function(c) {
        return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
    }).join(''));
    const payload = JSON.parse(jsonPayload);
    return {
      username: payload.sub || '',
      role: payload.role || 'Viewer',
      nodes: payload.nodes || [],
    };
  } catch (e) {
    return null;
  }
}

export function hasNode(node: string): boolean {
  const principal = getAuthPrincipal();
  if (!principal) return false;
  if (principal.role.toLowerCase() === 'admin') return true;
  return principal.nodes.includes(node);
}
