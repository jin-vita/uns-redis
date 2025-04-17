package org.techtown.unsredis

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.text.method.ScrollingMovementMethod
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.techtown.unsredis.databinding.ActivityMainBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val tag: String = javaClass.simpleName

    private var channelList = arrayOf("test01", "test02")

    private val activityHandler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == 2) {
            val channel = msg.data.getString("channel").toString()
            val data = msg.data.getString("data").toString()
            setReceivedData(channel, data)
        }
        true
    }

    private val activityMessenger = Messenger(activityHandler)

    private var serviceMessenger: Messenger? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceMessenger = Messenger(service)

            // 메시지 보내기
            val msg = Message.obtain(null, 1, "안녕, 서비스야!")
            msg.replyTo = activityMessenger // 응답 받을 곳 지정
            serviceMessenger?.send(msg)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
        }
    }

    override fun onStart() {
        super.onStart()
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called.")

        val intent = Intent(this, RedisService::class.java)
        intent.putExtra("activityMessenger", activityMessenger)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called.")

        unbindService(connection)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        initView()
    }

    private fun initView() = with(binding) {
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called. SDK: ${Build.VERSION.SDK_INT}")

        resultText.movementMethod = ScrollingMovementMethod()

        connect1Button.setOnClickListener {
            binding.statusText.text = getString(R.string.connecting)
            connect(channelList.first())
            connect1Button.isEnabled = false
            connect2Button.isEnabled = true
        }

        connect2Button.setOnClickListener {
            binding.statusText.text = getString(R.string.connecting)
            connect(channelList.last())
            connect1Button.isEnabled = true
            connect2Button.isEnabled = false
        }

        disconnectButton.setOnClickListener {
            connect1Button.isEnabled = true
            connect2Button.isEnabled = true
            disconnect()
        }

        sendButton.setOnClickListener {
            binding.chatInput.apply {
                when {
                    connect1Button.isEnabled && connect2Button.isEnabled ->
                        AppData.showToast(this@MainActivity, "현재 연결이 없음")

                    else -> {
                        val channel = if (connect1Button.isEnabled) channelList.first() else channelList.last()
                        sendData(channel, this.text.toString().trim())
                    }
                }
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(this.windowToken, 0)
                this.setText("")
            }
        }
    }

    fun setReceivedData(channel: String, data: String) {
        AppData.debug(tag, "setReceivedData, $channel - $data")
        printLog("$channel - $data")
        data.apply {
            when {
                startsWith("check redis connection") -> return
                startsWith("already connected") or startsWith("successfully connected") -> {
                    binding.statusText.text = getString(R.string.connected)
                    binding.idText.text = channel
                }

                endsWith("unsubscribed") -> {
                    binding.statusText.text = getString(R.string.disconnect)
                    binding.idText.text = Extras.UNKNOWN
                }

                equals("fail to connect") ->
                    binding.statusText.text = getString(R.string.fail_to_connect)

                equals("fail to reconnect") ->
                    binding.statusText.text = getString(R.string.fail_to_reconnect)

                else -> setData(this)
            }
        }
    }

    private fun setData(data: String) {
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called. data: $data")
        // TODO: 받은 메시지 처리 로직 작성
    }

    private fun connect(channel: String) {
        val host = AppData.REDIS_HOST
        val port = AppData.REDIS_PORT
        AppData.debug(tag, "connect called : $channel, $host:$port")
        binding.idText.text = channel

        Intent(this, RedisService::class.java).also {
            it.putExtra(Extras.COMMAND, Extras.CONNECT)
            it.putExtra(Extras.REDIS_HOST, host)
            it.putExtra(Extras.REDIS_PORT, port)
            it.putExtra(Extras.MY_CHANNEL, channel)
            startForegroundService(it)
        }
    }

    private fun disconnect() {
        AppData.debug(tag, "disconnect called")

        Intent(this, RedisService::class.java).also {
            it.putExtra(Extras.COMMAND, Extras.DISCONNECT)
            startForegroundService(it)
        }
    }

    private fun sendData(channel: String, data: String) = with(Intent(this, RedisService::class.java)) {
        printLog("to $channel : $data")
        putExtra(Extras.COMMAND, Extras.SEND)
        putExtra(Extras.CHANNEL, channel)
        putExtra(Extras.DATA, data)
        startForegroundService(this)
    }

    private fun printLog(message: String) = runOnUiThread {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        val now = LocalDateTime.now().format(formatter)
        val log = "[$now] $message"
        if (AppData.logList.size > 1000) AppData.logList.removeAt(1)
        AppData.logList.add(log)
        val sb = StringBuilder()
        AppData.logList.forEach { sb.appendLine(it) }
        binding.resultText.text = sb
        moveToBottom(binding.resultText)
    }

    private fun moveToBottom(textView: TextView) = textView.post {
        val scrollAmount = try {
            textView.layout.getLineTop(textView.lineCount) - textView.height
        } catch (_: NullPointerException) {
            0
        }
        if (scrollAmount > 0) textView.scrollTo(0, scrollAmount)
        else textView.scrollTo(0, 0)
    }
}