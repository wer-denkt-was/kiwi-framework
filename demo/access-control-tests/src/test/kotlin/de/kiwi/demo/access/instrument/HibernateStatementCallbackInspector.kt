package de.kiwi.demo.access.instrument

import de.kiwi.api.util.slf4j
import de.kiwi.demo.access.instrument.HibernateStatementCallbackInspector.CallbackScope
import de.kiwi.demo.access.instrument.HibernateStatementCallbackInspector.UnregisterCallback
import org.hibernate.resource.jdbc.spi.StatementInspector
import java.io.Closeable

private val log = slf4j<HibernateStatementCallbackInspector>()

class HibernateStatementCallbackInspector : StatementInspector, Closeable {
    companion object {
        private val holder = InheritableThreadLocal<HibernateStatementCallbackInspector?>()

        fun registerCallback(callback: Callback): UnregisterCallback {
            val instance = holder.get()
            if (instance == null) {
                log.warn("No instance of HibernateStatementCallbackInspector available!")
                return UnregisterCallback {}
            }

            synchronized(instance.callbacks) { instance.callbacks.add(callback) }
            return UnregisterCallback {
                synchronized(instance.callbacks) { instance.callbacks.remove(callback) }
            }
        }

        fun <T> withCallback(callback: Callback) = CallbackScope<T> { block ->
            val unregister = registerCallback(callback)
            try {
                block()
            } finally {
                unregister()
            }
        }
    }

    private val callbacks = LinkedHashSet<Callback>()
    private val inInspection = ThreadLocal.withInitial { false }

    init {
        holder.set(this)
    }

    override fun close() {
        holder.remove()
    }

    override fun inspect(sql: String): String {
        callbacks.forEach { callback ->
            // Prevent recursive callbacks
            if (!inInspection.get()) {
                inInspection.set(true)
                callback.onStatement(sql)
                inInspection.set(false)
            }
        }
        return sql
    }

    fun interface Callback {
        fun onStatement(sql: String)
    }

    fun interface CallbackScope<T> {
        operator fun invoke(block: () -> T): T
    }

    fun interface UnregisterCallback {
        operator fun invoke()
    }
}
