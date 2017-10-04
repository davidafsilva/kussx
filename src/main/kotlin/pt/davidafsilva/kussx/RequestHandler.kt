package pt.davidafsilva.kussx

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.http.HttpHeaders
import io.vertx.core.json.JsonObject
import io.vertx.redis.RedisOptions
import io.vertx.rxjava.core.Vertx
import io.vertx.rxjava.ext.web.RoutingContext
import io.vertx.rxjava.redis.RedisClient
import org.slf4j.LoggerFactory
import pt.davidafsilva.hashids.Hashids
import rx.Single
import rx.schedulers.Schedulers
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.Executors

/**
 * The API request handler
 *
 * @author david
 */
class RequestHandler(vertx: Vertx, private val config: Configuration) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val incrExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4)

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

    fun handleIdDecodeRequest(context: RoutingContext) {
        val key = context.pathParam("key")
        redisClient?.run {
            rxGet(key)
                    .flatMap {
                        if (it == null) Single.error(RuntimeException("no suck mapping"))
                        else Single.just(it)
                    }
                    .map { JsonObject(it) }
                    .doOnSuccess {
                        // redirect the request
                        context.response()
                                .setStatusCode(HttpResponseStatus.TEMPORARY_REDIRECT.code())
                                .putHeader(HttpHeaders.LOCATION.toString(), it.getString("url", ""))
                                .end()

                        // increment the access counter in the backgroun
                        rxSet(key, it.put("access", it.getLong("access", 0L) + 1).encode())
                                .subscribeOn(Schedulers.from(incrExecutor))
                                .subscribe()
                    }
                    .doOnError { context.fail(HttpResponseStatus.NOT_FOUND.code()) }
                    .subscribe()
        }
    }

    fun handleIdCreationRequest(context: RoutingContext) {
        val url: String? = context.bodyAsJson.getString("url", "")
        when {
            url.isNullOrEmpty() -> context.fail(HttpResponseStatus.BAD_REQUEST.code())
            else -> {
                redisClient?.apply {
                    rxIncr("shorten-counter")
                            .map { hashids?.encode(it) }
                            .flatMap { k -> rxExists(k).map { Pair(it, k) } }
                            .flatMap {
                                if (it.first == 0L) Single.just(it.second)
                                else Single.error(DuplicateKeyException())
                            }
                            .retry { _, e -> e is DuplicateKeyException }
                            .flatMap { k ->
                                val json = JsonObject()
                                        .put("url", url)
                                        .put("created", Instant.now())
                                        .put("access", 0L)
                                        .encode()
                                rxSet(k, json).map { JsonObject().put("key", k) }
                            }
                            .doOnSuccess { context.response().end(it.encode()) }
                            .doOnError { context.fail(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()) }
                            .subscribe()
                }
            }
        }
    }

    fun handleIdDeletionRequest(context: RoutingContext) {
        val key = context.pathParam("key")
        redisClient?.run {
            rxDel(key)
                    .doOnSuccess { context.response().end() }
                    .doOnError { context.fail(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()) }
                    .subscribe()
        }
    }

    fun close(completer: Handler<AsyncResult<Void>>?) {
        redisClient?.close(completer)
    }

    private class DuplicateKeyException : RuntimeException()
}
