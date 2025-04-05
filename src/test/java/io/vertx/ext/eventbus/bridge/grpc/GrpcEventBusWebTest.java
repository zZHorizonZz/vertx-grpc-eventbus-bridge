package io.vertx.ext.eventbus.bridge.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.grpc.common.GrpcMessage;
import io.vertx.grpc.common.impl.GrpcMessageImpl;
import io.vertx.grpc.event.v1alpha.EventMessage;
import io.vertx.grpc.event.v1alpha.EventRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Base64;

import static io.vertx.core.http.HttpHeaders.ACCEPT;
import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static io.vertx.grpc.common.GrpcMediaType.GRPC_WEB_TEXT;
import static org.junit.Assert.assertEquals;

public class GrpcEventBusWebTest extends GrpcEventBusBridgeTestBase {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private static final int PREFIX_SIZE = 5;

    private static final String GRPC_STATUS = "grpc-status";
    private static final String STATUS_OK = GRPC_STATUS + ":" + 0 + "\r\n";

    private static final CharSequence USER_AGENT = HttpHeaders.createOptimized("X-User-Agent");
    private static final CharSequence GRPC_WEB_JAVASCRIPT_0_1 = HttpHeaders.createOptimized("grpc-web-javascript/0.1");
    private static final CharSequence GRPC_WEB = HttpHeaders.createOptimized("X-Grpc-Web");
    private static final CharSequence TRUE = HttpHeaders.createOptimized("1");

    private HttpClient client;

    @Override
    public void before(TestContext context) {
        super.before(context);

        // Create HTTP client with appropriate options for gRPC-Web
        HttpClientOptions options = new HttpClientOptions().setDefaultHost("localhost").setDefaultPort(7000);
        this.client = vertx.createHttpClient(options);

        ping().onComplete(res -> {
            if (res.succeeded()) {
                System.out.println("[TEST] Ping successful");
            } else {
                context.fail("Ping failed: " + res.cause().getMessage());
            }
        });
    }

    @Override
    public void after(TestContext context) {
        super.after(context);

        Async async = context.async();

        if (client != null) {
            client.close().onComplete(v -> {
                System.out.println("[TEST] Client closed");
                vertx.close().onComplete(context.asyncAssertSuccess(h -> {
                    System.out.println("[TEST] Vert.x closed");
                    async.complete();
                }));
            });
        }
    }

    @Test
    public void testRequestResponse(TestContext context) {
        Async async = context.async();

        // Send a request to the "hello" address
        JsonObject request = new JsonObject().put("value", "Vert.x");

        request("hello", request, 5000).onSuccess(response -> {
            System.out.println("[TEST] Received response: " + response);

            // Verify the response
            context.assertTrue(response.containsKey("value"));
            context.assertEquals("Hello Vert.x", response.getString("value"));

            async.complete();
        }).onFailure(err -> context.fail("Request failed: " + err.getMessage()));
    }

    @Test
    public void testEcho(TestContext context) {
        Async async = context.async();

        // Create a complex JSON object
        JsonObject request = new JsonObject()
                .put("string", "value")
                .put("number", 123)
                .put("boolean", true)
                .put("nested", new JsonObject().put("key", "value"));

        request("echo", request, 5000).onSuccess(response -> {
            System.out.println("[TEST] Received echo response: " + response);

            context.assertEquals(request.getString("string"), response.getString("string"));
            context.assertEquals(request.getInteger("number"), response.getInteger("number"));
            context.assertEquals(request.getBoolean("boolean"), response.getBoolean("boolean"));
            context.assertEquals(request.getJsonObject("nested").getString("key"), response.getJsonObject("nested").getString("key"));

            async.complete();
        }).onFailure(err -> context.fail("Echo request failed: " + err.getMessage()));
    }

    @Test
    public void testPublishToRestrictedAddress(TestContext context) {
        Async async = context.async();

        JsonObject message = new JsonObject().put("key", "value");

        publish("restricted-address", message)
                .onSuccess(v -> context.fail("Expected publish to fail, but it succeeded"))
                .onFailure(err -> {
                    System.out.println("[TEST] Publish to restricted address failed as expected: " + err.getMessage());
                    async.complete();
                });
    }

    /*@Test
    public void testSubscribeToAddress(TestContext context) {
        Async async = context.async();

        String address = "test-address";

        subscribe(address).onSuccess(buffer -> {
            System.out.println("[TEST] Subscription successful");

            // Simulate sending a message to the subscribed address
            vertx.eventBus().send(address, new JsonObject().put("key", "value"));

            // Wait for the message to be received
            vertx.setTimer(1000, id -> {
                System.out.println("[TEST] Received message on subscribed address");
                async.complete();
            });
        }).onFailure(err -> context.fail("Subscription failed: " + err.getMessage()));
    }*/

    protected MultiMap requestHeaders() {
        return HttpHeaders.headers()
                .add(ACCEPT, GRPC_WEB_TEXT)
                .add(CONTENT_TYPE, GRPC_WEB_TEXT)
                .add(USER_AGENT, GRPC_WEB_JAVASCRIPT_0_1)
                .add(GRPC_WEB, TRUE);
    }

    protected Buffer encode(Message message) {
        Buffer buffer = GrpcMessageImpl.encode(GrpcMessage.message("identity", Buffer.buffer(message.toByteArray())));
        // The whole message must be encoded at once when sending
        return Buffer.buffer(ENCODER.encode(buffer.getBytes()));
    }

    protected Buffer decodeBody(Buffer buffer) {
        // The server sends base64 encoded chunks of arbitrary size
        // All we know is that a 4-bytes block is always a valid base64 payload
        assertEquals(0, buffer.length() % 4);
        Buffer res = Buffer.buffer();
        for (int i = 0; i < buffer.length(); i += 4) {
            byte[] block = buffer.getBytes(i, i + 4);
            res.appendBytes(DECODER.decode(block));
        }
        return res;
    }

    public Future<JsonObject> request(String address, JsonObject message, long timeout) {
        Promise<JsonObject> promise = Promise.promise();

        EventRequest request = EventRequest.newBuilder()
                .setAddress(address)
                .setBody(GrpcEventBusBridgeTestBase.jsonToStruct(message))
                .setTimeout(timeout)
                .build();

        client.request(HttpMethod.POST, "/vertx.event.v1alpha.EventBusBridge/Request")
                .compose(httpRequest -> {
                    requestHeaders().forEach(httpRequest::putHeader);

                    return httpRequest.send(encode(request)).onSuccess(response -> {
                                if (response.statusCode() == 200) {
                                    response.body().onSuccess(buffer -> {
                                        try {
                                            Buffer body = decodeBody(buffer);
                                            int pos = 0;

                                            Buffer prefix = body.getBuffer(pos, PREFIX_SIZE);
                                            assertEquals(0x00, prefix.getUnsignedByte(0)); // Uncompressed message
                                            int len = prefix.getInt(1);
                                            pos += PREFIX_SIZE;

                                            EventMessage eventMessage = EventMessage.parseFrom(body.getBuffer(pos, pos + len).getBytes());
                                            pos += len;

                                            Buffer trailer = body.getBuffer(pos, body.length());
                                            assertEquals(0x80, trailer.getUnsignedByte(0)); // Uncompressed trailer
                                            len = trailer.getInt(1);
                                            assertEquals(STATUS_OK, trailer.getBuffer(PREFIX_SIZE, PREFIX_SIZE + len).toString());

                                            System.out.println("[WEB CLIENT] Received response from " + address);
                                            promise.complete(GrpcEventBusBridgeTestBase.structToJson(eventMessage.getBody()));
                                        } catch (Exception e) {
                                            promise.fail("Failed to parse response: " + e.getMessage());
                                        }
                                    });
                                } else {
                                    String error = "HTTP error: " + response.statusCode() + " " + response.statusMessage();
                                    System.out.println("[WEB CLIENT] Failed to get response from " + address + ": " + error);
                                    promise.fail(error);
                                }
                            })
                            .onFailure(err -> {
                                System.out.println("[WEB CLIENT] Failed to get response from " + address + ": " + err.getMessage());
                                promise.fail(err);
                            });
                })
                .onFailure(err -> {
                    System.out.println("[WEB CLIENT] Failed to create request: " + err.getMessage());
                    promise.fail(err);
                });

        return promise.future();
    }

    public Future<Void> publish(String address, JsonObject message) {
        Promise<Void> promise = Promise.promise();

        EventRequest request = EventRequest.newBuilder()
                .setAddress(address)
                .setBody(GrpcEventBusBridgeTestBase.jsonToStruct(message))
                .build();

        client.request(HttpMethod.POST, "/vertx.event.v1alpha.EventBusBridge/Publish").compose(httpRequest -> {
                    requestHeaders().forEach(httpRequest::putHeader);

                    return httpRequest.send(encode(request))
                            .onSuccess(response -> {
                                if (response.statusCode() == 200 && !response.headers().contains(GRPC_STATUS)) {
                                    System.out.println("[WEB CLIENT] Message published to " + address);
                                    promise.complete();
                                } else {
                                    String error = "HTTP error: " + response.statusCode() + " " + response.statusMessage();
                                    System.out.println("[WEB CLIENT] Failed to publish message to " + address + ": " + error);
                                    promise.fail(error);
                                }
                            })
                            .onFailure(err -> {
                                System.out.println("[WEB CLIENT] Failed to publish message to " + address + ": " + err.getMessage());
                                promise.fail(err);
                            });
                })
                .onFailure(err -> {
                    System.out.println("[WEB CLIENT] Failed to create request: " + err.getMessage());
                    promise.fail(err);
                });

        return promise.future();
    }

    /*public Future<Buffer> subscribe(String address) {
        Promise<Buffer> promise = Promise.promise();

        client.request(HttpMethod.POST, "/vertx.event.v1alpha.EventBusBridge/Subscribe").compose(httpRequest -> {
                    requestHeaders().forEach(httpRequest::putHeader);

                    EventRequest request = EventRequest.newBuilder()
                            .setAddress(address)
                            .build();

                    return httpRequest.send(encode(request)).compose(response -> response.body().map(response))
                            .onSuccess(response -> {
                                if (response.statusCode() == 200) {
                                    response.body().onSuccess(buffer -> {
                                        System.out.println("[WEB CLIENT] Subscription successful");
                                        promise.complete(decodeBody(buffer));
                                    });
                                } else {
                                    String error = "HTTP error: " + response.statusCode() + " " + response.statusMessage();
                                    System.out.println("[WEB CLIENT] Subscription failed: " + error);
                                    promise.fail(error);
                                }
                            })
                            .onFailure(err -> {
                                System.out.println("[WEB CLIENT] Subscription failed: " + err.getMessage());
                                promise.fail(err);
                            });
                })
                .onFailure(err -> {
                    System.out.println("[WEB CLIENT] Failed to create request: " + err.getMessage());
                    promise.fail(err);
                });

        return promise.future();
    }*/

    public Future<Void> ping() {
        Promise<Void> promise = Promise.promise();

        client.request(HttpMethod.POST, "/vertx.event.v1alpha.EventBusBridge/Ping").compose(httpRequest -> {
                    requestHeaders().forEach(httpRequest::putHeader);
                    return httpRequest.send(encode(Empty.getDefaultInstance()))
                            .onSuccess(response -> {
                                if (response.statusCode() == 200) {
                                    System.out.println("[WEB CLIENT] Ping successful");
                                    promise.complete();
                                } else {
                                    String error = "HTTP error: " + response.statusCode() + " " + response.statusMessage();
                                    System.out.println("[WEB CLIENT] Ping failed: " + error);
                                    promise.fail(error);
                                }
                            })
                            .onFailure(err -> {
                                System.out.println("[WEB CLIENT] Ping failed: " + err.getMessage());
                                promise.fail(err);
                            });
                })
                .onFailure(err -> {
                    System.out.println("[WEB CLIENT] Failed to create request: " + err.getMessage());
                    promise.fail(err);
                });

        return promise.future();
    }
}
