package pt.davidafsilva.kussx

import io.vertx.core.Future
import io.vertx.core.http.HttpServerOptions
import io.vertx.rxjava.core.AbstractVerticle
import io.vertx.rxjava.ext.web.Router
import io.vertx.rxjava.ext.web.handler.BodyHandler
import org.slf4j.LoggerFactory

/**
 * This verticle shall expose our REST API and inter-op with our data store, accordingly.
 *
 * @author david
 */
class RestVerticle : AbstractVerticle() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private var requestHandler: RequestHandler? = null

    override fun start(startFuture: Future<Void>?) {
        val configuration = Configuration(config())

        // request handler
        requestHandler = RequestHandler(vertx, configuration)

        // options
        val options = HttpServerOptions().apply {
            port = configuration.getInt("KUSSX_API_PORT", 8080)
            host = configuration.getStr("KUSSX_API_HOST", "0.0.0.0")
        }
        // routing
        val router = Router.router(vertx).also {
            it.get("/:key/info").handler(requestHandler!!::keyInformation)
            it.get("/:key").handler(requestHandler!!::redirectWithKey)
            it.post("/shorten").handler(BodyHandler.create())
            it.post("/shorten")
                    .consumes("application/json")
                    .produces("application/json")
                    .handler(requestHandler!!::createKey)
            it.delete("/:key")
                    .handler(requestHandler!!::deleteKey)
        }
        // create http server
        vertx.createHttpServer(options)
                .requestHandler(router::accept)
                .listen({ result ->
                    if (result.succeeded()) {
                        logger.info("API server deployed at {}:{}", options.host, result.result().actualPort())
                        startFuture?.complete()
                    } else startFuture?.fail(result.cause())
                })
    }

    override fun stop(stopFuture: Future<Void>?) {
        requestHandler?.close(stopFuture?.completer())
    }
}
