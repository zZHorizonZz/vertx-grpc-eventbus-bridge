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
import io.vertx.grpc.common.GrpcStatus;
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

/**
 * Abstract base class for all EventBus bridge handlers.
 */
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

    /**
     * Checks if a bridge event should be allowed by calling the bridge event handler. If the event is allowed, the okAction is executed. If it's denied, the rejectAction is
     * executed. If no bridge event handler is configured, the event is automatically allowed.
     *
     * @param type the type of bridge event (SEND, PUBLISH, RECEIVE, REGISTER, UNREGISTER)
     * @param message the message associated with the event
     * @param okAction the action to execute if the event is allowed
     * @param rejectAction the action to execute if the event is denied
     */
    protected void checkCallHook(BridgeEventType type, JsonObject message, Runnable okAction, Runnable rejectAction) {
        checkCallHook(() -> new BridgeEventImpl(type, message), okAction, rejectAction);
    }

    /**
     * Checks if a bridge event should be allowed by calling the bridge event handler. If the event is allowed, the okAction is executed. If it's denied, the rejectAction is
     * executed. If no bridge event handler is configured, the event is automatically allowed.
     *
     * This overload allows lazy creation of the bridge event through a supplier.
     *
     * @param eventSupplier a supplier that creates the bridge event when needed
     * @param okAction the action to execute if the event is allowed
     * @param rejectAction the action to execute if the event is denied
     */
    protected void checkCallHook(Supplier<BridgeEventImpl> eventSupplier, Runnable okAction, Runnable rejectAction) {
        if (bridgeEventHandler == null) {
            // No bridge event handler configured, automatically allow the event
            if (okAction != null) {
                okAction.run();
            }
        } else {
            // Create the bridge event and pass it to the handler
            BridgeEventImpl event = eventSupplier.get();
            bridgeEventHandler.handle(event);

            // When the event is completed, execute the appropriate action
            event.future().onComplete(res -> {
                if (res.succeeded()) {
                    if (res.result()) {
                        // Event allowed
                        if (okAction != null) {
                            okAction.run();
                        }
                    } else {
                        // Event denied
                        if (rejectAction != null) {
                            rejectAction.run();
                        }
                    }
                }
            });
        }
    }

    /**
     * Checks if the specified address matches any of the permitted options.
     *
     * This method is used to implement the security rules for the EventBus bridge. It checks if the address is allowed based on the bridge options.
     *
     * For inbound messages, it also checks if the address is a reply address for a pending request.
     *
     * @param inbound true if checking inbound permissions, false if checking outbound permissions
     * @param address the address to check
     * @return true if the address matches any of the permitted options, false otherwise
     */
    protected boolean checkMatches(boolean inbound, String address) {
        // Special case: if this is an inbound message to a reply address, always allow it
        if (inbound && replies.containsKey(address)) {
            return true;
        }

        // Get the appropriate list of permitted options based on direction
        List<PermittedOptions> matches = inbound ? options.getInboundPermitteds() : options.getOutboundPermitteds();

        // Check each permitted option
        for (PermittedOptions matchHolder : matches) {
            String matchAddress = matchHolder.getAddress();
            String matchRegex;
            if (matchAddress == null) {
                matchRegex = matchHolder.getAddressRegex();
            } else {
                matchRegex = null;
            }

            // Check if the address matches either the exact address or the regex pattern
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

        // No match found
        return false;
    }

    /**
     * Checks if the specified address matches the given regex pattern.
     *
     * This method uses a cache of compiled regular expressions for better performance. If the pattern is not in the cache, it compiles it and adds it to the cache.
     *
     * @param matchRegex the regex pattern to match against
     * @param address the address to check
     * @return true if the address matches the pattern, false otherwise
     */
    protected boolean regexMatches(String matchRegex, String address) {
        // Get or create the compiled pattern
        Pattern pattern = compiledREs.get(matchRegex);
        if (pattern == null) {
            pattern = Pattern.compile(matchRegex);
            compiledREs.put(matchRegex, pattern);
        }

        // Check if the address matches the pattern
        Matcher m = pattern.matcher(address);
        return m.matches();
    }

    protected <Req, Resp> void replyStatus(GrpcServerRequest<Req, Resp> request, GrpcStatus status) {
        this.replyStatus(request, status, null);
    }

    protected <Req, Resp> void replyStatus(GrpcServerRequest<Req, Resp> request, GrpcStatus status, String message) {
        request.response().status(status);

        if (message != null) {
            request.response().statusMessage(message);
        }

        request.response().end();
    }

    /**
     * Handles an error and creates a response message.
     * <p>
     * This method is used to handle errors that occur during processing. It creates a response message with the appropriate status code and message.
     *
     * @param error the error that occurred
     * @return a response message with the error details
     */
    protected EventMessage handleErrorAndCreateResponse(Throwable error) {
        if (error instanceof ReplyException) {
            ReplyException replyEx = (ReplyException) error;
            return EventMessage.newBuilder()
                    .setStatus(Status.newBuilder().setCode(replyEx.failureCode()).setMessage(replyEx.getMessage()).build())
                    .build();
        } else {
            return EventMessage.newBuilder()
                    .setStatus(Status.newBuilder().setCode(500).setMessage(error.getMessage()).build())
                    .build();
        }
    }

    /**
     * Unregisters a consumer from the EventBus.
     *
     * This method is called when a client wants to unsubscribe from an address. It removes the consumer from the internal maps and unregisters it from the EventBus. If this was
     * the last consumer for the address, it also removes the address from the map.
     *
     * @param address the address the consumer is subscribed to
     * @param consumerId the unique ID of the consumer to unregister
     * @return true if the consumer was found and unregistered, false otherwise
     */
    protected boolean unregisterConsumer(String address, String consumerId) {
        // Get the map of consumers for this address
        Map<String, MessageConsumer<?>> addressConsumers = consumers.get(address);
        if (addressConsumers != null) {
            // Remove the consumer from the map
            MessageConsumer<?> consumer = addressConsumers.remove(consumerId);

            // Remove any associated gRPC request and end the response stream
            GrpcServerRequest<EventRequest, EventMessage> request = requests.remove(consumerId);
            if (request != null) {
                request.response().end();
            }

            // If the consumer was found, unregister it from the EventBus
            if (consumer != null) {
                consumer.unregister();

                // If this was the last consumer for this address, remove the address from the map
                if (addressConsumers.isEmpty()) {
                    consumers.remove(address);
                }

                return true;
            }
        }
        return false;
    }

    /**
     * Creates a JSON object representing a bridge event from a gRPC request.
     *
     * This method extracts information from the request and puts it into a JSON object that can be used for bridge event processing.
     *
     * @param type the type of event (e.g., "send", "publish", "register")
     * @param request the gRPC request containing the event details
     * @return a JSON object representing the event
     */
    protected static JsonObject createEvent(String type, EventRequest request) {
        JsonObject event = new JsonObject().put("type", type);

        if (request == null) {
            return event;
        }

        // Add address if present
        if (!request.getAddress().isEmpty()) {
            event.put("address", request.getAddress());
        }

        // Add consumer ID if present
        if (!request.getConsumer().isEmpty()) {
            event.put("consumer", request.getConsumer());
        }

        // Add reply address if present
        if (!request.getReplyAddress().isEmpty()) {
            event.put("replyAddress", request.getReplyAddress());
        }

        // Add headers if present
        if (!request.getHeadersMap().isEmpty()) {
            JsonObject headers = new JsonObject();
            request.getHeadersMap().forEach(headers::put);
            event.put("headers", headers);
        }

        return event;
    }

    /**
     * Converts a Protocol Buffers message to a JSON object.
     *
     * This method uses the Protocol Buffers JSON format to convert the message to a JSON string, then parses that string into a Vert.x JsonObject.
     *
     * @param message the Protocol Buffers message to convert
     * @return a JSON object representing the message
     */
    protected static JsonObject protoToJson(Message message) {
        JsonObject json = new JsonObject();
        try {
            String jsonString = JsonFormat.printer().print(message);
            json = new JsonObject(jsonString);
        } catch (Exception e) {
            // Silently handle exception and return empty JSON object
            // This could be improved with logging
        }
        return json;
    }

    /**
     * Converts a JSON object to a Protocol Buffers message.
     *
     * This method uses the Protocol Buffers JSON format to parse the JSON object into a Protocol Buffers message of the specified type.
     *
     * @param json the JSON object to convert
     * @param builder the builder for the target Protocol Buffers message type
     * @param <T> the type of the Protocol Buffers message
     * @return a Protocol Buffers message representing the JSON object
     */
    @SuppressWarnings("unchecked")
    protected static <T extends Message> T jsonToProto(JsonObject json, Message.Builder builder) {
        try {
            JsonFormat.parser().merge(json.encode(), builder);
        } catch (Exception e) {
            // Silently handle exception and return message with default values
            // This could be improved with logging
        }
        return (T) builder.build();
    }

    /**
     * Converts request headers to Vert.x DeliveryOptions.
     *
     * This method processes the headers from a gRPC request and converts them into Vert.x DeliveryOptions that can be used when sending messages on the EventBus.
     *
     * Special headers: - "timeout": Sets the send timeout in milliseconds - "localOnly": Sets whether the message should be delivered to local consumers only - "codecName": Sets
     * the codec name to use for the message
     *
     * All other headers are added as regular headers to the DeliveryOptions.
     *
     * @param headers the headers from the gRPC request
     * @return DeliveryOptions configured with the specified headers
     */
    public static DeliveryOptions createDeliveryOptions(Map<String, String> headers) {
        DeliveryOptions options = new DeliveryOptions();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                // Handle special headers
                if ("timeout".equals(key)) {
                    try {
                        options.setSendTimeout(Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        // Ignore invalid timeout values
                    }
                } else if ("localOnly".equals(key)) {
                    options.setLocalOnly(Boolean.parseBoolean(value));
                } else if ("codecName".equals(key)) {
                    options.setCodecName(value);
                } else {
                    // Add all other headers as regular headers
                    options.addHeader(key, value);
                }
            }
        }
        return options;
    }
}
