import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

export const createStompClient = (onMessageReceived) => {
  const client = new Client({
    // 使用 SockJS 连接后端的 /ws 端点
    webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
    debug: function (str) {
      console.log('STOMP: ' + str)
    },
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
  })

  client.onConnect = function (frame) {
    // 订阅全局通知频道
    client.subscribe('/topic/notifications', (message) => {
      if (message.body) {
        onMessageReceived(JSON.parse(message.body))
      }
    })
  }

  client.onStompError = function (frame) {
    console.error('Broker reported error: ' + frame.headers['message'])
  }

  client.activate()
  return client
}