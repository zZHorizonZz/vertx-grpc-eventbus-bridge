package io.vertx.ext.eventbus.bridge.grpc.impl;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.eventbus.bridge.grpc.BridgeEvent;
import io.vertx.ext.eventbus.bridge.grpc.GrpcEventBusBridge;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpc.server.GrpcServerOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Implementation of the GrpcEventBusBridge interface.
 * <p>
 * This class sets up a gRPC server that bridges the Vert.x EventBus to external clients. It allows external applications to communicate with the Vert.x EventBus through gRPC,
 * enabling cross-platform and cross-language communication.
 * <p>
 * The bridge supports operations like publishing messages, sending requests, subscribing to addresses, and handling responses from the EventBus.
 */
public class GrpcEventBusBridgeImpl implements GrpcEventBusBridge {

    private static final Logger log = LoggerFactory.getLogger(GrpcEventBusBridgeImpl.class);

    private final Vertx vertx;
    private final EventBus eb;
    private final BridgeOptions options;
    private final Handler<BridgeEvent> bridgeEventHandler;
    private final int port;
    private HttpServer server;
    private final Map<String, Pattern> compiledREs = new HashMap<>();

    public GrpcEventBusBridgeImpl(Vertx vertx, BridgeOptions options, int port, Handler<BridgeEvent> eventHandler) {
        this.vertx = vertx;
        this.eb = vertx.eventBus();
        this.options = options != null ? options : new BridgeOptions();
        this.bridgeEventHandler = eventHandler;
        this.port = port;
    }

    public GrpcEventBusBridgeImpl(Vertx vertx, BridgeOptions options, int port) {
        this(vertx, options, port, null);
    }

    @Override
    public Future<GrpcEventBusBridge> listen() {
        return listen(port);
    }

    @Override
    public Future<GrpcEventBusBridge> listen(int port) {
        return listen(port, "0.0.0.0");
    }

    @Override
    public Future<GrpcEventBusBridge> listen(int port, String host) {
        Promise<GrpcEventBusBridge> promise = Promise.promise();

        try {
            // Create the EventBus bridge service that will handle gRPC requests
            EventBusBridgeService service = new EventBusBridgeService(eb, options, bridgeEventHandler, compiledREs);

            // Configure the HTTP server options
            HttpServerOptions serverOptions = new HttpServerOptions()
                    .setPort(port)
                    .setHost(host);

            // Create a gRPC server with gRPC-Web support enabled
            GrpcServer grpcServer = GrpcServer.server(vertx, new GrpcServerOptions().setGrpcWebEnabled(true));

            // Bind the service to the gRPC server
            service.bind(grpcServer);

            // Create and start the HTTP server
            server = vertx.createHttpServer(serverOptions);
            server.requestHandler(grpcServer);
            server.listen().onComplete(res -> {
                if (res.succeeded()) {
                    log.info("gRPC EventBus Bridge listening on " + host + ":" + port);
                    promise.complete(this);
                } else {
                    log.error("Failed to start gRPC server", res.cause());
                    promise.fail(res.cause());
                }
            });
        } catch (Exception e) {
            log.error("Error setting up gRPC server", e);
            promise.fail(e);
        }

        return promise.future();
    }

    @Override
    public Future<Void> close() {
        Promise<Void> promise = Promise.promise();
        if (server != null) {
            // Close the HTTP server if it exists
            server.close().onComplete(res -> {
                if (res.succeeded()) {
                    log.info("gRPC EventBus Bridge server closed successfully");
                    promise.complete();
                } else {
                    log.error("Error shutting down gRPC server", res.cause());
                    promise.fail(res.cause());
                }
            });
        } else {
            // No server to close, complete immediately
            promise.complete();
        }
        return promise.future();
    }
}
