package de.kiwi.mjml

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.io.Resource
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import org.graalvm.polyglot.Context as JsContext
import org.graalvm.polyglot.Engine as JsEngine
import org.graalvm.polyglot.PolyglotException as JsException
import org.graalvm.polyglot.Source as JsSource
import org.graalvm.polyglot.Value as JsValue
import org.graalvm.polyglot.proxy.ProxyExecutable as JsExecutable
import org.graalvm.polyglot.proxy.ProxyObject as JsObject

private val log = LoggerFactory.getLogger(JsMjmlCompiler::class.java)!!

/**
 * Compiles MJML using a pool of worker threads, each owning a JS context with the MJML compiler loaded.
 * JS contexts are single-threaded, so compile-requests are queued and processed by the next idle worker.
 * Workers load the MJML compiler in the background, so the application startup is not blocked;
 * requests submitted in the meantime stay queued until the first worker is ready.
 */
internal class JsMjmlCompiler(
    private val jsResource: Resource,
    private val fonts: Map<String, String>,
    private val poolSize: Int = 1,
    private val shutdownTimeout: Duration = 5.seconds,
) : MjmlCompiler, InitializingBean, Closeable {
    private class CompileJob(val mjml: String) {
        val result = CompletableFuture<String>()
    }

    private val jobs = LinkedBlockingQueue<CompileJob>()
    private val activeWorkers = AtomicInteger()

    // Set once no worker is able to process jobs anymore, i.e. all failed to load or the compiler was closed
    private val failure = AtomicReference<MjmlException>()

    // The engine is shared by all contexts of the pool, so the MJML compiler source is only parsed once
    private lateinit var engine: JsEngine
    private lateinit var source: JsSource
    private lateinit var workers: List<Thread>

    override fun afterPropertiesSet() {
        check(poolSize >= 1) { "MJML compiler pool size must be at least 1, but was $poolSize" }

        engine = JsEngine
            .newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        source = JsSource
            .newBuilder("js", jsResource.getContentAsString(Charsets.UTF_8), jsResource.filename ?: "mjml.js")
            .build()

        log.info("Loading MJML compiler from $jsResource with $poolSize worker(s) ...")
        activeWorkers.set(poolSize)
        workers = List(poolSize) { index ->
            Thread.ofPlatform()
                .name("mjml-compiler-$index")
                .daemon()
                .start(::runWorker)
        }
    }

    override fun close() {
        if (!::workers.isInitialized) return

        // Set failure to be thrown if compilation is attempted after this point
        setFailure(MjmlException("MJML compiler has been closed"))

        // Idle workers stop immediately, busy workers stop after completing their current job
        workers.forEach(Thread::interrupt)
        workers.forEach { it.join(shutdownTimeout.inWholeMilliseconds) }

        // Cancels any execution of workers, which did not stop in time
        engine.close(/* cancelIfExecuting = */ true)
    }

    override fun compile(mjml: String): String {
        val job = CompileJob(mjml)
        jobs.put(job)

        // Jobs queued after the last worker has stopped would never be processed
        failure.get()?.let { failPendingJobs(it) }

        try {
            return job.result.get()
        } catch (ex: ExecutionException) {
            val cause = ex.cause!!
            throw cause as? MjmlException ?: MjmlException(cause.message ?: cause.toString(), cause)
        } catch (ex: InterruptedException) {
            jobs.remove(job)
            Thread.currentThread().interrupt()
            throw MjmlException("Interrupted while waiting for MJML compilation", ex)
        }
    }

    private fun runWorker() {
        val workerName = Thread.currentThread().name
        try {
            JsContext.newBuilder("js").engine(engine).build().use { context ->
                val (mjml2html, loadTime) = measureTimedValue { loadCompiler(context) }
                log.debug("Finished loading MJML compiler (worker '{}') in {}", workerName, loadTime)

                while (true) {
                    val job = jobs.take()
                    if (job.result.isDone) continue
                    try {
                        job.result.complete(executeCompilation(mjml2html, job.mjml))
                    } catch (ex: Exception) {
                        job.result.completeExceptionally(ex)
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Worker has been stopped by close()
        } catch (ex: Exception) {
            if (failure.get() == null) {
                log.error("MJML compiler worker '$workerName' failed", ex)
            }
        } finally {
            if (activeWorkers.decrementAndGet() == 0) {
                setFailure(MjmlException("No MJML compiler worker available, see log for details"))
            }
        }
    }

    private fun loadCompiler(context: JsContext): JsValue {
        context.eval("js", "var window = globalThis;")
        context.eval(source)
        return context.eval("js", "MJML.mjml2html")
    }

    private fun executeCompilation(mjml2html: JsValue, mjml: String): String {
        try {
            val fontsProxy = JsObject.fromMap(fonts)
            val optionsProxy = JsObject.fromMap(mapOf("fonts" to fontsProxy))

            // Since MJML 5, mjml2html returns a Promise, which settles synchronously because GraalJS
            // drains its job queue before control returns to the host
            val promise = mjml2html.execute(mjml, optionsProxy)
            var result: JsValue? = null
            var error: JsValue? = null
            promise.invokeMember(
                "then",
                JsExecutable { args -> result = args.first(); null },
                JsExecutable { args -> error = args.first(); null },
            )

            error?.let { throw MjmlException(it.toString()) }
            val html = checkNotNull(result) { "MJML compilation did not settle synchronously" }
            return html.getMember("html").asString()
        } catch (ex: JsException) {
            throw MjmlException(ex.message ?: "JsException", ex)
        }
    }

    private fun setFailure(exception: MjmlException) {
        failure.compareAndSet(null, exception)
        failPendingJobs(failure.get())
    }

    private fun failPendingJobs(exception: MjmlException) {
        generateSequence { jobs.poll() }.forEach { it.result.completeExceptionally(exception) }
    }
}
