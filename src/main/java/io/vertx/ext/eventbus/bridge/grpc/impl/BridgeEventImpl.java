package io.vertx.ext.eventbus.bridge.grpc.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.eventbus.bridge.grpc.BridgeEvent;

class BridgeEventImpl implements BridgeEvent {

  private final BridgeEventType type;
  private final JsonObject rawMessage;
  private final Promise<Boolean> promise;

  public BridgeEventImpl(BridgeEventType type, JsonObject rawMessage) {
    this.type = type;
    this.rawMessage = rawMessage;
    this.promise = Promise.promise();
  }

  @Override
  public Future<Boolean> future() {
    return promise.future();
  }

  @Override
  public BridgeEventType type() {
    return type;
  }

  @Override
  public JsonObject getRawMessage() {
    return rawMessage;
  }

  @Override
  public BridgeEvent setRawMessage(JsonObject message) {
    if (message != rawMessage) {
      rawMessage.clear().mergeIn(message);
    }
    return this;
  }

  @Override
  public void handle(AsyncResult<Boolean> asyncResult) {
    promise.handle(asyncResult);
  }

  @Override
  public void complete(Boolean result) {
    promise.complete(result);
  }

  @Override
  public void complete() {
    promise.complete();
  }

  @Override
  public void fail(Throwable throwable) {
    promise.fail(throwable);
  }

  @Override
  public void fail(String failureMessage) {
    promise.fail(failureMessage);
  }

  @Override
  public boolean tryComplete(Boolean result) {
    return promise.tryComplete(result);
  }

  @Override
  public boolean tryComplete() {
    return promise.tryComplete();
  }

  @Override
  public boolean tryFail(Throwable cause) {
    return promise.tryFail(cause);
  }

  @Override
  public boolean tryFail(String failureMessage) {
    return promise.tryFail(failureMessage);
  }
}