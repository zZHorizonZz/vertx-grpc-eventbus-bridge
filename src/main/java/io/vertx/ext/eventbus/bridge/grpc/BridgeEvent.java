package io.vertx.ext.eventbus.bridge.grpc;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.json.JsonObject;

/**
 * Represents an event that occurs on the gRPC event bus bridge.
 * <p>
 * Please consult the documentation for a full explanation.
 */
@VertxGen
public interface BridgeEvent extends io.vertx.ext.bridge.BaseBridgeEvent {

  /**
   * Get the raw JSON message for the event. This will be null for SOCKET_CREATED or SOCKET_CLOSED events as there is
   * no message involved.
   *
   * @param message the raw message
   * @return this reference, so it can be used fluently
   */
  @Fluent
  BridgeEvent setRawMessage(JsonObject message);
}