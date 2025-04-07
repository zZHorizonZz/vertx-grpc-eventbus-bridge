package io.vertx.ext.eventbus.bridge.grpc.impl.handler;

import com.google.protobuf.Struct;
import com.google.rpc.Status;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.ReplyException;
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
import io.vertx.grpc.event.v1alpha.EventMessage;
import io.vertx.grpc.event.v1alpha.EventRequest;
import io.vertx.grpc.server.GrpcServerRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class EventBusBridgeRequestHandler extends EventBusBridgeHandlerBase implements Handler<GrpcServerRequest<EventRequest, EventMessage>> {

    public static final ServiceMethod<EventRequest, EventMessage> SERVICE_METHOD = ServiceMethod.server(
            ServiceName.create("vertx.event.v1alpha.EventBusBridge"),
            "Request",
            GrpcMessageEncoder.encoder(),
            GrpcMessageDecoder.decoder(EventRequest.parser()));

    public EventBusBridgeRequestHandler(EventBus bus, BridgeOptions options, Handler<BridgeEvent> bridgeEventHandler, Map<String, Pattern> compiledREs) {
        super(bus, options, bridgeEventHandler, compiledREs);
    }

    @Override
    public void handle(GrpcServerRequest<EventRequest, EventMessage> request) {
        request.handler(eventRequest -> {
            String address = eventRequest.getAddress();
            if (address.isEmpty()) {
                request.response().status(GrpcStatus.INVALID_ARGUMENT).end();
                return;
            }

            JsonObject body = protoToJson(eventRequest.getBody());
            JsonObject eventJson = createEvent("send", eventRequest);

            if (!checkMatches(true, address)) {
                request.response().status(GrpcStatus.PERMISSION_DENIED).end();
                return;
            }

            checkCallHook(BridgeEventType.SEND, eventJson,
                    () -> {
                        DeliveryOptions deliveryOptions = createDeliveryOptions(eventRequest.getHeadersMap());

                        if (eventRequest.getTimeout() > 0) {
                            deliveryOptions.setSendTimeout(eventRequest.getTimeout());
                        }

                        bus.request(address, body, deliveryOptions)
                                .onSuccess(reply -> {
                                    Map<String, String> responseHeaders = new HashMap<>();
                                    for (Map.Entry<String, String> entry : reply.headers()) {
                                        responseHeaders.put(entry.getKey(), entry.getValue());
                                    }

                                    Struct replyBody;
                                    if (reply.body() instanceof JsonObject) {
                                        replyBody = jsonToProto((JsonObject) reply.body(), Struct.newBuilder());
                                    } else if (reply.body() instanceof String) {
                                        replyBody = jsonToProto(new JsonObject().put("value", reply.body()), Struct.newBuilder());
                                    } else {
                                        replyBody = jsonToProto(new JsonObject().put("value", String.valueOf(reply.body())), Struct.newBuilder());
                                    }

                                    EventMessage response = EventMessage.newBuilder()
                                            .putAllHeaders(responseHeaders)
                                            .setBody(replyBody)
                                            .build();

                                    request.response().end(response);
                                })
                                .onFailure(err -> {
                                    EventMessage response = handleErrorAndCreateResponse(err);
                                    request.response().end(response);
                                });
                    },
                    () -> request.response().status(GrpcStatus.PERMISSION_DENIED).end());
        });
    }
}
