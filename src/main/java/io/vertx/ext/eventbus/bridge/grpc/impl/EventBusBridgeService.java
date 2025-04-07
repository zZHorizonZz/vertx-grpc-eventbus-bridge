package io.vertx.ext.eventbus.bridge.grpc.impl;

import com.google.protobuf.Descriptors;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.eventbus.bridge.grpc.BridgeEvent;
import io.vertx.ext.eventbus.bridge.grpc.impl.handler.*;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.event.v1alpha.EventBusBridgeProto;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpc.server.Service;

import java.util.Map;
import java.util.regex.Pattern;

public class EventBusBridgeService implements Service {

    private static final ServiceName SERVICE_NAME = ServiceName.create("vertx.event.v1alpha.EventBusBridge");
    private static final Descriptors.ServiceDescriptor SERVICE_DESCRIPTOR = EventBusBridgeProto.getDescriptor().findServiceByName("EventBusBridge");

    private final EventBus eventBus;
    private final BridgeOptions options;
    private final Handler<BridgeEvent> bridgeEventHandler;
    private final Map<String, Pattern> compiledREs;

    public EventBusBridgeService(EventBus eventBus, BridgeOptions options, Handler<BridgeEvent> bridgeEventHandler, Map<String, Pattern> compiledREs) {
        this.eventBus = eventBus;
        this.options = options;
        this.bridgeEventHandler = bridgeEventHandler;
        this.compiledREs = compiledREs;
    }

    @Override
    public ServiceName name() {
        return SERVICE_NAME;
    }

    @Override
    public Descriptors.ServiceDescriptor descriptor() {
        return SERVICE_DESCRIPTOR;
    }

    @Override
    public void bind(GrpcServer server) {
        server.callHandler(EventBusBridgePublishHandler.SERVICE_METHOD, new EventBusBridgePublishHandler(eventBus, options, bridgeEventHandler, compiledREs));
        server.callHandler(EventBusBridgeSendHandler.SERVICE_METHOD, new EventBusBridgeSendHandler(eventBus, options, bridgeEventHandler, compiledREs));
        server.callHandler(EventBusBridgeRequestHandler.SERVICE_METHOD, new EventBusBridgeRequestHandler(eventBus, options, bridgeEventHandler, compiledREs));
        server.callHandler(EventBusBridgeSubscribeHandler.SERVICE_METHOD, new EventBusBridgeSubscribeHandler(eventBus, options, bridgeEventHandler, compiledREs));
        server.callHandler(EventBusBridgeUnsubscribeHandler.SERVICE_METHOD, new EventBusBridgeUnsubscribeHandler(eventBus, options, bridgeEventHandler, compiledREs));
        server.callHandler(EventBusBridgePingHandler.SERVICE_METHOD, new EventBusBridgePingHandler(eventBus, options, bridgeEventHandler, compiledREs));
    }
}
