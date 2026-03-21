package dev.jdtech.mpv

import android.content.Context
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

class MpvPlayer private constructor() : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("mpv")
            System.loadLibrary("player")
        }

        private val instance = AtomicReference<MpvPlayer?>(null)

        suspend fun create(
            context: Context,
            configure: MpvPlayerConfig.() -> Unit = {},
        ): MpvPlayer = withContext(Dispatchers.IO) {
            val player = MpvPlayer()
            check(instance.compareAndSet(null, player)) {
                "An MpvPlayer instance is already active. Close it before creating a new one."
            }
            try {
                nativeCreate(context.applicationContext)
                MpvPlayerConfig().apply(configure)
                nativeInit()
                player
            } catch (e: Throwable) {
                try { nativeDestroy() } catch (_: Throwable) {}
                instance.set(null)
                throw e
            }
        }

        // JNI callbacks — called from native event thread

        @JvmStatic
        fun onPropertyChanged(name: String) {
            instance.get()?.propertyChanges?.tryEmit(PropertyChange.None(name))
        }

        @JvmStatic
        fun onPropertyChanged(name: String, value: Boolean) {
            instance.get()?.propertyChanges?.tryEmit(PropertyChange.Flag(name, value))
        }

        @JvmStatic
        fun onPropertyChanged(name: String, value: Long) {
            instance.get()?.propertyChanges?.tryEmit(PropertyChange.Int64(name, value))
        }

        @JvmStatic
        fun onPropertyChanged(name: String, value: Double) {
            instance.get()?.propertyChanges?.tryEmit(PropertyChange.Double(name, value))
        }

        @JvmStatic
        fun onPropertyChanged(name: String, value: String) {
            instance.get()?.propertyChanges?.tryEmit(
                PropertyChange.Str(name, sanitizeString(value)),
            )
        }

        @JvmStatic
        fun onEvent(eventId: Int) {
            val event = MpvEvent.fromId(eventId) ?: return
            instance.get()?.events?.tryEmit(event)
        }

        @JvmStatic
        fun onEndFile(reason: Int) {
            instance.get()?.events?.tryEmit(
                MpvEvent.EndFile(EndFileReason.fromId(reason)),
            )
        }

        @JvmStatic
        fun onLogMessage(prefix: String, level: Int, text: String) {
            val logLevel = LogLevel.fromNative(level) ?: return
            instance.get()?.logMessages?.tryEmit(
                LogMessage(prefix, logLevel, sanitizeString(text).trimEnd()),
            )
        }

        private fun sanitizeString(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                        sb.append(c)
                        sb.append(s[i + 1])
                        i += 2
                    } else {
                        sb.append('\uFFFD')
                        i++
                    }
                } else if (c.isLowSurrogate()) {
                    sb.append('\uFFFD')
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        // JNI native declarations

        @JvmStatic internal external fun nativeCreate(appctx: Context)
        @JvmStatic internal external fun nativeInit()
        @JvmStatic internal external fun nativeDestroy()
        @JvmStatic internal external fun nativeCommand(cmd: Array<out String>)
        @JvmStatic internal external fun nativeSetOptionString(name: String, value: String): Int
        @JvmStatic internal external fun nativeAttachSurface(surface: Surface)
        @JvmStatic internal external fun nativeDetachSurface()
        @JvmStatic internal external fun nativeGetPropertyInt(name: String): Int?
        @JvmStatic internal external fun nativeGetPropertyDouble(name: String): Double?
        @JvmStatic internal external fun nativeGetPropertyBoolean(name: String): Boolean?
        @JvmStatic internal external fun nativeGetPropertyString(name: String): String?
        @JvmStatic internal external fun nativeSetPropertyInt(name: String, value: Int)
        @JvmStatic internal external fun nativeSetPropertyDouble(name: String, value: Double)
        @JvmStatic internal external fun nativeSetPropertyBoolean(name: String, value: Boolean)
        @JvmStatic internal external fun nativeSetPropertyString(name: String, value: String)
        @JvmStatic internal external fun nativeObserveProperty(name: String, format: Int)
    }

    private val events = MutableSharedFlow<MpvEvent>(extraBufferCapacity = 64)
    private val propertyChanges = MutableSharedFlow<PropertyChange>(extraBufferCapacity = 64)
    private val logMessages = MutableSharedFlow<LogMessage>(extraBufferCapacity = 64)

    val eventFlow: SharedFlow<MpvEvent> = events.asSharedFlow()
    val propertyFlow: SharedFlow<PropertyChange> = propertyChanges.asSharedFlow()
    val logFlow: SharedFlow<LogMessage> = logMessages.asSharedFlow()

    // Commands

    suspend fun command(vararg args: String) {
        checkNotClosed()
        withContext(Dispatchers.IO) { nativeCommand(args) }
    }

    // Surface — not suspend, called from SurfaceHolder.Callback

    fun attachSurface(surface: Surface) {
        checkNotClosed()
        nativeAttachSurface(surface)
    }

    fun detachSurface() {
        checkNotClosed()
        nativeDetachSurface()
    }

    // Property getters

    suspend fun getInt(name: String): Int? {
        checkNotClosed()
        return withContext(Dispatchers.IO) { nativeGetPropertyInt(name) }
    }

    suspend fun getDouble(name: String): Double? {
        checkNotClosed()
        return withContext(Dispatchers.IO) { nativeGetPropertyDouble(name) }
    }

    suspend fun getFlag(name: String): Boolean? {
        checkNotClosed()
        return withContext(Dispatchers.IO) { nativeGetPropertyBoolean(name) }
    }

    suspend fun getString(name: String): String? {
        checkNotClosed()
        return withContext(Dispatchers.IO) { nativeGetPropertyString(name) }
    }

    // Property setters

    suspend fun setProperty(name: String, value: Int) {
        checkNotClosed()
        withContext(Dispatchers.IO) { nativeSetPropertyInt(name, value) }
    }

    suspend fun setProperty(name: String, value: Double) {
        checkNotClosed()
        withContext(Dispatchers.IO) { nativeSetPropertyDouble(name, value) }
    }

    suspend fun setProperty(name: String, value: Boolean) {
        checkNotClosed()
        withContext(Dispatchers.IO) { nativeSetPropertyBoolean(name, value) }
    }

    suspend fun setProperty(name: String, value: String) {
        checkNotClosed()
        withContext(Dispatchers.IO) { nativeSetPropertyString(name, value) }
    }

    // Property observation

    fun observeProperty(name: String, format: PropertyFormat): Flow<PropertyChange> {
        checkNotClosed()
        nativeObserveProperty(name, format.nativeValue)
        return propertyFlow.filter { it.name == name }
    }

    fun observeFlag(name: String): Flow<Boolean> {
        checkNotClosed()
        nativeObserveProperty(name, PropertyFormat.Flag.nativeValue)
        return propertyFlow
            .filterIsInstance<PropertyChange.Flag>()
            .filter { it.name == name }
            .map { it.value }
    }

    fun observeInt(name: String): Flow<Long> {
        checkNotClosed()
        nativeObserveProperty(name, PropertyFormat.Int64.nativeValue)
        return propertyFlow
            .filterIsInstance<PropertyChange.Int64>()
            .filter { it.name == name }
            .map { it.value }
    }

    fun observeDouble(name: String): Flow<Double> {
        checkNotClosed()
        nativeObserveProperty(name, PropertyFormat.Double.nativeValue)
        return propertyFlow
            .filterIsInstance<PropertyChange.Double>()
            .filter { it.name == name }
            .map { it.value }
    }

    fun observeString(name: String): Flow<String> {
        checkNotClosed()
        nativeObserveProperty(name, PropertyFormat.String.nativeValue)
        return propertyFlow
            .filterIsInstance<PropertyChange.Str>()
            .filter { it.name == name }
            .map { it.value }
    }

    // Lifecycle

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        nativeDestroy()
        instance.set(null)
    }

    private fun checkNotClosed() {
        check(!closed) { "MpvPlayer has been closed" }
    }
}
