package io.vertx.ext.eventbus.bridge.grpc.impl;

import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Status;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.eventbus.bridge.grpc.BridgeEvent;
import io.vertx.grpc.event.v1alpha.EventMessage;
import io.vertx.grpc.event.v1alpha.EventRequest;
import io.vertx.grpc.server.GrpcServerRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class EventBusBridgeHandlerBase {

    protected final EventBus bus;
    protected final BridgeOptions options;
    protected final Handler<BridgeEvent> bridgeEventHandler;
    protected final Map<String, Pattern> compiledREs;

    protected static final Map<String, Map<String, MessageConsumer<?>>> consumers = new ConcurrentHashMap<>();
    protected static final Map<String, io.vertx.core.eventbus.Message<?>> replies = new ConcurrentHashMap<>();
    protected static final Map<String, GrpcServerRequest<EventRequest, EventMessage>> requests = new ConcurrentHashMap<>();

    public EventBusBridgeHandlerBase(EventBus bus, BridgeOptions options, Handler<BridgeEvent> bridgeEventHandler, Map<String, Pattern> compiledREs) {
        this.bus = bus;
        this.options = options != null ? options : new BridgeOptions();
        this.bridgeEventHandler = bridgeEventHandler;
        this.compiledREs = compiledREs != null ? compiledREs : new HashMap<>();
    }

    protected void checkCallHook(BridgeEventType type, JsonObject message, Runnable okAction, Runnable rejectAction) {
        checkCallHook(() -> new BridgeEventImpl(type, message), okAction, rejectAction);
    }

    protected void checkCallHook(Supplier<BridgeEventImpl> eventSupplier, Runnable okAction, Runnable rejectAction) {
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

    protected boolean checkMatches(boolean inbound, String address) {
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

    protected boolean regexMatches(String matchRegex, String address) {
        Pattern pattern = compiledREs.get(matchRegex);
        if (pattern == null) {
            pattern = Pattern.compile(matchRegex);
            compiledREs.put(matchRegex, pattern);
        }
        Matcher m = pattern.matcher(address);
        return m.matches();
    }

    protected boolean unregisterConsumer(String address, String consumerId) {
        Map<String, MessageConsumer<?>> addressConsumers = consumers.get(address);
        if (addressConsumers != null) {
            MessageConsumer<?> consumer = addressConsumers.remove(consumerId);
            GrpcServerRequest<EventRequest, EventMessage> request = requests.remove(consumerId);

            if(request != null) {
                request.response().end();
            }

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
    protected <T> void handleError(Throwable error, Promise<T> promise) {
        if (error instanceof ReplyException) {
            ReplyException replyEx = (ReplyException) error;
            promise.complete((T) io.vertx.grpc.event.v1alpha.EventMessage.newBuilder()
                    .setStatus(Status.newBuilder().setCode(replyEx.failureCode()).setMessage(replyEx.getMessage()).build())
                    .build());
        } else {
            promise.complete((T) io.vertx.grpc.event.v1alpha.EventMessage.newBuilder()
                    .setStatus(Status.newBuilder().setCode(500).setMessage(error.getMessage()).build())
                    .build());
        }
    }

    protected JsonObject createEvent(String type, EventRequest request) {
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

    protected JsonObject protoToJson(Message message) {
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
    protected <T extends Message> T jsonToProto(JsonObject json, Message.Builder builder) {
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