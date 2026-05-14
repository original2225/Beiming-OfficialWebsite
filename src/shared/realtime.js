export function createRealtimeClient(url) {
  const listeners = new Map();
  let opened = false;
  let closed = false;
  const socket = new WebSocket(url);

  const emitLocal = (event, payload) => {
    const callbacks = listeners.get(event) || [];
    callbacks.forEach((callback) => callback(payload));
  };

  socket.addEventListener('open', () => {
    opened = true;
    emitLocal('connect');
  });

  socket.addEventListener('message', (message) => {
    let packet;
    try {
      packet = JSON.parse(message.data);
    } catch {
      return;
    }
    if (!packet?.event) return;
    if (packet.payload !== undefined && packet.data === undefined) {
      if (packet.payload && typeof packet.payload === 'object') {
        packet.ok = packet.payload.ok ?? packet.ok;
        packet.message = packet.payload.message ?? packet.message;
        packet.timestamp = packet.payload.timestamp ?? packet.timestamp;
        packet.data = 'data' in packet.payload ? packet.payload.data : packet.payload;
      } else {
        packet.data = packet.payload;
      }
    }
    emitLocal(packet.event, packet);
  });

  socket.addEventListener('error', () => {
    emitLocal('connect_error', new Error('WebSocket connection failed'));
  });

  socket.addEventListener('close', () => {
    if (!closed && !opened) {
      emitLocal('connect_error', new Error('WebSocket connection closed before ready'));
    }
    emitLocal('disconnect');
  });

  return {
    on(event, callback) {
      const callbacks = listeners.get(event) || [];
      callbacks.push(callback);
      listeners.set(event, callbacks);
      return this;
    },
    emit(event, data = {}) {
      if (socket.readyState !== WebSocket.OPEN) return false;
      socket.send(JSON.stringify({ event, data }));
      return true;
    },
    disconnect() {
      closed = true;
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close(1000, 'client closed');
      }
    },
  };
}
