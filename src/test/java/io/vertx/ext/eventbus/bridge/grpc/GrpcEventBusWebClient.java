package io.vertx.ext.eventbus.bridge.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.grpc.common.GrpcMessage;
import io.vertx.grpc.common.impl.GrpcMessageImpl;
import io.vertx.grpc.event.v1alpha.EventMessage;
import io.vertx.grpc.event.v1alpha.EventRequest;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.vertx.core.http.HttpHeaders.*;
import static io.vertx.grpc.common.GrpcMediaType.GRPC_WEB_TEXT;
import static org.junit.Assert.assertEquals;

/**
 * A client for the gRPC EventBus Bridge that uses gRPC-Web. This client simulates a web browser connecting to the gRPC server using HTTP/1.1.
 */
public class GrpcEventBusWebClient {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private static final int PREFIX_SIZE = 5;

    private static final String GRPC_STATUS = "grpc-status";
    private static final String STATUS_OK = GRPC_STATUS + ":" + 0 + "\r\n";

    private final Vertx vertx;
    private final HttpClient httpClient;
    private final String host;
    private final int port;
    private final Map<String, WebSocket> subscriptions = new ConcurrentHashMap<>();

    public GrpcEventBusWebClient(Vertx vertx, String host, int port) {
        this.vertx = vertx;
        this.host = host;
        this.port = port;

        // Create HTTP client with appropriate options for gRPC-Web
        HttpClientOptions options = new HttpClientOptions()
                .setDefaultHost(host)
                .setDefaultPort(port);

        this.httpClient = vertx.createHttpClient(options);
    }

    protected MultiMap requestHeaders() {
        return HttpHeaders.headers()
                .add(ACCEPT, GRPC_WEB_TEXT)
                .add(CONTENT_TYPE, GRPC_WEB_TEXT)
                .add(USER_AGENT, HttpHeaders.createOptimized("grpc-web-javascript/0.1"))
                .add(HttpHeaders.createOptimized("X-Grpc-Web"), HttpHeaders.createOptimized("1"));
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

    /**
     * Send a message to an address and expect a reply.
     *
     * @param address the address to send to
     * @param message the message to send
     * @param timeout the timeout in milliseconds
     * @return a future completed with the reply
     */
    public Future<JsonObject> request(String address, JsonObject message, long timeout) {
        Promise<JsonObject> promise = Promise.promise();

        EventRequest request = EventRequest.newBuilder()
                .setAddress(address)
                .setBody(GrpcEventBusBridgeTestBase.jsonToStruct(message))
                .setTimeout(timeout)
                .build();

        httpClient.request(HttpMethod.POST, "/vertx.event.v1alpha.EventBusBridge/Request")
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

    /**
     * Publish a message to an address.
     *
     * @param address the address to publish to
     * @param message the message to publish
     * @return a future completed when the message is published
     */
    public Future<Void> publish(String address, JsonObject message) {
        Promise<Void> promise = Promise.promise();

        EventRequest request = EventRequest.newBuilder()
                .setAddress(address)
                .setBody(GrpcEventBusBridgeTestBase.jsonToStruct(message))
                .build();

        httpClient.request(HttpMethod.POST, "/vertx.event.v1alpha.EventBusBridge/Publish").compose(httpRequest -> {
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

    /**
     * Ping the server to check connectivity.
     *
     * @return a future completed when the ping is successful
     */
    public Future<Void> ping() {
        Promise<Void> promise = Promise.promise();

        httpClient.request(HttpMethod.POST, "/vertx.event.v1alpha.EventBusBridge/Ping").compose(httpRequest -> {
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

    /**
     * Close the client.
     *
     * @return a future completed when the client is closed
     */
    public Future<Void> close() {
        return httpClient.close();
    }
}