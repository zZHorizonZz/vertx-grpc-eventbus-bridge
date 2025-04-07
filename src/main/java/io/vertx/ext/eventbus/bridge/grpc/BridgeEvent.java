package io.vertx.ext.eventbus.bridge.grpc;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.json.JsonObject;

/**
 * Represents an event that occurs on the gRPC event bus bridge.
 * <p>
 * Bridge events are generated when clients interact with the EventBus through the gRPC bridge.
 * These events can be used to implement custom authorization logic, logging, or monitoring.
 * <p>
 * Events include:
 * <ul>
 *   <li>SEND - When a client sends a message to an address</li>
 *   <li>PUBLISH - When a client publishes a message to an address</li>
 *   <li>RECEIVE - When a client receives a message from an address it has subscribed to</li>
 *   <li>REGISTER - When a client registers a handler for an address</li>
 *   <li>UNREGISTER - When a client unregisters a handler for an address</li>
 * </ul>
 * <p>
 * Each event can be allowed or denied by calling the {@code setHandler} method.
 */
@VertxGen
public interface BridgeEvent extends io.vertx.ext.bridge.BaseBridgeEvent {

  /**
   * Sets the raw JSON message for this bridge event.
   * <p>
   * The raw message contains the actual data being sent over the EventBus.
   * This method allows modifying the message content before it's processed,
   * which can be useful for message transformation or content filtering.
   *
   * @param message the raw JSON message to set
   * @return this reference, so it can be used fluently in method chaining
   */
  @Fluent
  BridgeEvent setRawMessage(JsonObject message);
}
