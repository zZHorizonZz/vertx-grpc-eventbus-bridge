package io.vertx.ext.eventbus.bridge.grpc.impl.handler;

import io.vertx.core.Handler;
import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.event.v1alpha.EventMessage;
import io.vertx.grpc.event.v1alpha.EventRequest;
import io.vertx.grpc.server.GrpcServerRequest;

public class EventBusBridgeSubscribeHandler implements Handler<GrpcServerRequest<EventRequest, EventMessage>> {

    public static final ServiceMethod<EventRequest, EventMessage> SERVICE_METHOD = ServiceMethod.server(
            ServiceName.create("vertx.event.v1alpha.EventBusBridge"),
            "Subscribe",
            GrpcMessageEncoder.encoder(),
            GrpcMessageDecoder.decoder(EventRequest.parser()));

    @Override
    public void handle(GrpcServerRequest<EventRequest, EventMessage> event) {

    }
}
