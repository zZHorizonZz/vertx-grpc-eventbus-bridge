package io.vertx.ext.eventbus.bridge.grpc.impl;

import com.google.protobuf.Descriptors;
import io.vertx.ext.eventbus.bridge.grpc.impl.handler.*;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.event.v1alpha.EventBusBridgeProto;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpc.server.Service;

public class EventBusBridgeService implements Service {

    private static final ServiceName SERVICE_NAME = ServiceName.create("vertx.event.v1alpha.EventBusBridge");
    private static final Descriptors.ServiceDescriptor SERVICE_DESCRIPTOR = EventBusBridgeProto.getDescriptor().findServiceByName("EventBusBridge");

    private static final EventBusBridgeService INSTANCE = new EventBusBridgeService();

    public static EventBusBridgeService getInstance() {
        return INSTANCE;
    }

    private EventBusBridgeService() {
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
        server.callHandler(EventBusBridgePublishHandler.SERVICE_METHOD, new EventBusBridgePublishHandler());
        server.callHandler(EventBusBridgeSendHandler.SERVICE_METHOD, new EventBusBridgeSendHandler());
        server.callHandler(EventBusBridgeRequestHandler.SERVICE_METHOD, new EventBusBridgeRequestHandler());
        server.callHandler(EventBusBridgeSubscribeHandler.SERVICE_METHOD, new EventBusBridgeSubscribeHandler());
        server.callHandler(EventBusBridgeUnsubscribeHandler.SERVICE_METHOD, new EventBusBridgeUnsubscribeHandler());
        server.callHandler(EventBusBridgePingHandler.SERVICE_METHOD, new EventBusBridgePingHandler());
    }
}
