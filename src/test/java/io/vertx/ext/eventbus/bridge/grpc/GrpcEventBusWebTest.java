package io.vertx.ext.eventbus.bridge.grpc;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.ServerSocket;

@RunWith(VertxUnitRunner.class)
public class GrpcEventBusWebTest extends GrpcEventBusBridgeTestBase {

    private static final String HOST = "localhost";

    private Vertx vertx;
    private GrpcEventBusWebClient client;
    private String serverDeploymentId;
    private int port;

    private int findAvailablePort() {
        try {
            ServerSocket socket = new ServerSocket(0);
            int port = socket.getLocalPort();
            socket.close();
            return port;
        } catch (IOException e) {
            // If we can't find a port, use a default port and hope it's available ¯\_(ツ)_/¯
            return 8000 + (int) (Math.random() * 1000);
        }
    }

    @Before
    public void setUp(TestContext context) {
        Async async = context.async();

        vertx = Vertx.vertx();

        port = findAvailablePort();
        System.out.println("[TEST] Using port: " + port);

        GrpcEventBusServer.deploy(vertx, port)
                .onSuccess(deploymentId -> {
                    serverDeploymentId = deploymentId;
                    System.out.println("[TEST] Server deployed with ID: " + deploymentId);

                    // Create the client
                    client = new GrpcEventBusWebClient(vertx, HOST, port);

                    // Ping the server to make sure it's ready
                    client.ping()
                            .onSuccess(v -> {
                                System.out.println("[TEST] Server is ready");
                                async.complete();
                            })
                            .onFailure(err -> {
                                context.fail("Failed to ping server: " + err.getMessage());
                            });
                })
                .onFailure(err -> {
                    context.fail("Failed to deploy server: " + err.getMessage());
                });
    }

    @After
    public void tearDown(TestContext context) {
        Async async = context.async();

        if (client != null) {
            client.close().onComplete(v -> {
                System.out.println("[TEST] Client closed");

                if (serverDeploymentId != null) {
                    vertx.undeploy(serverDeploymentId).onComplete(res -> {
                        System.out.println("[TEST] Server undeployed");

                        vertx.close().onComplete(context.asyncAssertSuccess(h -> {
                            System.out.println("[TEST] Vert.x closed");
                            async.complete();
                        }));
                    });
                } else {
                    vertx.close().onComplete(context.asyncAssertSuccess(h -> {
                        System.out.println("[TEST] Vert.x closed");
                        async.complete();
                    }));
                }
            });
        } else {
            // Undeploy the server
            if (serverDeploymentId != null) {
                vertx.undeploy(serverDeploymentId).onComplete(res -> {
                    System.out.println("[TEST] Server undeployed");

                    // Close Vert.x
                    vertx.close().onComplete(context.asyncAssertSuccess(h -> {
                        System.out.println("[TEST] Vert.x closed");
                        async.complete();
                    }));
                });
            } else {
                // Close Vert.x
                vertx.close().onComplete(context.asyncAssertSuccess(h -> {
                    System.out.println("[TEST] Vert.x closed");
                    async.complete();
                }));
            }
        }
    }

    @Test
    public void testRequestResponse(TestContext context) {
        Async async = context.async();

        // Send a request to the "hello" address
        JsonObject request = new JsonObject().put("name", "Vert.x");

        client.request("hello", request, 5000).onSuccess(response -> {
            System.out.println("[TEST] Received response: " + response);

            // Verify the response
            context.assertTrue(response.containsKey("message"));
            context.assertEquals("Hello Vert.x", response.getString("message"));

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

        client.request("echo", request, 5000).onSuccess(response -> {
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

        client.publish("restricted-address", message)
                .onSuccess(v -> context.fail("Expected publish to fail, but it succeeded"))
                .onFailure(err -> {
                    System.out.println("[TEST] Publish to restricted address failed as expected: " + err.getMessage());
                    async.complete();
                });
    }
}