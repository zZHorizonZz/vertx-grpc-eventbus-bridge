package io.vertx.ext.eventbus.bridge.grpc.impl;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Status;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.eventbus.bridge.grpc.BridgeEvent;
import io.vertx.grpc.common.GrpcError;
import io.vertx.grpc.common.GrpcErrorException;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.event.v1alpha.EventBusBridgeGrpcService;
import io.vertx.grpc.event.v1alpha.EventMessage;
import io.vertx.grpc.event.v1alpha.EventRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GrpcEventBusBridgeService extends EventBusBridgeGrpcService {

    private final EventBus bus;
    private final BridgeOptions options;
    private final Handler<BridgeEvent> bridgeEventHandler;
    private final Map<String, Pattern> compiledREs;

    private final Map<String, Map<String, MessageConsumer<?>>> consumers = new ConcurrentHashMap<>();
    private final Map<String, Message<?>> replies = new ConcurrentHashMap<>();

    public GrpcEventBusBridgeService(EventBus bus, BridgeOptions options, Handler<BridgeEvent> bridgeEventHandler, Map<String, Pattern> compiledREs) {
        this.bus = bus;
        this.options = options != null ? options : new BridgeOptions();
        this.bridgeEventHandler = bridgeEventHandler;
        this.compiledREs = compiledREs != null ? compiledREs : new HashMap<>();
    }

    @Override
    public Future<EventMessage> send(EventRequest request) {
        Promise<EventMessage> promise = Promise.promise();

        String address = request.getAddress();
        if (address.isEmpty()) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.INVALID_ARGUMENT));
            return promise.future();
        }

        JsonObject body = protoToJson(request.getBody());
        JsonObject eventJson = createEvent("send", request);

        if (!checkMatches(true, address)) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED));
            return promise.future();
        }

        checkCallHook(BridgeEventType.SEND, eventJson,
                () -> {
                    DeliveryOptions deliveryOptions = createDeliveryOptions(request.getHeadersMap());

                    if (!request.getReplyAddress().isEmpty()) {
                        bus.request(address, body, deliveryOptions)
                                .onSuccess(reply -> {
                                    if (reply.replyAddress() != null) {
                                        replies.put(reply.replyAddress(), reply);
                                    }

                                    promise.complete(EventMessage.getDefaultInstance());
                                }).onFailure(err -> handleError(err, promise));
                    } else {
                        bus.send(address, body, deliveryOptions);
                        promise.complete(EventMessage.getDefaultInstance());
                    }
                },
                () -> promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED))
        );

        return promise.future();
    }

    @Override
    public Future<Empty> publish(EventRequest request) {
        Promise<Empty> promise = Promise.promise();

        String address = request.getAddress();
        if (address.isEmpty()) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.INVALID_ARGUMENT));
            return promise.future();
        }

        JsonObject body = protoToJson(request.getBody());
        JsonObject eventJson = createEvent("publish", request);

        if (!checkMatches(true, address)) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED));
            return promise.future();
        }

        checkCallHook(BridgeEventType.PUBLISH, eventJson,
                () -> {
                    // Create delivery options from headers
                    DeliveryOptions deliveryOptions = createDeliveryOptions(request.getHeadersMap());

                    // Publish the message
                    bus.publish(address, body, deliveryOptions);

                    // Send success response
                    promise.complete(Empty.getDefaultInstance());
                },
                () -> promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED)));

        return promise.future();
    }

    @Override
    public Future<EventMessage> request(EventRequest request) {
        Promise<EventMessage> promise = Promise.promise();

        String address = request.getAddress();
        if (address.isEmpty()) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.INVALID_ARGUMENT));
            return promise.future();
        }

        JsonObject body = protoToJson(request.getBody());
        JsonObject eventJson = createEvent("send", request);

        if (!checkMatches(true, address)) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED));
            return promise.future();
        }

        checkCallHook(BridgeEventType.SEND, eventJson,
                () -> {
                    DeliveryOptions deliveryOptions = createDeliveryOptions(request.getHeadersMap());

                    if (request.getTimeout() > 0) {
                        deliveryOptions.setSendTimeout(request.getTimeout());
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

                                promise.complete(EventMessage.newBuilder().putAllHeaders(responseHeaders).setBody(replyBody).build());
                            })
                            .onFailure(err -> handleError(err, promise));
                },
                () -> promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED))
        );

        return promise.future();
    }

    @Override
    public Future<Empty> unsubscribe(EventRequest request) {
        Promise<Empty> promise = Promise.promise();

        String address = request.getAddress();
        String consumerId = request.getConsumer();

        if (address.isEmpty()) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.INVALID_ARGUMENT));
            return promise.future();
        }

        if (consumerId.isEmpty()) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.INVALID_ARGUMENT));
            return promise.future();
        }

        JsonObject eventJson = createEvent("unregister", request);

        if (!checkMatches(false, address)) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED));
            return promise.future();
        }

        checkCallHook(BridgeEventType.UNREGISTER, eventJson,
                () -> {
                    if (unregisterConsumer(address, consumerId)) {
                        promise.complete(Empty.getDefaultInstance());
                    } else {
                        promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.NOT_FOUND));
                    }
                },
                () -> promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED))
        );

        return promise.future();
    }

    @Override
    public Future<Empty> ping(Empty request) {
        Promise<Empty> promise = Promise.promise();
        JsonObject eventJson = createEvent("ping", null);

        checkCallHook(BridgeEventType.SOCKET_PING, eventJson,
                () -> promise.complete(Empty.getDefaultInstance()),
                () -> promise.complete(Empty.getDefaultInstance())
        );

        return promise.future();
    }

    @Override
    public Future<ReadStream<EventMessage>> subscribe(EventRequest request) {
        Promise<ReadStream<EventMessage>> promise = Promise.promise();

        String address = request.getAddress();
        if (address.isEmpty()) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.INVALID_ARGUMENT));
            return promise.future();
        }

        JsonObject event = createEvent("register", request);

        if (!checkMatches(false, address)) {
            promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED));
            return promise.future();
        }

        checkCallHook(BridgeEventType.REGISTER, event,
                () -> {
                    String consumerId = UUID.randomUUID().toString();

                    ReadStream<EventMessage> stream = new ReadStream<>() {
                        private Handler<EventMessage> dataHandler;
                        private Handler<Throwable> exceptionHandler;
                        private Handler<Void> endHandler;
                        private boolean paused = false;
                        private MessageConsumer<Object> consumer;

                        {
                            consumer = bus.consumer(address, message -> {
                                if (dataHandler != null && !paused) {
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

                                    dataHandler.handle(response);
                                }
                            });

                            Map<String, MessageConsumer<?>> addressConsumers = consumers.computeIfAbsent(address, k -> new ConcurrentHashMap<>());
                            addressConsumers.put(consumerId, consumer);
                        }

                        @Override
                        public ReadStream<EventMessage> exceptionHandler(Handler<Throwable> handler) {
                            this.exceptionHandler = handler;
                            return this;
                        }

                        @Override
                        public ReadStream<EventMessage> handler(Handler<EventMessage> handler) {
                            this.dataHandler = handler;
                            return this;
                        }

                        @Override
                        public ReadStream<EventMessage> pause() {
                            paused = true;
                            return this;
                        }

                        @Override
                        public ReadStream<EventMessage> resume() {
                            paused = false;
                            return this;
                        }

                        @Override
                        public ReadStream<EventMessage> fetch(long amount) {
                            return this;
                        }

                        @Override
                        public ReadStream<EventMessage> endHandler(Handler<Void> endHandler) {
                            this.endHandler = endHandler;
                            if (endHandler == null && consumer != null) {
                                unregisterConsumer(address, consumerId);
                            }

                            return this;
                        }
                    };

                    promise.complete(stream);
                },
                () -> promise.fail(new GrpcErrorException(GrpcError.INTERNAL, GrpcStatus.PERMISSION_DENIED))
        );

        return promise.future();
    }

    public void checkCallHook(BridgeEventType type, JsonObject message, Runnable okAction, Runnable rejectAction) {
        checkCallHook(() -> new BridgeEventImpl(type, message), okAction, rejectAction);
    }

    public void checkCallHook(Supplier<BridgeEventImpl> eventSupplier, Runnable okAction, Runnable rejectAction) {
        if (bridgeEventHandler == null) {
            if (okAction != null) {
                okAction.run();
            }
        } else {
            BridgeEventImpl event = eventSupplier.get();
            bridgeEventHandler.handle(event);
            event.future().onComplete(res -> {
                if (res.succeeded()) {
                    if (res.result()) {
                        if (okAction != null) {
                            okAction.run();
                        }
                    } else {
                        if (rejectAction != null) {
                            rejectAction.run();
                        }
                    }
                }
            });
        }
    }

    public boolean checkMatches(boolean inbound, String address) {
        if (inbound && replies.containsKey(address)) {
            return true;
        }

        List<PermittedOptions> matches = inbound ? options.getInboundPermitteds() : options.getOutboundPermitteds();

        for (PermittedOptions matchHolder : matches) {
            String matchAddress = matchHolder.getAddress();
            String matchRegex;
            if (matchAddress == null) {
                matchRegex = matchHolder.getAddressRegex();
            } else {
                matchRegex = null;
            }

            boolean addressOK;
            if (matchAddress == null) {
                addressOK = matchRegex == null || regexMatches(matchRegex, address);
            } else {
                addressOK = matchAddress.equals(address);
            }

            if (addressOK) {
                return true;
            }
        }

        return false;
    }

    public boolean regexMatches(String matchRegex, String address) {
        Pattern pattern = compiledREs.get(matchRegex);
        if (pattern == null) {
            pattern = Pattern.compile(matchRegex);
            compiledREs.put(matchRegex, pattern);
        }
        Matcher m = pattern.matcher(address);
        return m.matches();
    }

    private boolean unregisterConsumer(String address, String consumerId) {
        Map<String, MessageConsumer<?>> addressConsumers = consumers.get(address);
        if (addressConsumers != null) {
            MessageConsumer<?> consumer = addressConsumers.remove(consumerId);
            if (consumer != null) {
                consumer.unregister();
                if (addressConsumers.isEmpty()) {
                    consumers.remove(address);
                }

                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> void handleError(Throwable error, Promise<T> promise) {
        if (error instanceof ReplyException) {
            ReplyException replyEx = (ReplyException) error;
            promise.complete((T) EventMessage.newBuilder()
                    .setStatus(Status.newBuilder().setCode(replyEx.failureCode()).setMessage(replyEx.getMessage()).build())
                    .build());
        } else {
            promise.complete((T) EventMessage.newBuilder()
                    .setStatus(Status.newBuilder().setCode(500).setMessage(error.getMessage()).build())
                    .build());
        }
    }

    private JsonObject createEvent(String type, EventRequest request) {
        JsonObject event = new JsonObject().put("type", type);

        if (request == null) {
            return event;
        }

        if (!request.getAddress().isEmpty()) {
            event.put("address", request.getAddress());
        }

        if (!request.getConsumer().isEmpty()) {
            event.put("consumer", request.getConsumer());
        }

        if (!request.getReplyAddress().isEmpty()) {
            event.put("replyAddress", request.getReplyAddress());
        }

        if (!request.getHeadersMap().isEmpty()) {
            JsonObject headers = new JsonObject();
            request.getHeadersMap().forEach(headers::put);
            event.put("headers", headers);
        }

        return event;
    }

    private JsonObject protoToJson(com.google.protobuf.Message message) {
        JsonObject json = new JsonObject();
        try {
            String jsonString = JsonFormat.printer().print(message);
            json = new JsonObject(jsonString);
        } catch (Exception e) {
            // Handle exception
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private <T extends com.google.protobuf.Message> T jsonToProto(JsonObject json, com.google.protobuf.Message.Builder builder) {
        try {
            JsonFormat.parser().merge(json.encode(), builder);
        } catch (Exception e) {
            // Handle exception
        }
        return (T) builder.build();
    }

    /**
     * Convert request headers to DeliveryOptions
     */
    public DeliveryOptions createDeliveryOptions(Map<String, String> headers) {
        DeliveryOptions options = new DeliveryOptions();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                if ("timeout".equals(key)) {
                    try {
                        options.setSendTimeout(Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        // Ignore invalid timeout
                    }
                } else if ("localOnly".equals(key)) {
                    options.setLocalOnly(Boolean.parseBoolean(value));
                } else if ("codecName".equals(key)) {
                    options.setCodecName(value);
                } else {
                    options.addHeader(key, value);
                }
            }
        }
        return options;
    }
}
