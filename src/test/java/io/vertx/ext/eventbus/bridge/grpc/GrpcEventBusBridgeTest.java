package io.vertx.ext.eventbus.bridge.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.event.v1alpha.EventBusBridgeGrpcClient;
import io.vertx.grpc.event.v1alpha.EventRequest;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class GrpcEventBusBridgeTest extends GrpcEventBusBridgeTestBase {

    private GrpcClient client;
    private EventBusBridgeGrpcClient grpcClient;

    @Override
    public void before(TestContext context) {
        super.before(context);

        client = GrpcClient.client(vertx);

        SocketAddress socketAddress = SocketAddress.inetSocketAddress(7000, "localhost");
        grpcClient = EventBusBridgeGrpcClient.create(client, socketAddress);
    }

    @After
    public void after(TestContext context) {
        Async async = context.async();

        super.after(context);

        if (client != null) {
            client.close().onComplete(c -> vertx.close().onComplete(context.asyncAssertSuccess(h -> async.complete())));
        }
    }

    @Test
    public void testSendVoidMessage(TestContext context) {
        final Async async = context.async();

        vertx.eventBus().consumer("test", (Message<JsonObject> msg) -> {
            context.assertEquals("vert.x", msg.body().getString("value"));
            async.complete();
        });

        final EventRequest request = EventRequest.newBuilder()
                .setAddress("test")
                .setBody(jsonToStruct(new JsonObject().put("value", "vert.x")))
                .build();

        grpcClient.send(request).onComplete(context.asyncAssertSuccess(response -> context.assertFalse(response.hasStatus())));
    }

    @Test
    public void testSendWithReply(TestContext context) {
        final Async async = context.async();
        final EventRequest request = EventRequest.newBuilder()
                .setAddress("hello")
                .setReplyAddress("reply-address")
                .setBody(jsonToStruct(new JsonObject().put("value", "vert.x")))
                .build();

        grpcClient.send(request).onComplete(context.asyncAssertSuccess(response -> {
            context.assertFalse(response.hasStatus());
            async.complete();
        }));
    }

    @Test
    public void testRequest(TestContext context) {
        final Async async = context.async();
        final EventRequest request = EventRequest.newBuilder()
                .setAddress("hello")
                .setBody(jsonToStruct(new JsonObject().put("value", "vert.x")))
                .setTimeout(5000)
                .build();

        grpcClient.request(request).onComplete(context.asyncAssertSuccess(response -> {
            context.assertFalse(response.hasStatus());
            JsonObject responseBody = structToJson(response.getBody());
            context.assertEquals("Hello vert.x", responseBody.getString("value"));
            async.complete();
        }));
    }

    @Test
    public void testPublish(TestContext context) {
        final Async async = context.async();
        final AtomicBoolean received = new AtomicBoolean(false);

        vertx.eventBus().consumer("test", (Message<JsonObject> msg) -> {
            context.assertEquals("vert.x", msg.body().getString("value"));
            if (received.compareAndSet(false, true)) {
                async.complete();
            }
        });

        final EventRequest request = EventRequest.newBuilder()
                .setAddress("test")
                .setBody(jsonToStruct(new JsonObject().put("value", "vert.x")))
                .build();

        grpcClient.publish(request).onComplete(context.asyncAssertSuccess(response -> {

        }));
    }

    @Test
    public void testSubscribe(TestContext context) {
        final Async async = context.async();
        final AtomicReference<String> consumerId = new AtomicReference<>();
        final EventRequest request = EventRequest.newBuilder().setAddress("ping").build();

        grpcClient.subscribe(request).onComplete(context.asyncAssertSuccess(stream -> stream.handler(response -> {
            consumerId.set(response.getConsumer());

            context.assertEquals("ping", response.getAddress());
            context.assertNotNull(response.getBody());

            Struct body = response.getBody();
            context.assertEquals("hi", body.getFieldsMap().get("value").getStringValue());

            EventRequest unsubRequest = EventRequest.newBuilder()
                    .setAddress("ping")
                    .setConsumer(consumerId.get())
                    .build();

            grpcClient.unsubscribe(unsubRequest).onComplete(context.asyncAssertSuccess(unsubResponse -> async.complete()));
        })));
    }

    @Test
    public void testPing(TestContext context) {
        final Async async = context.async();
        grpcClient.ping(Empty.getDefaultInstance()).onComplete(context.asyncAssertSuccess(response -> async.complete()));
    }

    /*@Test
    public void testStreamCancelation(TestContext context) {
        final Async async = context.async();
        final AtomicBoolean messageReceived = new AtomicBoolean(false);
        final AtomicBoolean streamCanceled = new AtomicBoolean(false);
        final AtomicReference<String> consumerId = new AtomicReference<>();

        Handler<BridgeEvent> originalHandler = eventHandler;
        eventHandler = event -> {
            if (event.type() == BridgeEventType.UNREGISTER && messageReceived.get()) {
                streamCanceled.set(true);

                EventRequest secondUnsubRequest = EventRequest.newBuilder()
                        .setAddress("ping")
                        .setConsumer(consumerId.get())
                        .build();

                grpcClient.unsubscribe(secondUnsubRequest).onComplete(context.asyncAssertFailure(err -> {
                    async.complete();
                }));
            }
            originalHandler.handle(event);
        };

        EventRequest request = EventRequest.newBuilder().setAddress("ping").build();
        grpcClient.subscribe(request).onComplete(context.asyncAssertSuccess(stream -> {
            stream.handler(response -> {
                context.assertFalse(response.hasStatus());
                context.assertEquals("ping", response.getAddress());
                context.assertNotNull(response.getBody());

                Struct body = response.getBody();
                context.assertEquals("hi", body.getFieldsMap().get("value").getStringValue());

                consumerId.set(response.getConsumer());
                messageReceived.set(true);

                // Cancel the stream properly through gRPC
                // stream.endHandler(null);
            });
        })).timeout(10, TimeUnit.SECONDS);
    }*/

    @Test
    public void testUnsubscribeWithoutReceivingMessage(TestContext context) {
        final Async async = context.async();
        final AtomicReference<String> consumerId = new AtomicReference<>();
        final EventRequest request = EventRequest.newBuilder().setAddress("ping").build();

        grpcClient.subscribe(request).onComplete(context.asyncAssertSuccess(stream -> stream.handler(response -> {
            consumerId.set(response.getConsumer());
            EventRequest unsubRequest = EventRequest.newBuilder()
                    .setAddress("ping")
                    .setConsumer(consumerId.get())
                    .build();

            grpcClient.unsubscribe(unsubRequest).onComplete(context.asyncAssertSuccess(unsubResponse -> {
                vertx.setTimer(1000, id -> async.complete());
                stream.handler(msg -> context.fail("Received message after unsubscribe"));
            }));
        })));
    }

    @Test
    public void testUnsubscribeInvalidConsumerId(TestContext context) {
        final Async async = context.async();
        final EventRequest unsubRequest = EventRequest.newBuilder()
                .setAddress("ping")
                .setConsumer("invalid-consumer-id")
                .build();

        grpcClient.unsubscribe(unsubRequest).onComplete(context.asyncAssertFailure(err -> async.complete()));
    }

    @Test
    public void testMultipleSubscribeAndUnsubscribe(TestContext context) {
        final Async async = context.async(2);
        final AtomicReference<String> consumerId1 = new AtomicReference<>();
        final AtomicReference<String> consumerId2 = new AtomicReference<>();
        final EventRequest request = EventRequest.newBuilder().setAddress("ping").build();

        // First subscription
        grpcClient.subscribe(request).onComplete(context.asyncAssertSuccess(stream1 -> stream1.handler(response -> {
            if (consumerId1.get() != null) {
                return;
            }

            consumerId1.set(response.getConsumer());

            // Second subscription
            grpcClient.subscribe(request).onComplete(context.asyncAssertSuccess(stream2 -> stream2.handler(response2 -> {
                if (consumerId2.get() != null) {
                    return;
                }

                consumerId2.set(response2.getConsumer());
                context.assertNotEquals(consumerId1.get(), consumerId2.get());

                EventRequest unsubRequest1 = EventRequest.newBuilder()
                        .setAddress("ping")
                        .setConsumer(consumerId1.get())
                        .build();

                grpcClient.unsubscribe(unsubRequest1).onComplete(context.asyncAssertSuccess(unsubResponse1 -> {
                    async.countDown();
                    EventRequest unsubRequest2 = EventRequest.newBuilder()
                            .setAddress("ping")
                            .setConsumer(consumerId2.get())
                            .build();

                    grpcClient.unsubscribe(unsubRequest2).onComplete(context.asyncAssertSuccess(unsubResponse2 -> async.countDown()));
                }));
            })));
        })));
    }

    @Test
    public void testMultipleMessagesInStream(TestContext context) {
        final Async async = context.async();
        final AtomicInteger messageCount = new AtomicInteger(0);
        final AtomicReference<String> consumerId = new AtomicReference<>();
        final AtomicReference<Long> firstMessageTime = new AtomicReference<>();
        final AtomicReference<Long> lastMessageTime = new AtomicReference<>();
        final EventRequest request = EventRequest.newBuilder().setAddress("ping").build();
        final int expectedMessages = 3;

        grpcClient.subscribe(request).onComplete(context.asyncAssertSuccess(stream -> stream.handler(response -> {
            if (consumerId.get() == null) {
                consumerId.set(response.getConsumer());
            }

            context.assertEquals("ping", response.getAddress());
            context.assertNotNull(response.getBody());

            Struct body = response.getBody();
            context.assertEquals("hi", body.getFieldsMap().get("value").getStringValue());

            long currentTime = System.currentTimeMillis();
            if (firstMessageTime.get() == null) {
                firstMessageTime.set(currentTime);
            }

            lastMessageTime.set(currentTime);

            int count = messageCount.incrementAndGet();
            System.out.println("[DEBUG] Received message " + count + " of " + expectedMessages);

            if (count >= expectedMessages) {
                long timeDifference = lastMessageTime.get() - firstMessageTime.get();
                context.assertTrue(timeDifference >= 1000, "Expected delay between messages, but got: " + timeDifference + "ms");
                System.out.println("[DEBUG] Time difference between first and last message: " + timeDifference + "ms");

                EventRequest unsubRequest = EventRequest.newBuilder()
                        .setAddress("ping")
                        .setConsumer(consumerId.get())
                        .build();

                grpcClient.unsubscribe(unsubRequest).onComplete(context.asyncAssertSuccess(unsubResponse -> async.complete()));
            }
        })));
    }
}
