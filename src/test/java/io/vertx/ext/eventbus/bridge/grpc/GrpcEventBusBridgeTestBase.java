package io.vertx.ext.eventbus.bridge.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.vertx.core.json.JsonObject;

public abstract class GrpcEventBusBridgeTestBase {

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
