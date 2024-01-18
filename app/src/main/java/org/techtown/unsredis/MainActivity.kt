package org.techtown.unsredis

import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import org.techtown.unsredis.databinding.ActivityMainBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val tag: String = javaClass.simpleName
    private var channel = "test01"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        initView()
    }

    private fun initView() = with(binding) {
        val method = Thread.currentThread().stackTrace[2].methodName
        AppData.debug(tag, "$method called. SDK: ${Build.VERSION.SDK_INT}")

        resultText.movementMethod = ScrollingMovementMethod()

        connectButton.setOnClickListener { connect(channel) }
        disconnectButton.setOnClickListener { disconnect() }
    }

    private fun connect(channel: String) {
        val host = AppData.REDIS_HOST
        val port = AppData.REDIS_PORT
        AppData.debug(tag, "connect called : $channel, $host:$port")

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
            it.putExtra(RedisExtras.COMMAND, RedisExtras.CONNECT)
            startForegroundService(it)
        }
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