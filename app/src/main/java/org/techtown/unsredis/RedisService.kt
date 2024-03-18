package org.techtown.unsredis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisURI
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.Delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.concurrent.timer

/**
 * 레디스 서비스
 */
class RedisService : Service() {
    companion object {
        private const val TAG: String = "RedisService"
    }

    data class RedisInfo(
        val client: RedisClient,
        val channel: String,
        var connection: RedisPubSubCommands<String, String>? = null,
        var isConnected: Boolean = false,
    )

    private var publishConnection: RedisCommands<String, String>? = null
    private val connectionHandler by lazy { Handler(Looper.getMainLooper()) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var timer: Timer? = null

    // thread-safe (여러 스레드에서 동시에 사용 가능한 변수들)
    private val clients by lazy { ConcurrentLinkedDeque<RedisInfo>() }
    private val isConnecting = AtomicBoolean(false)

    private var channelId = ""
    private var host = ""
    private var port = 0

    override fun onDestroy() {
        super.onDestroy()
        disconnectRedis()
    }

    override fun onCreate() {
        super.onCreate()
        initNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleCommand(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 알림 채널 초기화
     */
    private fun initNotificationChannel() {
        val channelId = "redis_channel"
        val notificationChannel =
            NotificationChannel(channelId, "Redis Channel", NotificationManager.IMPORTANCE_LOW)
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(notificationChannel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Redis")
            .setContentText("$TAG started.").build()
        startForeground(1, notification)
    }

    private fun handleCommand(intent: Intent) {
        val command = intent.getStringExtra(Extras.COMMAND)
        AppData.debug(TAG, "command in RedisService : $command")

        command?.apply {
            when (this) {
                // 연결 혹은 끊기를 while 처럼 빠르게 반복하면 redisConnectionException 이 생겨서 0.5초 딜레이
                Extras.CONNECT -> {
                    host = intent.getStringExtra(Extras.REDIS_HOST).toString()
                    port = intent.getIntExtra(Extras.REDIS_PORT, 0)
                    channelId = intent.getStringExtra(Extras.MY_CHANNEL).toString()
                    connectionHandler.removeMessages(0)
                    connectionHandler.postDelayed(::connectRedis, 500)
                }

                Extras.DISCONNECT -> {
                    connectionHandler.removeMessages(0)
                    connectionHandler.postDelayed(::disconnectRedis, 500)
                }

                Extras.SEND -> {
                    intent.getStringExtra(Extras.CHANNEL)?.let {
                        val data = intent.getStringExtra(Extras.DATA) ?: "전달할 데이터 없음"
                        sendData(it, data)
                    }
                }
            }
        }
    }

    // 레디스 연결
    private fun connectRedis() {
        AppData.debug(TAG, "connectRedis called.")

        thread {
            // 다른 channel 의 기존 연결이 있다면 끊고 다시 연결 시도 (clients 에 값이 있다면 언제나 단 하나)
            clients.forEach {
                if (it.channel != channelId) {
                    disconnectRedis(true)
                    return@thread
                }
            }
            // 이미 연결되어있다면 리턴, 없다면 true 로 변환하고 넘어간다.
            if (isConnecting.getAndSet(true)) {
                sendData(channelId, "already connected. $channelId - $host:$port")
                return@thread
            }
            // 이 부분에서 size 는 언제나 0 이어야 한다.
            AppData.error(TAG, "clients.size: ${clients.size}")
            val options = ClientResources.builder()
                .reconnectDelay(Delay.constant(Duration.ofSeconds(10)))
                .build()
            RedisURI.create(host, port)
                .let { clients.add(RedisInfo(RedisClient.create(options, it), channelId)) }
            subscribeChannel()
        }
    }

    // 연결 끊기
    private fun disconnectRedis(isReconnect: Boolean = false) {
        AppData.debug(TAG, "disconnectRedis called.")
        thread {
            timer?.cancel()
            publishConnection = null
            clients.forEach {
                if (it.isConnected) it.connection?.unsubscribe(it.channel)
                it.client.shutdown()
            }
            clients.clear()
            isConnecting.set(false)
            if (isReconnect) connectRedis()
        }
    }

    // CHECK_INTERVAL 마다 나 자신에게 메시지를 보낸다.
    private fun checkConnection() {
        thread {
            timer?.cancel()
            timer = timer(period = Extras.CHECK_INTERVAL) {
                clients.forEach {
                    try {
                        if (publishConnection == null) publishConnection =
                            it.client.connect().sync()
                        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                        val now = LocalDateTime.now().format(formatter)
                        publishConnection?.publish(channelId, "check redis connection $now")
                    } catch (e: RedisConnectionException) {
                        e.printStackTrace()
                    } catch (e: RedisCommandTimeoutException) {
                        // 연결했었지만 네트워크가 끊겨서 다시 연결 실패 상황
                        broadcastToActivity(Extras.UNKNOWN, "fail to reconnect")
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    // 구독하기
    private fun subscribeChannel() {
        thread {
            clients.forEach {
                try {
                    it.client.connectPubSub().sync()
                } catch (e: RedisConnectionException) {
                    // 네트워크에 해당 레디스서버 IP 와 PORT 가 없어서 연결실패 상황
                    timer?.cancel()
                    clients.forEach { client ->
                        if (client.isConnected) client.connection?.unsubscribe(client.channel)
                        client.client.shutdown()
                    }
                    clients.clear()
                    isConnecting.set(false)
                    broadcastToActivity(Extras.UNKNOWN, "fail to connect")

                    return@thread
                }.apply {
                    it.connection = this
                    statefulConnection.addListener(object :
                        RedisPubSubListener<String, String> {
                        override fun message(p0: String?, p1: String?, p2: String?) {}
                        override fun psubscribed(p0: String?, p1: Long) {}
                        override fun punsubscribed(p0: String?, p1: Long) {}

                        override fun message(channel: String, data: String) {
                            AppData.debug(TAG, "message received in RedisService - channel : ${channel}, data : $data")
                            onMessageReceived(channel, data)
                        }

                        override fun subscribed(channel: String, count: Long) {
                            it.isConnected = true
                            broadcastToActivity(channel, "$channel subscribed")
                            sendData(channel, "successfully connected. $channel - $host:$port")
                            handler.postDelayed(::checkConnection, 1000)
                        }

                        override fun unsubscribed(channel: String, count: Long) {
                            it.isConnected = false
                            broadcastToActivity(channel, "$channel unsubscribed")
                        }

                    })

                    subscribe(channelId)
                }
            }
        }
    }

    /**
     * 메시지 수신 시
     */
    private fun onMessageReceived(channel: String, data: String) {
        AppData.debug(TAG, "onMessageReceived called in RedisService.")
        broadcastToActivity(channel, data)
    }

    /**
     * 액티비티의 리시버로 보내기
     */
    private fun broadcastToActivity(channel: String, data: String) = with(Intent(AppData.ACTION_REMOTE_DATA)) {
        AppData.error(TAG, "broadcastToActivity called. channel : $channel, data : $data")
        putExtra(Extras.COMMAND, "REDIS")
        putExtra("channel", channel)
        putExtra("data", data)
        sendBroadcast(this)
    }

    /**
     * 메시지 전송 시
     */
    private fun sendData(channel: String, data: String) {
        AppData.debug(TAG, "sendData called. channel: $channel, data: $data")
        thread {
            clients.forEach {
                try {
                    if (publishConnection == null) publishConnection = it.client.connect().sync()
                    publishConnection?.publish(channel, data)
                } catch (e: RedisConnectionException) {
                    e.printStackTrace()
                } catch (e: RedisCommandTimeoutException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder =
        throw UnsupportedOperationException("Not yet implemented")
}