package io.vertx.ext.eventbus.bridge.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.event.v1alpha.EventBusBridgeGrpcClient;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public abstract class GrpcEventBusBridgeTestBase {

    protected Vertx vertx;
    protected GrpcEventBusBridge bridge;
    protected volatile Handler<BridgeEvent> eventHandler = event -> event.complete(true);

    @Before
    public void before(TestContext context) {
        vertx = Vertx.vertx();

        final Async async = context.async();

        vertx.eventBus().consumer("hello", (Message<JsonObject> msg) -> msg.reply(new JsonObject().put("value", "Hello " + msg.body().getString("value"))));
        vertx.eventBus().consumer("echo", (Message<JsonObject> msg) -> msg.reply(msg.body()));
        vertx.setPeriodic(1000, __ -> vertx.eventBus().send("ping", new JsonObject().put("value", "hi")));

        bridge = GrpcEventBusBridge.create(
                vertx,
                new BridgeOptions()
                        .addInboundPermitted(new PermittedOptions().setAddress("hello"))
                        .addInboundPermitted(new PermittedOptions().setAddress("echo"))
                        .addInboundPermitted(new PermittedOptions().setAddress("test"))
                        .addOutboundPermitted(new PermittedOptions().setAddress("echo"))
                        .addOutboundPermitted(new PermittedOptions().setAddress("test"))
                        .addOutboundPermitted(new PermittedOptions().setAddress("ping")),
                7000,
                event -> eventHandler.handle(event));

        bridge.listen().onComplete(res -> {
            context.assertTrue(res.succeeded());
            async.complete();
        });
    }

    @After
    public void after(TestContext context) {
        Async async = context.async();
        if (bridge != null) {
            bridge.close().onComplete(v -> vertx.close().onComplete(context.asyncAssertSuccess(h -> async.complete())));
        } else {
            vertx.close().onComplete(context.asyncAssertSuccess(h -> async.complete()));
        }
    }

    /**
     * Convert a JsonObject to a Protobuf Struct
     * TODO: In the future we should use a proper conversion implemented directly to Vert.x?
     */
    public static Struct jsonToStruct(JsonObject json) {
        if (json == null) {
            return Struct.getDefaultInstance();
        }

        Struct.Builder structBuilder = Struct.newBuilder();
        for (String fieldName : json.fieldNames()) {
            Object value = json.getValue(fieldName);
            if (value == null) {
                structBuilder.putFields(fieldName, Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build());
            } else if (value instanceof String) {
                structBuilder.putFields(fieldName, Value.newBuilder().setStringValue((String) value).build());
            } else if (value instanceof Number) {
                structBuilder.putFields(fieldName, Value.newBuilder().setNumberValue(((Number) value).doubleValue()).build());
            } else if (value instanceof Boolean) {
                structBuilder.putFields(fieldName, Value.newBuilder().setBoolValue((Boolean) value).build());
            } else if (value instanceof JsonObject) {
                structBuilder.putFields(fieldName, Value.newBuilder().setStructValue(jsonToStruct((JsonObject) value)).build());
            }
        }
        return structBuilder.build();
    }

    /**
     * Convert a Protobuf Struct to a JsonObject
     * TODO: In the future we should use a proper conversion implemented directly to Vert.x?
     */
    public static JsonObject structToJson(Struct struct) {
        if (struct == null) {
            return new JsonObject();
        }

        JsonObject json = new JsonObject();
        struct.getFieldsMap().forEach((key, value) -> {
            switch (value.getKindCase()) {
                case NULL_VALUE:
                    json.putNull(key);
                    break;
                case NUMBER_VALUE:
                    json.put(key, value.getNumberValue());
                    break;
                case STRING_VALUE:
                    json.put(key, value.getStringValue());
                    break;
                case BOOL_VALUE:
                    json.put(key, value.getBoolValue());
                    break;
                case STRUCT_VALUE:
                    json.put(key, structToJson(value.getStructValue()));
                    break;
                default:
                    break;
            }
        });
        return json;
    }
}
