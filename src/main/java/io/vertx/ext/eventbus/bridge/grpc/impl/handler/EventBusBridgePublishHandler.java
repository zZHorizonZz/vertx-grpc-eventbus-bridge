package io.vertx.ext.eventbus.bridge.grpc.impl.handler;

import com.google.protobuf.Empty;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.eventbus.bridge.grpc.BridgeEvent;
import io.vertx.ext.eventbus.bridge.grpc.impl.EventBusBridgeHandlerBase;
import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.event.v1alpha.EventRequest;
import io.vertx.grpc.server.GrpcServerRequest;

import java.util.Map;
import java.util.regex.Pattern;

public class EventBusBridgePublishHandler extends EventBusBridgeHandlerBase implements Handler<GrpcServerRequest<EventRequest, Empty>> {

    public static final ServiceMethod<EventRequest, Empty> SERVICE_METHOD = ServiceMethod.server(
            ServiceName.create("vertx.event.v1alpha.EventBusBridge"),
            "Publish",
            GrpcMessageEncoder.encoder(),
            GrpcMessageDecoder.decoder(EventRequest.parser()));

    public EventBusBridgePublishHandler(EventBus bus, BridgeOptions options, Handler<BridgeEvent> bridgeEventHandler, Map<String, Pattern> compiledREs) {
        super(bus, options, bridgeEventHandler, compiledREs);
    }

    @Override
    public void handle(GrpcServerRequest<EventRequest, Empty> request) {
        request.handler(eventRequest -> {
            String address = eventRequest.getAddress();
            if (address.isEmpty()) {
                request.response().status(GrpcStatus.INVALID_ARGUMENT).end();
                return;
            }

            JsonObject body = protoToJson(eventRequest.getBody());
            JsonObject eventJson = createEvent("publish", eventRequest);

            if (!checkMatches(true, address)) {
                request.response().status(GrpcStatus.PERMISSION_DENIED).end();
                return;
            }

            checkCallHook(BridgeEventType.PUBLISH, eventJson,
                    () -> {
                        // Create delivery options from headers
                        DeliveryOptions deliveryOptions = createDeliveryOptions(eventRequest.getHeadersMap());

                        // Publish the message
                        bus.publish(address, body, deliveryOptions);

                        // Send success response
                        request.response().end(Empty.getDefaultInstance());
                    },
                    () -> request.response().status(GrpcStatus.PERMISSION_DENIED).end());
        });
    }
}
