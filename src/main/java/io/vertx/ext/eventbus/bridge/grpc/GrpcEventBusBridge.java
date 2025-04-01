package io.vertx.ext.eventbus.bridge.grpc;

import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.eventbus.bridge.grpc.impl.GrpcEventBusBridgeImpl;

/**
 * gRPC EventBus bridge for Vert.x
 */
@VertxGen
public interface GrpcEventBusBridge {

  static GrpcEventBusBridge create(Vertx vertx) {
    return create(vertx, null, 0);
  }

  static GrpcEventBusBridge create(Vertx vertx, BridgeOptions options) {
    return create(vertx, options, 0);
  }

  static GrpcEventBusBridge create(Vertx vertx, BridgeOptions options, int port) {
    return create(vertx, options, port, null);
  }

  static GrpcEventBusBridge create(Vertx vertx, BridgeOptions options, int port, Handler<BridgeEvent> eventHandler) {
    return new GrpcEventBusBridgeImpl(vertx, options, port, eventHandler);
  }

  /**
   * Start listening on the port as configured when creating the server.
   *
   * @return a future of the result
   */
  Future<GrpcEventBusBridge> listen();

  /**
   * Start listening on the specified port, ignoring port configured when creating the server.
   *
   * @param port the gRPC port
   *
   * @return a future of the result
   */
  Future<GrpcEventBusBridge> listen(int port);

  /**
   * Start listening on the specified port and host, ignoring port configured when creating the server.
   *
   * @param port the gRPC port
   * @param host the host to bind to
   *
   * @return a future of the result
   */
  Future<GrpcEventBusBridge> listen(int port, String host);

  /**
   * Close the current gRPC server.
   *
   * @return a future of the result
   */
  Future<Void> close();
}
