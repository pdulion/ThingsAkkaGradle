package com.dulion.akka.iot;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import com.dulion.akka.iot.Device.ReadTemperatureReply;
import com.dulion.akka.iot.Manager.AllTemperaturesReply;
import com.dulion.akka.iot.Manager.DeviceNotAvailable;
import com.dulion.akka.iot.Manager.DeviceTimedOut;
import com.dulion.akka.iot.Manager.Temperature;
import com.dulion.akka.iot.Manager.TemperatureNotAvailable;
import com.google.common.annotations.VisibleForTesting;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import lombok.Value;

public class Query extends AbstractBehavior<Query.Request> {

  public interface Request {}

  @VisibleForTesting
  @Value(staticConstructor = "of")
  static class TemperatureReplyWrapper implements Request {
    ReadTemperatureReply reply;
  }

  @VisibleForTesting
  @Value(staticConstructor = "of")
  static class DeviceTerminated implements Request {
    String deviceId;
  }

  private enum CollectionTimeout implements Request {
    INSTANCE
  }

  /**
   * Create behavior for device group query.
   *
   * @param requestId       - tracking identifier.
   * @param replyTo         - where to send the results.
   * @param deviceIdToActor - map collection of devices to query.
   * @param timeout         - maximum time to wait for temperatures replies.
   * @return Behavior - reference to group query supplier.
   */
  public static Behavior<Request> create(
      ActorRef<Manager.AllTemperaturesReply> replyTo,
      long requestId,
      Map<String, ActorRef<Device.Request>> deviceIdToActor,
      Duration timeout) {
    return Behaviors.setup(
        context -> Behaviors.withTimers(
            timers -> new Query(
                requestId,
                replyTo,
                deviceIdToActor,
                timeout,
                context,
                timers)));
  }

  private final long requestId;
  private final ActorRef<AllTemperaturesReply> replyTo;
  private final HashSet<String> waiting;
  private final Map<String, Manager.TemperatureReading> replies = new HashMap<>();

  private Query(
      long requestId,
      ActorRef<AllTemperaturesReply> replyTo,
      Map<String, ActorRef<Device.Request>> deviceIdToActor,
      Duration timeout,
      ActorContext<Request> context,
      TimerScheduler<Request> timers) {
    super(context);
    this.requestId = requestId;
    this.replyTo = replyTo;
    this.waiting = new HashSet<>(deviceIdToActor.keySet());

    timers.startSingleTimer(CollectionTimeout.INSTANCE, timeout);
    ActorRef<ReadTemperatureReply> adapter = context.messageAdapter(
        ReadTemperatureReply.class,
        TemperatureReplyWrapper::of);

    deviceIdToActor.forEach((key, value) -> {
      context.watchWith(value, new DeviceTerminated(key));
      value.tell(Device.ReadTemperatureRequest.of(requestId, adapter));
    });
  }

  @Override
  public Receive<Request> createReceive() {
    return newReceiveBuilder()
        .onMessage(TemperatureReplyWrapper.class, this::onTemperatureReply)
        .onMessage(DeviceTerminated.class, this::onDeviceTerminated)
        .onMessage(CollectionTimeout.class, this::onCollectionTimeout)
        .build();
  }

  private Behavior<Request> onTemperatureReply(TemperatureReplyWrapper wrapper) {
    ReadTemperatureReply reply = wrapper.getReply();
    Manager.TemperatureReading reading = Optional.ofNullable(reply.getTemperature())
        .map(t -> (Manager.TemperatureReading) Temperature.of(t))
        .orElse(TemperatureNotAvailable.READING_NOT_AVAILABLE);
    replies.put(reply.getDeviceId(), reading);
    waiting.remove(reply.getDeviceId());
    return respondWhenCollected();
  }

  private Behavior<Request> onDeviceTerminated(DeviceTerminated terminated) {
    String deviceId = terminated.getDeviceId();
    if (waiting.remove(deviceId)) {
      replies.put(deviceId, DeviceNotAvailable.DEVICE_NOT_AVAILABLE);
    }
    return respondWhenCollected();
  }

  private Behavior<Request> onCollectionTimeout(CollectionTimeout timeout) {
    waiting.forEach(deviceId -> replies.put(deviceId, DeviceTimedOut.DEVICE_TIMED_OUT));
    waiting.clear();
    return respondWhenCollected();
  }

  private Behavior<Request> respondWhenCollected() {
    if (!waiting.isEmpty()) {
      return this;
    }
    replyTo.tell(Manager.AllTemperaturesReply.of(requestId, replies));
    return Behaviors.stopped();
  }
}
