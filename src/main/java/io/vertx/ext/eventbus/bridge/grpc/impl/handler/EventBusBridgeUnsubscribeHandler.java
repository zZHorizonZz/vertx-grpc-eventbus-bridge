package io.vertx.ext.eventbus.bridge.grpc.impl.handler;

import com.google.protobuf.Empty;
import io.vertx.core.Handler;
import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.event.v1alpha.EventRequest;
import io.vertx.grpc.server.GrpcServerRequest;

public class EventBusBridgeUnsubscribeHandler implements Handler<GrpcServerRequest<EventRequest, Empty>> {

    public static final ServiceMethod<EventRequest, Empty> SERVICE_METHOD = ServiceMethod.server(
            ServiceName.create("vertx.event.v1alpha.EventBusBridge"),
            "Unsubscribe",
            GrpcMessageEncoder.encoder(),
            GrpcMessageDecoder.decoder(EventRequest.parser()));

    @Override
    public void handle(GrpcServerRequest<EventRequest, Empty> event) {

    }
}
