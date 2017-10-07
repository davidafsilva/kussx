package pt.davidafsilva.kussx

import io.netty.handler.codec.http.HttpResponseStatus.*
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.http.HttpHeaders
import io.vertx.core.json.JsonObject
import io.vertx.redis.RedisOptions
import io.vertx.rxjava.core.Vertx
import io.vertx.rxjava.ext.web.RoutingContext
import io.vertx.rxjava.redis.RedisClient
import org.slf4j.LoggerFactory.getLogger
import pt.davidafsilva.hashids.Hashids
import rx.Single
import rx.schedulers.Schedulers
import java.lang.Runtime.getRuntime
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.Executors.newFixedThreadPool

/**
 * The API request handler
 *
 * @author david
 */
class RequestHandler(vertx: Vertx, private val config: Configuration) {

    private val logger = getLogger(javaClass)
    private val incrExecutor = newFixedThreadPool(getRuntime().availableProcessors() * 4)

    private var hashids: Hashids? = null
    private var redisClient: RedisClient? = null

    init {
        // hashids
        val salt = config.getStr("KUSSX_SALT", {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            logger.debug("used {} as salt", b)
            b.toString()
        })
        hashids = Hashids.newInstance(salt)

        // redis client
        redisClient = RedisClient.create(vertx, RedisOptions().apply {
            this.auth = config.getStr("KUSSX_REDIS_AUTH_PASSWORD")
            this.host = config.getStr("KUSSX_REDIS_HOST", "localhost")
            this.port = config.getInt("KUSSX_REDIS_PORT", 6379)
        })
    }

    fun keyInformation(context: RoutingContext) {
        getKey(context.pathParam("key")).subscribe(
                { context.response().end(it.encode()) },
                { logger.error("unable obtain key", it); context.fail(NOT_FOUND.code()) }
        )
    }

    fun redirectWithKey(context: RoutingContext) {
        val key = context.pathParam("key")
        getKey(key).subscribe({
            // redirect the request
            context.response()
                    .setStatusCode(TEMPORARY_REDIRECT.code())
                    .putHeader(HttpHeaders.LOCATION.toString(), it.getString("url", ""))
                    .end()

            // increment the access counter in the background
            redisClient!!.rxSet(key, it.put("access", it.getLong("access", 0L) + 1).encode())
                    .subscribeOn(Schedulers.from(incrExecutor))
                    .subscribe()
        }, { logger.error("unable redirect key", it); context.fail(NOT_FOUND.code()) })
    }

    fun createKey(context: RoutingContext) {
        val url: String? = context.bodyAsJson.getString("url", "")
        when {
            url.isNullOrEmpty() -> context.fail(BAD_REQUEST.code())
            else -> {
                redisClient?.apply {
                    // get the current counter value and encode it
                    rxIncr("shorten-counter")
                            .map { hashids!!.encode(it) }
                            // check for duplicates (misread counters) and retry
                            .flatMap { k -> rxExists(k).map { Pair(it, k) } }
                            .flatMap {
                                if (it.first == 0L) Single.just(it.second)
                                else Single.error(DuplicateKeyException())
                            }
                            .retry { _, e -> e is DuplicateKeyException }
                            // create the new mapping
                            .flatMap { k ->
                                val json = JsonObject()
                                        .put("url", url)
                                        .put("created", Instant.now())
                                        .put("access", 0L)
                                        .encode()
                                rxSet(k, json).map { JsonObject().put("key", k) }
                            }
                            .subscribe(
                                    { context.response().end(it.encode()) },
                                    {
                                        logger.error("unable create key", it)
                                        context.fail(INTERNAL_SERVER_ERROR.code())
                                    }
                            )
                }
            }
        }
    }

    fun deleteKey(context: RoutingContext) {
        val key = context.pathParam("key")
        redisClient?.run {
            rxDel(key).subscribe(
                    { context.response().end() },
                    { logger.error("unable delete key", it); context.fail(INTERNAL_SERVER_ERROR.code()) }
            )
        }
    }


    fun close(completer: Handler<AsyncResult<Void>>?) {
        redisClient?.close(completer)
    }

    private fun getKey(key: String): Single<JsonObject> {
        return redisClient?.let {
            it.rxGet(key)
                    .flatMap {
                        if (it.isNullOrEmpty()) Single.error(RuntimeException("no such key: $key"))
                        else Single.just(JsonObject(it))
                    }
        } ?: Single.error(RuntimeException("no client available"))
    }

    private class DuplicateKeyException : RuntimeException()
}
