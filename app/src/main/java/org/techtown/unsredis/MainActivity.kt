package org.techtown.unsredis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.techtown.unsredis.databinding.ActivityMainBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val tag: String = javaClass.simpleName

    private lateinit var redisReceiver: BroadcastReceiver
    private lateinit var redisFilter: IntentFilter

    private var channelList = arrayOf("test01", "test02")

    override fun onStart() {
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called.")
        super.onStart()
        ContextCompat.registerReceiver(baseContext, redisReceiver, redisFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called.")
        unregisterReceiver(redisReceiver)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        initReceiver()

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
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(this.windowToken, 0)
                this.setText("")
            }
        }
    }

    // 리시버 초기화
    private fun initReceiver() {
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called.")
        redisFilter = IntentFilter()
        redisFilter.addAction(AppData.ACTION_REDIS_DATA)
        redisReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = setReceivedData(intent)
        }
    }

    fun setReceivedData(intent: Intent) {
        val command = intent.getStringExtra(RedisExtras.COMMAND)
        val channel = intent.getStringExtra(RedisExtras.CHANNEL)
        val data = intent.getStringExtra(RedisExtras.DATA)
        AppData.debug(tag, "$command : $channel - $data")
        printLog("$command : $channel - $data")
        data?.apply {
            when {
                startsWith("check redis connection") -> return
                startsWith("already connected") or startsWith("successfully connected") -> {
                    binding.statusText.text = getString(R.string.connected)
                    binding.idText.text = channel
                }

                endsWith("unsubscribed") -> {
                    binding.statusText.text = getString(R.string.disconnect)
                    binding.idText.text = RedisExtras.UNKNOWN
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
        // TODO: 레디스로부터 받은 메시지 처리 로직 작성
    }

    private fun connect(channel: String) {
        val host = AppData.REDIS_HOST
        val port = AppData.REDIS_PORT
        AppData.debug(tag, "connect called : $channel, $host:$port")
        binding.idText.text = channel

        Intent(this, RedisService::class.java).also {
            it.putExtra(RedisExtras.COMMAND, RedisExtras.CONNECT)
            it.putExtra(RedisExtras.REDIS_HOST, host)
            it.putExtra(RedisExtras.REDIS_PORT, port)
            it.putExtra(RedisExtras.MY_CHANNEL, channel)
            startForegroundService(it)
        }
    }

    private fun disconnect() {
        AppData.debug(tag, "disconnect called")

        Intent(this, RedisService::class.java).also {
            it.putExtra(RedisExtras.COMMAND, RedisExtras.DISCONNECT)
            startForegroundService(it)
        }
    }

    private fun sendData(channel: String, data: String) = with(Intent(this, RedisService::class.java)) {
        printLog("to $channel : $data")
        putExtra(RedisExtras.COMMAND, "send")
        putExtra(RedisExtras.CHANNEL, channel)
        putExtra(RedisExtras.DATA, data)
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