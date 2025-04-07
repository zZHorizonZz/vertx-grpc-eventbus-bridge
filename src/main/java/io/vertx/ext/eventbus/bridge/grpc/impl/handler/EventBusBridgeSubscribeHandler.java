package io.vertx.ext.eventbus.bridge.grpc.impl.handler;

import com.google.protobuf.Struct;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class EventBusBridgeSubscribeHandler extends EventBusBridgeHandlerBase implements Handler<GrpcServerRequest<EventRequest, EventMessage>> {

    public static final ServiceMethod<EventRequest, EventMessage> SERVICE_METHOD = ServiceMethod.server(
            ServiceName.create("vertx.event.v1alpha.EventBusBridge"),
            "Subscribe",
            GrpcMessageEncoder.encoder(),
            GrpcMessageDecoder.decoder(EventRequest.parser()));

    public EventBusBridgeSubscribeHandler(EventBus bus, BridgeOptions options, Handler<BridgeEvent> bridgeEventHandler, Map<String, Pattern> compiledREs) {
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

            JsonObject event = createEvent("register", eventRequest);

            if (!checkMatches(false, address)) {
                request.response().status(GrpcStatus.PERMISSION_DENIED).end();
                return;
            }

            checkCallHook(BridgeEventType.REGISTER, event,
                    () -> {
                        request.pause();

                        String consumerId = UUID.randomUUID().toString();
                        requests.put(consumerId, request);

                        MessageConsumer<Object> consumer = bus.consumer(address,  new BridgeMessageConsumer(request, address, consumerId));

                        Map<String, MessageConsumer<?>> addressConsumers = consumers.computeIfAbsent(address, k -> new ConcurrentHashMap<>());
                        addressConsumers.put(consumerId, consumer);

                        request.endHandler(v -> unregisterConsumer(address, consumerId));
                    },
                    () -> request.response().status(GrpcStatus.PERMISSION_DENIED).end());
        });
    }

    static final class BridgeMessageConsumer implements Handler<Message<Object>> {
        private final GrpcServerRequest<EventRequest, EventMessage> request;
        private final String address;
        private final String consumerId;

        BridgeMessageConsumer(GrpcServerRequest<EventRequest, EventMessage> request, String address, String consumerId) {
            this.request = request;
            this.address = address;
            this.consumerId = consumerId;
        }

        @Override
        public void handle(Message<Object> message) {
            Map<String, String> responseHeaders = new HashMap<>();
            for (Map.Entry<String, String> entry : message.headers()) {
                responseHeaders.put(entry.getKey(), entry.getValue());
            }

            Struct body;

            if (message.body() instanceof JsonObject) {
                body = jsonToProto((JsonObject) message.body(), Struct.newBuilder());
            } else if (message.body() instanceof String) {
                body = jsonToProto(new JsonObject(String.valueOf(message.body())), Struct.newBuilder());
            } else {
                body = jsonToProto(new JsonObject().put("value", String.valueOf(message.body())), Struct.newBuilder());
            }

            EventMessage response = EventMessage.newBuilder()
                    .setAddress(address)
                    .setConsumer(consumerId)
                    .putAllHeaders(responseHeaders)
                    .setBody(body)
                    .build();

            if (message.replyAddress() != null) {
                response = response.toBuilder().setReplyAddress(message.replyAddress()).build();
                replies.put(message.replyAddress(), message);
            }

            request.resume();
            request.response().write(response);
            request.pause();
        }
    }
}
