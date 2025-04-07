package io.vertx.ext.eventbus.bridge.grpc.impl.handler;

import com.google.protobuf.Empty;
import io.vertx.core.Handler;
import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.server.GrpcServerRequest;

public class EventBusBridgePingHandler implements Handler<GrpcServerRequest<Empty, Empty>> {

    public static final ServiceMethod<Empty, Empty> SERVICE_METHOD = ServiceMethod.server(
            ServiceName.create("vertx.event.v1alpha.EventBusBridge"),
            "Ping",
            GrpcMessageEncoder.encoder(),
            GrpcMessageDecoder.decoder(Empty.parser()));

    @Override
    public void handle(GrpcServerRequest<Empty, Empty> event) {
        // Handle the ping request
        event.response().send(Empty.getDefaultInstance());
    }
}
