import { getToken, isTokenExpired, refreshAccessToken } from './api';

type MessageHandler = (data: any) => void;

export class ConsoleWebSocket {
  private ws: WebSocket | null = null;
  private handlers: Set<MessageHandler> = new Set();
  private isConnecting = false;
  private isClosedByUser = false;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private pingInterval: NodeJS.Timeout | null = null;
  private url: string;
  private consecutiveAuthFailures = 0;
  private readonly maxConsecutiveAuthFailures = 3;

  constructor(url?: string) {
    this.url = url || '/ws/console';
  }

  async connect() {
    if (this.isConnecting) return;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    this.isClosedByUser = false;
    this.isConnecting = true;

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    // Verify token freshness or refresh prior to connecting / reconnecting
    let token = getToken();
    if (!token || isTokenExpired(token)) {
      token = await refreshAccessToken();
    }

    if (this.isClosedByUser) {
      this.isConnecting = false;
      return;
    }

    if (!token) {
      console.warn('[ConsoleWebSocket] Access token is missing or expired, and refresh failed. Halting reconnect.');
      this.isConnecting = false;
      return;
    }

    if (this.consecutiveAuthFailures >= this.maxConsecutiveAuthFailures) {
      console.warn('[ConsoleWebSocket] Max consecutive authentication failures reached. Halting reconnect.');
      this.isConnecting = false;
      return;
    }

    // Determine WS URL based on current origin if relative
    let wsUrl = this.url;
    if (wsUrl.startsWith('/')) {
      const protocol = typeof window !== 'undefined' && window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const host = typeof window !== 'undefined' ? window.location.host : 'localhost:3000';
      wsUrl = `${protocol}//${host}${wsUrl}`;
    }

    try {
      this.ws = new WebSocket(wsUrl);
    } catch (e) {
      console.error('[ConsoleWebSocket] Failed to construct WebSocket:', e);
      this.isConnecting = false;
      this.scheduleReconnect();
      return;
    }

    this.ws.onopen = () => {
      this.isConnecting = false;
      this.ws?.send(JSON.stringify({ type: 'auth', token }));

      if (this.pingInterval) clearInterval(this.pingInterval);
      this.pingInterval = setInterval(() => {
        this.send({ type: 'ping' });
      }, 30000);
    };

    this.ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'pong') return;
        if (data.type === 'hello') {
          this.consecutiveAuthFailures = 0;
        }
        this.handlers.forEach(handler => handler(data));
      } catch (e) {
        console.error('[ConsoleWebSocket] Failed to parse WS message', e);
      }
    };

    this.ws.onclose = (event) => {
      this.cleanup();

      if (this.isClosedByUser) return;

      if (event.code === 1008) {
        this.consecutiveAuthFailures++;
        if (this.consecutiveAuthFailures >= this.maxConsecutiveAuthFailures) {
          console.warn('[ConsoleWebSocket] Repeated authentication failures (code 1008). Halting reconnect.');
          return;
        }
      }

      this.scheduleReconnect();
    };

    this.ws.onerror = (error) => {
      console.error('[ConsoleWebSocket] WebSocket error:', error);
      this.ws?.close();
    };
  }

  private scheduleReconnect(delay = 3000) {
    if (this.isClosedByUser) return;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }

  send(data: any) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    }
  }

  subscribe(handler: MessageHandler) {
    this.handlers.add(handler);
    return () => {
      this.handlers.delete(handler);
    };
  }

  disconnect() {
    this.isClosedByUser = true;
    this.consecutiveAuthFailures = 0;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.cleanup();
  }

  private cleanup() {
    this.isConnecting = false;
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      this.ws.close();
      this.ws = null;
    }
  }
}

// In case the API is on port 3001 (common pattern), we could point it there. 
// But let's assume standard relative path. If proxy is configured, it will work.
// Actually, let's point to ws://localhost:3001/ws/console if in dev, but standard relative is safer if we don't know.
export const consoleWs = new ConsoleWebSocket('/ws/console');
export const telemetryWs = new ConsoleWebSocket('/ws/telemetry');
