package io.vertx.ext.eventbus.bridge.grpc;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.bridge.PermittedOptions;

/**
 * A simple Vert.x server that exposes the event bus over gRPC.
 */
public class GrpcEventBusServer extends AbstractVerticle {

  private GrpcEventBusBridge bridge;
  private final int port;

  public GrpcEventBusServer(int port) {
    this.port = port;
  }

  @Override
  public void start(Promise<Void> startPromise) {
    // Register some event bus handlers
    vertx.eventBus().consumer("hello", msg -> {
      JsonObject request = (JsonObject) msg.body();
      String name = request.getString("name", "World");
      msg.reply(new JsonObject().put("message", "Hello " + name));
    });

    vertx.eventBus().consumer("echo", msg -> {
      msg.reply(msg.body());
    });

    // Publish periodic messages to the "news" address
    vertx.setPeriodic(1000, id -> {
      vertx.eventBus().publish("news", 
        new JsonObject()
          .put("timestamp", System.currentTimeMillis())
          .put("message", "News update")
      );
    });

    // Create the bridge with permissions
    BridgeOptions options = new BridgeOptions()
      .addInboundPermitted(new PermittedOptions().setAddress("hello"))
      .addInboundPermitted(new PermittedOptions().setAddress("echo"))
      .addOutboundPermitted(new PermittedOptions().setAddress("news"))
      .addOutboundPermitted(new PermittedOptions().setAddress("echo"));

    bridge = GrpcEventBusBridge.create(
      vertx,
      options,
      port,
      event -> {
        System.out.println("[SERVER] Bridge event: " + event.type());
        event.complete(true);
      }
    );

    // Start the bridge
    bridge.listen()
      .onSuccess(b -> {
        System.out.println("[SERVER] gRPC EventBus Bridge started on port " + port);
        startPromise.complete();
      })
      .onFailure(startPromise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {
    if (bridge != null) {
      bridge.close()
        .onSuccess(v -> {
          System.out.println("[SERVER] gRPC EventBus Bridge stopped");
          stopPromise.complete();
        })
        .onFailure(stopPromise::fail);
    } else {
      stopPromise.complete();
    }
  }

  // Helper method to deploy this verticle
  public static Future<String> deploy(Vertx vertx, int port) {
    return vertx.deployVerticle(new GrpcEventBusServer(port));
  }
}