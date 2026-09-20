(ns codex.impl.websocket.transport
  {:no-doc true})

(defprotocol Transport
  (-send! [connection data last?])
  (-ping! [connection data])
  (-pong! [connection data])
  (-close! [connection status reason])
  (-abort! [connection]))
