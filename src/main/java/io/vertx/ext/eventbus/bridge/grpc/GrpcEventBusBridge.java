package io.vertx.ext.eventbus.bridge.grpc;

import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.eventbus.bridge.grpc.impl.GrpcEventBusBridgeImpl;

/**
 * gRPC EventBus bridge for Vert.x.
 * 
 * This interface provides methods to create and manage a gRPC server that bridges
 * the Vert.x EventBus to external clients using gRPC protocol. It allows external
 * applications to communicate with the Vert.x EventBus through gRPC, enabling
 * cross-platform and cross-language communication.
 * 
 * The bridge supports operations like publishing messages, sending requests,
 * subscribing to addresses, and handling responses from the EventBus.
 */
@VertxGen
public interface GrpcEventBusBridge {

  /**
   * Creates a new gRPC EventBus bridge with default options and port.
   * 
   * @param vertx the Vert.x instance to use
   * @return a new instance of GrpcEventBusBridge
   */
  static GrpcEventBusBridge create(Vertx vertx) {
    return create(vertx, null, 0);
  }

  /**
   * Creates a new gRPC EventBus bridge with the specified bridge options and default port.
   * 
   * @param vertx the Vert.x instance to use
   * @param options the bridge options for controlling access to the EventBus
   * @return a new instance of GrpcEventBusBridge
   */
  static GrpcEventBusBridge create(Vertx vertx, BridgeOptions options) {
    return create(vertx, options, 0);
  }

  /**
   * Creates a new gRPC EventBus bridge with the specified bridge options and port.
   * 
   * @param vertx the Vert.x instance to use
   * @param options the bridge options for controlling access to the EventBus
   * @param port the port on which the gRPC server will listen
   * @return a new instance of GrpcEventBusBridge
   */
  static GrpcEventBusBridge create(Vertx vertx, BridgeOptions options, int port) {
    return create(vertx, options, port, null);
  }

  /**
   * Creates a new gRPC EventBus bridge with the specified bridge options, port, and event handler.
   * This is the most configurable factory method that all other factory methods delegate to.
   * 
   * @param vertx the Vert.x instance to use
   * @param options the bridge options for controlling access to the EventBus
   * @param port the port on which the gRPC server will listen
   * @param eventHandler a handler for bridge events that can be used to implement custom security logic
   * @return a new instance of GrpcEventBusBridge
   */
  static GrpcEventBusBridge create(Vertx vertx, BridgeOptions options, int port, Handler<BridgeEvent> eventHandler) {
    return new GrpcEventBusBridgeImpl(vertx, options, port, eventHandler);
  }

  /**
   * Start listening on the port as configured when creating the server.
   * This method starts the gRPC server on the port specified during bridge creation.
   * If no port was specified (or port was 0), a random available port will be chosen.
   *
   * @return a future that will be completed when the server has been started, with this instance as the result
   */
  Future<GrpcEventBusBridge> listen();

  /**
   * Start listening on the specified port, ignoring port configured when creating the server.
   * This method allows overriding the port that was specified during bridge creation.
   *
   * @param port the gRPC port to listen on (0 means choose a random available port)
   *
   * @return a future that will be completed when the server has been started, with this instance as the result
   */
  Future<GrpcEventBusBridge> listen(int port);

  /**
   * Start listening on the specified port and host, ignoring port configured when creating the server.
   * This method allows overriding both the port and host that were specified during bridge creation.
   *
   * @param port the gRPC port to listen on (0 means choose a random available port)
   * @param host the host to bind to (e.g., "localhost" for local connections only, "0.0.0.0" for all network interfaces)
   *
   * @return a future that will be completed when the server has been started, with this instance as the result
   */
  Future<GrpcEventBusBridge> listen(int port, String host);

  /**
   * Close the current gRPC server.
   * This method stops the gRPC server and releases all resources associated with it.
   * After calling this method, the bridge will no longer accept new connections,
   * but existing connections may continue to operate until they are closed.
   *
   * @return a future that will be completed when the server has been closed
   */
  Future<Void> close();
}
